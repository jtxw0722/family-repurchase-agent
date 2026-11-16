package com.jtxw.familyagent.application;

import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.*;
import com.jtxw.familyagent.domain.policy.*;
import com.jtxw.familyagent.infrastructure.importer.*;
import com.jtxw.familyagent.infrastructure.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/15 12:51:46
 * @Description: 在线导入应用服务，本地规则生成只读预览，并在确认时仅保存勾选后的业务字段，不保留上传文件或原文
 */
@Service
public class OnlineOrderImportService {
    /** 文件字节数上限，10 MiB，与截图上传保持相同规模。 */
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    /** 历史中位数偏差倍数阈值，超出上下三倍范围时默认不勾选。 */
    private static final BigDecimal PRICE_DEVIATION = new BigDecimal("3");
    /** 内存解析器，不创建文件。 */
    private final OnlineOrderFileReader reader;
    /** 复用本地规格解析组件，不调用模型。 */
    private final ProductSpecParser productSpecParser;
    /** 复用主行、续行处理，关闭或未完成交易不纳入待入库列表。 */
    private final ChineseOrderExportRowNormalizer rowNormalizer;
    /** 订单级金额分摊策略，仅在预览内存中执行。 */
    private final OrderAmountAllocationPolicy allocationPolicy;
    /** 支付口径异常识别策略。 */
    private final PaymentAdjustmentPolicy paymentPolicy;
    /** 只读本地规则提供器，每次预览读取一次快照，不使用学习型归一化器。 */
    private final ProductRuleProvider productRuleProvider;
    /** 日期合法性与格式规范化策略。 */
    private final PurchaseTimeNormalizer timeNormalizer;
    /** 用户指定的家庭归属人规范化策略。 */
    private final OwnerNormalizer ownerNormalizer;
    /** 购买记录只读查重及确认后的保存仓储。 */
    private final PurchaseRecordRepository repository;
    /** 读取同单位历史中位数用于预览异常提醒。 */
    private final NormalizationRuleRepository ruleRepository;

    /** 创建在线导入编排服务；构造阶段不执行解析或数据库写入。 */
    public OnlineOrderImportService(OnlineOrderFileReader reader, ProductSpecParser productSpecParser,
            ChineseOrderExportRowNormalizer rowNormalizer, OrderAmountAllocationPolicy allocationPolicy,
            PaymentAdjustmentPolicy paymentPolicy, ProductRuleProvider productRuleProvider,
            PurchaseTimeNormalizer timeNormalizer, OwnerNormalizer ownerNormalizer,
            PurchaseRecordRepository repository, NormalizationRuleRepository ruleRepository) {
        this.reader = reader;
        this.productSpecParser = productSpecParser;
        this.rowNormalizer = rowNormalizer;
        this.allocationPolicy = allocationPolicy;
        this.paymentPolicy = paymentPolicy;
        this.productRuleProvider = productRuleProvider;
        this.timeNormalizer = timeNormalizer;
        this.ownerNormalizer = ownerNormalizer;
        this.repository = repository;
        this.ruleRepository = ruleRepository;
    }

    /**
     * 只在请求内存中解析订单，不初始化数据库、不保存原文件、不返回订单编号或多余列。
     * @param base64 文件内容，最多 10 MiB 解码字节
     * @param format csv 或 xlsx，不接收原文件名
     * @param owner 用户指定归属人，必填，不读取原文件买家或收货人
     * @return 可编辑白名单预览，以及被跳过的未完成交易/空白续行数量
     */
    public Preview preview(String base64, String format, String owner) {
        if (base64 == null || base64.length() > ((MAX_FILE_BYTES + 2) / 3) * 4
                || owner == null || owner.isBlank() || owner.length() > 80) {
            throw new IllegalArgumentException("请选择不超过 10 MB 的订单文件，并填写归属人。");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("文件内容无法读取，请重新选择文件。");
        }
        if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("文件大小不符合要求。");
        List<Map<String, String>> source;
        try { source = reader.read(bytes, format); }
        finally { Arrays.fill(bytes, (byte) 0); }
        List<Map<String, String>> normalized = rowNormalizer.normalize(source);
        // 同一预览使用一致的规则快照，避免逐商品重复读取规则和关键词，也避免中途改规则导致行间口径不一致。
        List<ProductRule> rules = List.copyOf(productRuleProvider.listEnabledRules());
        ProductRuleMatcher ruleMatcher = new ProductRuleMatcher(() -> rules);
        OrderImportMapper mapper = new OrderImportMapper(productSpecParser, ruleMatcher, ownerNormalizer);
        List<RawPurchaseRecord> rawRecords = new ArrayList<>();
        List<Map<String, String>> metadata = new ArrayList<>();
        List<OnlineImportRow> result = new ArrayList<>();
        for (Map<String, String> row : normalized) {
            try {
                RawPurchaseRecord raw = mapper.map(OrderImportMapper.ImportSchema.CHINESE_ORDER_EXPORT,
                        row, Path.of("online-order.csv"), ownerNormalizer.normalize(owner));
                if (raw != null) {
                    if (java.util.stream.Stream.of(raw.quantity(), raw.totalAmount(), raw.productAmount(), raw.paidAmount(), raw.shippingFee())
                            .anyMatch(value -> value != null && !Double.isFinite(value))) throw new IllegalArgumentException();
                    rawRecords.add(raw);
                    metadata.add(row);
                }
            } catch (RuntimeException exception) {
                // 错误行仍可修正；不回传解析异常消息，避免包含原始单元格值或订单号。
                result.add(new OnlineImportRow(Integer.parseInt(row.get("_row")), row.get("商品名称"), "",
                        row.get("型号款式"), null, null, "", "", "", ownerNormalizer.normalize(owner),
                        row.get("店铺名称"), false, List.of("部分字段无法解析，请补全日期、平台、金额和总含量")));
            }
        }
        List<RawPurchaseRecord> allocated = allocationPolicy.allocate(rawRecords);
        Set<String> seen = new HashSet<>();
        List<NormalizationLibraryItem> library = ruleRepository.listLibraryItems();
        for (int i = 0; i < allocated.size(); i++) {
            RawPurchaseRecord raw = allocated.get(i);
            Map<String, String> row = metadata.get(i);
            var match = ruleMatcher.match(raw.productName() + " " + raw.sku());
            var payment = paymentPolicy.adjust(raw);
            List<String> warnings = new ArrayList<>();
            String date = normalizeDate(raw.orderTime());
            // 中文导出中的商品数量表示购买件数；既有映射器返回的是单件标准含量，需乘上实际件数。
            Double purchaseCount = purchaseCount(row.get("商品数量"));
            Double totalQuantity = raw.quantity() == null || purchaseCount == null ? null : "件".equals(raw.unit()) ? raw.quantity()
                    : BigDecimal.valueOf(raw.quantity()).multiply(BigDecimal.valueOf(purchaseCount)).doubleValue();
            OnlineImportRow candidate = new OnlineImportRow(Integer.parseInt(row.get("_row")), raw.productName(),
                    match.matched() ? match.normalizedName() : "", raw.sku(), raw.totalAmount(), totalQuantity,
                    raw.unit(), normalizePlatform(raw.platform()), date, ownerNormalizer.normalize(owner), row.get("店铺名称"), false, List.of());
            warnings.addAll(errors(candidate));
            if (gift(candidate)) warnings.add("赠品或试用商品，默认不勾选");
            if (nonComparable(candidate) && !gift(candidate)) warnings.add("定金、尾款或其他非普通商品记录，默认不勾选");
            if (candidate.amount() != null && candidate.amount() == 0 && !gift(candidate)) warnings.add("零元支付，请核对是否包含购物金或其他抵扣");
            if (raw.specReviewRequired()) warnings.add("规格含量需要核对，特别留意次卡、组合装或包装数量");
            if (payment.reviewRequired() || raw.amountReviewRequired()) warnings.add("支付金额或订单分摊需要核对");
            if (!match.matched()) warnings.add("未匹配归一化规则，请填写归一化名称");
            if (errors(candidate).isEmpty() && (duplicate(candidate) || !seen.add(fingerprint(candidate)))) warnings.add("疑似已有购买记录或文件内重复，默认不勾选");
            if (priceOutlier(candidate, library)) warnings.add("单位价格偏离同单位历史中位数三倍范围，请核对");
            result.add(new OnlineImportRow(candidate.rowNumber(), candidate.productName(), candidate.normalizedName(), candidate.sku(),
                    candidate.amount(), candidate.quantity(), candidate.unit(), candidate.platform(), candidate.purchaseDate(), candidate.owner(),
                    candidate.shopName(), warnings.isEmpty(), List.copyOf(warnings)));
        }
        result.sort(Comparator.comparingInt(OnlineImportRow::rowNumber));
        return new Preview(List.copyOf(result), source.size() - normalized.size());
    }

    /**
     * 确认勾选业务数据；先完整校验，再事务保存。重新检查历史及批内重复，重复行跳过并报告。
     * @param rows 用户编辑后的行，最多 5000 行；只处理 selected 为 true 的行
     * @return 实际保存与跳过重复的行号，无原文及文件信息
     */
    @Transactional
    public synchronized CommitResult commit(List<OnlineImportRow> rows) {
        if (rows == null || rows.size() > OnlineOrderFileReader.MAX_ROWS) throw new IllegalArgumentException("提交行数不符合要求。");
        List<OnlineImportRow> selected = rows.stream().filter(Objects::nonNull).filter(OnlineImportRow::selected).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("请至少勾选一条记录。");
        for (OnlineImportRow row : selected) {
            List<String> issues = errors(row);
            if (!issues.isEmpty()) throw new IllegalArgumentException("第 " + row.rowNumber() + " 行：" + String.join("；", issues));
        }
        List<Integer> saved = new ArrayList<>();
        List<Integer> skipped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (OnlineImportRow row : selected) {
            if (duplicate(row) || !seen.add(fingerprint(row))) { skipped.add(row.rowNumber()); continue; }
            repository.save(record(row));
            saved.add(row.rowNumber());
        }
        return new CommitResult(List.copyOf(saved), List.copyOf(skipped));
    }

    /** 根据必要业务字段生成校验说明，不信任客户端 selected 或 warnings 来判定字段是否合法。 */
    private List<String> errors(OnlineImportRow row) {
        List<String> issues = new ArrayList<>();
        if (row.rowNumber() <= 0) issues.add("行号无效，请重新解析文件");
        if (blank(row.productName()) || blank(row.normalizedName())) issues.add("请填写商品名称和归一化名称");
        if (row.amount() == null || !Double.isFinite(row.amount()) || row.amount() < 0) issues.add("请填写有效的非负实付金额");
        if (row.quantity() == null || !Double.isFinite(row.quantity()) || row.quantity() <= 0) issues.add("请填写有效的标准总含量");
        if (blank(row.unit()) || blank(row.platform()) || blank(row.owner())) issues.add("请填写单位、平台和归属人");
        if (row.owner() != null && row.owner().length() > 80) issues.add("归属人过长，请精简");
        if (normalizeDate(row.purchaseDate()).isEmpty()) issues.add("请填写有效的购买日期");
        if (!Set.of("taobao", "jd", "pdd", "douyin", "offline", "manual").contains(normalizePlatform(row.platform()))) issues.add("请选择有效平台");
        if (Arrays.stream(new String[]{row.productName(), row.normalizedName(), row.sku(), row.unit(), row.owner(), row.shopName()})
                .anyMatch(value -> value != null && value.length() > 500)) issues.add("字段过长，请精简后提交");
        return issues;
    }

    /** 日期仅按源字段解析，缺失或非法时返回空串，绝不默认为今天。 */
    private String normalizeDate(String value) {
        if (blank(value)) return "";
        try { return timeNormalizer.normalizeImportedOrderTime(value); }
        catch (IllegalArgumentException exception) { return ""; }
    }

    /** 购买件数缺失、非有限数或非正数时不默认补一，留待用户修正。 */
    private Double purchaseCount(String value) {
        try {
            double count = Double.parseDouble(trim(value).replace(",", ""));
            return Double.isFinite(count) && count > 0 ? count : null;
        } catch (NumberFormatException exception) { return null; }
    }

    /** 同归属人、同日、同金额及相同商品规格总量作保守查重，不声称已核对到原始订单号。 */
    private boolean duplicate(OnlineImportRow row) {
        String date = normalizeDate(row.purchaseDate());
        if (date.isEmpty() || row.amount() == null || row.quantity() == null) return false;
        return repository.findOrderImageMatches(ownerNormalizer.normalize(row.owner()), date.substring(0, 10), row.amount()).stream()
                .anyMatch(history -> normalizePlatform(history.platform()).equals(normalizePlatform(row.platform()))
                        && Objects.equals(trim(history.productName()), trim(row.productName()))
                        && Objects.equals(trim(history.sku()), trim(row.sku()))
                        && Objects.equals(trim(history.unit()), trim(row.unit()))
                        && history.quantity() != null && BigDecimal.valueOf(history.quantity()).compareTo(BigDecimal.valueOf(row.quantity())) == 0);
    }

    /** 生成批内业务指纹，只在本次请求使用，不持久化原始订单号或摘要。 */
    private String fingerprint(OnlineImportRow row) {
        return String.join("|", normalizeDate(row.purchaseDate()).substring(0, 10), normalizePlatform(row.platform()),
                ownerNormalizer.normalize(row.owner()), trim(row.productName()), trim(row.sku()), trim(row.unit()),
                BigDecimal.valueOf(row.quantity()).stripTrailingZeros().toPlainString(), BigDecimal.valueOf(row.amount()).stripTrailingZeros().toPlainString());
    }

    /** 历史中位数只在名称与单位同时一致时参与异常提示，不混用 kg/g 或其他单位。 */
    private boolean priceOutlier(OnlineImportRow row, List<NormalizationLibraryItem> library) {
        if (row.amount() == null || row.quantity() == null || row.quantity() <= 0) return false;
        return library.stream().filter(item -> Objects.equals(item.normalizedName(), row.normalizedName()) && Objects.equals(item.standardUnit(), row.unit()))
                .filter(item -> item.medianUnitPrice() != null && item.medianUnitPrice().signum() > 0).anyMatch(item -> {
                    BigDecimal expected = item.medianUnitPrice().multiply(BigDecimal.valueOf(row.quantity()));
                    BigDecimal amount = BigDecimal.valueOf(row.amount());
                    return amount.compareTo(expected.multiply(PRICE_DEVIATION)) > 0 || amount.multiply(PRICE_DEVIATION).compareTo(expected) < 0;
                });
    }

    /** 赠品按明确名称标记识别，不能仅因零元支付认定赠品。 */
    private boolean gift(OnlineImportRow row) { return (trim(row.productName()) + trim(row.sku())).matches("(?s).*(赠品|试用装|非卖品).*"); }
    /** 非商品权益或费用允许人工保存，但不计入可比价的历史样本。 */
    private boolean nonComparable(OnlineImportRow row) {
        return gift(row) || (trim(row.productName()) + trim(row.sku())).matches("(?s).*(定金|尾款|补差价|邮费|礼品卡|充值|虚拟权益).*");
    }
    /** 清理业务文本首尾空白，空值返回空串。 */
    private String trim(String value) { return value == null ? "" : value.trim(); }
    /** 判断必要字段是否缺失。 */
    private boolean blank(String value) { return trim(value).isEmpty(); }
    /** 规范平台，天猫统一归属淘宝。 */
    private String normalizePlatform(String value) {
        return switch (trim(value).toLowerCase(Locale.ROOT)) {
            case "tmall", "天猫", "淘宝", "taobao" -> "taobao";
            case "京东", "jd" -> "jd";
            case "拼多多", "pdd" -> "pdd";
            default -> trim(value).toLowerCase(Locale.ROOT);
        };
    }

    /** 构造白名单购买记录，源文件使用固定来源标记，备注与原文保持为空，零元和赠品排除价格基线。 */
    private PurchaseRecord record(OnlineImportRow row) {
        double unitPrice = BigDecimal.valueOf(row.amount()).divide(BigDecimal.valueOf(row.quantity()), 8, java.math.RoundingMode.HALF_UP).doubleValue();
        return new PurchaseRecord(null, null, normalizeDate(row.purchaseDate()), normalizePlatform(row.platform()), ownerNormalizer.normalize(row.owner()),
                trim(row.productName()), trim(row.normalizedName()), trim(row.sku()), "", "", row.quantity(), trim(row.unit()), row.amount(),
                row.amount(), row.amount(), null, "online_import_confirmed", unitPrice, "CNY", nonComparable(row) || row.amount() == 0 ? "exclude" : "include",
                false, "unique", "online-import", trim(row.shopName()), null, null, ClockUtils.nowText());
    }

    /**
     * @Author: jtxw
     * @Date: 2026/11/15 12:51:46
     * @Description: 在线订单只读预览结果，不包含上传文件元数据
     * @param rows 可编辑业务行
     * @param skippedRows 跳过的非成功交易或空白续行数
     */
    public record Preview(List<OnlineImportRow> rows, int skippedRows) { }

    /**
     * @Author: jtxw
     * @Date: 2026/11/15 12:51:46
     * @Description: 在线确认入库结果，以行号报告保存和重新查重跳过的记录
     * @param savedRows 已保存的行号
     * @param duplicateRows 确认时再次查重跳过的行号
     */
    public record CommitResult(List<Integer> savedRows, List<Integer> duplicateRows) { }
}
