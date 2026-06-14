package com.jtxw.familyagent.infrastructure.importer;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import com.jtxw.familyagent.domain.policy.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 17:19:22
 * @Description: 订单导入字段映射器，统一 CSV 和 Excel 的表头识别、字段转换和 owner 解析。
 */
@Component
public class OrderImportMapper {
    /**
     * 标准订单模板字段：订单编号。
     */
    private static final String STANDARD_HEADER_ORDER_ID = "order_id";
    /**
     * 标准订单模板字段：订单号。
     */
    private static final String STANDARD_HEADER_ORDER_NO = "order_no";
    /**
     * 标准订单模板字段：主订单编号。
     */
    private static final String STANDARD_HEADER_MAIN_ORDER_ID = "main_order_id";
    /**
     * 标准订单模板字段：子订单编号。
     */
    private static final String STANDARD_HEADER_SUB_ORDER_ID = "sub_order_id";
    /**
     * 标准订单模板字段：下单时间。
     */
    private static final String STANDARD_HEADER_ORDER_TIME = "order_time";
    /**
     * 标准订单模板字段：平台。
     */
    private static final String STANDARD_HEADER_PLATFORM = "platform";
    /**
     * 标准订单模板字段：订单归属人。
     */
    private static final String STANDARD_HEADER_OWNER = "owner";
    /**
     * 标准订单模板字段：商品名称。
     */
    private static final String STANDARD_HEADER_PRODUCT_NAME = "product_name";
    /**
     * 标准订单模板字段：商品规格。
     */
    private static final String STANDARD_HEADER_SKU = "sku";
    /**
     * 标准订单模板字段：品类。
     */
    private static final String STANDARD_HEADER_CATEGORY = "category";
    /**
     * 标准订单模板字段：子品类。
     */
    private static final String STANDARD_HEADER_SUB_CATEGORY = "sub_category";
    /**
     * 标准订单模板字段：数量。
     */
    private static final String STANDARD_HEADER_QUANTITY = "quantity";
    /**
     * 标准订单模板字段：单位。
     */
    private static final String STANDARD_HEADER_UNIT = "unit";
    /**
     * 标准订单模板字段：总金额。
     */
    private static final String STANDARD_HEADER_TOTAL_AMOUNT = "total_amount";
    /**
     * 标准订单模板字段：商品金额。
     */
    private static final String STANDARD_HEADER_PRODUCT_AMOUNT = "product_amount";
    /**
     * 标准订单模板字段：实付金额。
     */
    private static final String STANDARD_HEADER_PAID_AMOUNT = "paid_amount";
    /**
     * 标准订单模板字段：运费。
     */
    private static final String STANDARD_HEADER_SHIPPING_FEE = "shipping_fee";
    /**
     * 标准订单模板字段：币种。
     */
    private static final String STANDARD_HEADER_CURRENCY = "currency";

    /**
     * 中文订单模板字段：订单提交时间。
     */
    private static final String CHINESE_HEADER_ORDER_TIME = "订单提交时间";
    /**
     * 中文订单模板字段：订单编号。
     */
    private static final String CHINESE_HEADER_ORDER_ID = "订单编号";
    /**
     * 中文订单模板字段：订单号。
     */
    private static final String CHINESE_HEADER_ORDER_NO = "订单号";
    /**
     * 中文订单模板字段：主订单编号。
     */
    private static final String CHINESE_HEADER_MAIN_ORDER_ID = "主订单编号";
    /**
     * 中文订单模板字段：子订单编号。
     */
    private static final String CHINESE_HEADER_SUB_ORDER_ID = "子订单编号";
    /**
     * 中文订单模板字段：订单状态。
     */
    private static final String CHINESE_HEADER_ORDER_STATUS = "订单状态";
    /**
     * 中文订单模板字段：店铺名称。
     */
    private static final String CHINESE_HEADER_SHOP_NAME = "店铺名称";
    /**
     * 中文订单模板字段：商品名称。
     */
    private static final String CHINESE_HEADER_PRODUCT_NAME = "商品名称";
    /**
     * 中文订单模板字段：商品链接。
     */
    private static final String CHINESE_HEADER_PRODUCT_LINK = "商品链接";
    /**
     * 中文订单模板字段：型号款式。
     */
    private static final String CHINESE_HEADER_MODEL_STYLE = "型号款式";
    /**
     * 中文订单模板字段：商品数量。
     */
    private static final String CHINESE_HEADER_QUANTITY = "商品数量";
    /**
     * 中文订单模板字段：实付金额。
     */
    private static final String CHINESE_HEADER_PAID_AMOUNT = "实付金额";
    /**
     * 中文订单模板字段：商品金额。
     */
    private static final String CHINESE_HEADER_PRODUCT_AMOUNT = "商品金额";
    /**
     * 中文订单模板字段：运费。
     */
    private static final String CHINESE_HEADER_SHIPPING_FEE = "运费";

    /**
     * 平台域名片段：淘宝。
     */
    private static final String PLATFORM_DOMAIN_TAOBAO = "taobao.com";
    /**
     * 平台域名片段：天猫。
     */
    private static final String PLATFORM_DOMAIN_TMALL = "tmall.com";
    /**
     * 平台域名片段：京东。
     */
    private static final String PLATFORM_DOMAIN_JD = "jd.com";
    /**
     * 平台域名片段：拼多多。
     */
    private static final String PLATFORM_DOMAIN_YANGKEDUO = "yangkeduo.com";
    /**
     * 平台域名片段：拼多多。
     */
    private static final String PLATFORM_DOMAIN_PINDUODUO = "pinduoduo.com";
    /**
     * 平台标识：淘宝。
     */
    private static final String PLATFORM_TAOBAO = "taobao";
    /**
     * 平台标识：京东。
     */
    private static final String PLATFORM_JD = "jd";
    /**
     * 平台标识：拼多多。
     */
    private static final String PLATFORM_PDD = "pdd";
    /**
     * 平台标识：未知平台。
     */
    private static final String PLATFORM_UNKNOWN = "unknown";

    /**
     * 交易状态：交易成功。
     */
    private static final String TRADE_STATUS_SUCCESS = "交易成功";

    /**
     * 默认币种。
     */
    private static final String DEFAULT_CURRENCY = "CNY";
    /**
     * 默认数量单位（中文订单导出模板）。
     */
    private static final String DEFAULT_UNIT_PIECE = "件";
    /**
     * 规格复核原因码：多包装或次卡乘数需要人工确认。
     */
    private static final String SPEC_REVIEW_REASON_MULTIPACK_TIMES = "SPEC_MULTIPACK_TIMES";
    /**
     * 规格复核原因码：普通包装数量无法可靠折算为标准单位。
     */
    private static final String SPEC_REVIEW_REASON_QUANTITY_UNIT_PARSE = "QUANTITY_UNIT_PARSE_REVIEW";

    private final ProductSpecParser productSpecParser;
    private final ProductRuleMatcher productRuleMatcher;
    private final OwnerNormalizer ownerNormalizer;

    /**
     * 创建订单导入字段映射器，用于 Spring 注入完整依赖。
     *
     * @param productSpecParser  规格解析器
     * @param productRuleMatcher 商品规则匹配器
     * @param ownerNormalizer    owner 归一化器
     */
    @Autowired
    public OrderImportMapper(ProductSpecParser productSpecParser,
                             ProductRuleMatcher productRuleMatcher,
                             OwnerNormalizer ownerNormalizer) {
        this.productSpecParser = productSpecParser;
        this.productRuleMatcher = productRuleMatcher;
        this.ownerNormalizer = ownerNormalizer;
    }

    /**
     * 根据表头集合判断订单导入模板类型。
     *
     * <p>支持项目标准模板和已支持的中文订单导出模板。不支持时抛出异常。</p>
     *
     * @param headers 表头字段集合
     * @return 导入模板类型
     * @throws IllegalArgumentException 不支持的表头
     */
    public ImportSchema detectSchema(Set<String> headers) {
        if (hasHeaders(headers, STANDARD_HEADER_ORDER_TIME, STANDARD_HEADER_PRODUCT_NAME, STANDARD_HEADER_QUANTITY, STANDARD_HEADER_TOTAL_AMOUNT)) {
            return ImportSchema.STANDARD;
        }
        if (hasHeaders(headers, CHINESE_HEADER_ORDER_TIME, CHINESE_HEADER_ORDER_STATUS, CHINESE_HEADER_SHOP_NAME, CHINESE_HEADER_PRODUCT_NAME, CHINESE_HEADER_PRODUCT_LINK, CHINESE_HEADER_MODEL_STYLE, CHINESE_HEADER_QUANTITY, CHINESE_HEADER_PAID_AMOUNT)) {
            return ImportSchema.CHINESE_ORDER_EXPORT;
        }
        throw new IllegalArgumentException("不支持的订单文件表头，请使用项目标准模板或已支持的中文订单导出模板。当前表头：" + headers);
    }

    /**
     * 将一行订单字段转换为原始订单记录。
     *
     * @param schema        导入模板类型
     * @param values        当前行字段值
     * @param file          来源文件路径
     * @param ownerOverride 导入时指定的订单归属人
     * @return 原始订单记录；返回 null 表示该行应跳过
     */
    public RawPurchaseRecord map(ImportSchema schema, Map<String, String> values, Path file, String ownerOverride) {
        return switch (schema) {
            case STANDARD -> readStandardRecord(values, file, ownerOverride);
            case CHINESE_ORDER_EXPORT -> readChineseOrderExportRecord(values, file, ownerOverride);
        };
    }

    /**
     * 判断当前表头集合是否包含指定模板所需字段。
     *
     * <p>该方法只做包含判断，不修改表头集合。</p>
     *
     * @param headers         实际表头集合
     * @param requiredHeaders 模板所需字段
     * @return {@code true} 表示全部包含，{@code false} 表示缺少字段
     */
    private boolean hasHeaders(Set<String> headers, String... requiredHeaders) {
        for (String header : requiredHeaders) {
            if (!headers.contains(header)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将项目标准模板的一行字段转换为原始订单记录。
     *
     * <p>会解析标准字段、owner、金额、币种，并委托 {@link #buildRecord} 统一构建。不直接写数据库。</p>
     *
     * @param values        当前行字段值
     * @param file          来源文件路径
     * @param ownerOverride 导入时指定的订单归属人
     * @return 原始订单记录
     */
    private RawPurchaseRecord readStandardRecord(Map<String, String> values, Path file, String ownerOverride) {
        String orderTime = get(values, STANDARD_HEADER_ORDER_TIME);
        String platform = get(values, STANDARD_HEADER_PLATFORM);
        String owner = resolveOwner(get(values, STANDARD_HEADER_OWNER), file, ownerOverride);
        Double totalAmount = parseDouble(get(values, STANDARD_HEADER_TOTAL_AMOUNT));
        Double paidAmount = parseDouble(get(values, STANDARD_HEADER_PAID_AMOUNT));
        return buildRecord(
                orderTime,
                platform,
                owner,
                resolveOrderGroupKey(values, file, platform, owner, orderTime, paidAmount == null ? totalAmount : paidAmount),
                get(values, STANDARD_HEADER_PRODUCT_NAME),
                get(values, STANDARD_HEADER_SKU),
                get(values, STANDARD_HEADER_CATEGORY),
                get(values, STANDARD_HEADER_SUB_CATEGORY),
                parseDouble(get(values, STANDARD_HEADER_QUANTITY)),
                get(values, STANDARD_HEADER_UNIT),
                totalAmount,
                parseDouble(get(values, STANDARD_HEADER_PRODUCT_AMOUNT)),
                paidAmount,
                parseDouble(get(values, STANDARD_HEADER_SHIPPING_FEE)),
                getOrDefault(values, STANDARD_HEADER_CURRENCY, DEFAULT_CURRENCY),
                false
        );
    }

    /**
     * 将中文订单导出模板的一行字段转换为原始订单记录。
     *
     * <p>非交易成功订单会返回 null 表示跳过。平台根据商品链接推断。
     * 默认单位为件，默认币种为 CNY。不直接写数据库。</p>
     *
     * @param values        当前行字段值
     * @param file          来源文件路径
     * @param ownerOverride 导入时指定的订单归属人
     * @return 原始订单记录；返回 null 表示该行应跳过
     */
    private RawPurchaseRecord readChineseOrderExportRecord(Map<String, String> values, Path file, String ownerOverride) {
        // 交易关闭不代表有效购买，导入阶段直接跳过，避免进入价格统计和价格报告
        if (!TRADE_STATUS_SUCCESS.equals(get(values, CHINESE_HEADER_ORDER_STATUS))) {
            return null;
        }
        String orderTime = get(values, CHINESE_HEADER_ORDER_TIME);
        String platform = inferPlatform(get(values, CHINESE_HEADER_PRODUCT_LINK));
        String owner = resolveOwner("", file, ownerOverride);
        Double purchaseQuantity = parseDouble(get(values, CHINESE_HEADER_QUANTITY));
        Double lineProductAmount = multiplyAmount(parseDouble(get(values, CHINESE_HEADER_PRODUCT_AMOUNT)), purchaseQuantity);
        Double paidAmount = parseDouble(get(values, CHINESE_HEADER_PAID_AMOUNT));
        return buildRecord(
                orderTime,
                platform,
                owner,
                resolveOrderGroupKey(values, file, platform, owner, orderTime, paidAmount),
                get(values, CHINESE_HEADER_PRODUCT_NAME),
                get(values, CHINESE_HEADER_MODEL_STYLE),
                "",
                "",
                purchaseQuantity,
                DEFAULT_UNIT_PIECE,
                paidAmount,
                lineProductAmount,
                paidAmount,
                parseDouble(get(values, CHINESE_HEADER_SHIPPING_FEE)),
                DEFAULT_CURRENCY,
                true
        );
    }

    /**
     * 统一构建原始订单记录。
     *
     * <p>会执行商品规则匹配和 SKU 规格解析。当规格解析结果需要人工确认时，
     * 会写入规格复核原因码和中文提示。不直接写数据库。</p>
     *
     * @param orderTime     下单时间
     * @param platform      平台标识
     * @param owner         订单归属人
     * @param orderGroupKey 订单分组键
     * @param productName   商品名称
     * @param sku           商品规格
     * @param category      品类
     * @param subCategory   子品类
     * @param quantity      数量
     * @param unit          单位
     * @param totalAmount   总金额
     * @param productAmount 商品金额
     * @param paidAmount    实付金额
     * @param shippingFee   运费
     * @param currency      币种
     * @param paidAmountIncludesShipping 实付金额是否已经包含运费
     * @return 原始订单记录
     */
    private RawPurchaseRecord buildRecord(String orderTime,
                                          String platform,
                                          String owner,
                                          String orderGroupKey,
                                          String productName,
                                          String sku,
                                          String category,
                                          String subCategory,
                                          Double quantity,
                                          String unit,
                                          Double totalAmount,
                                          Double productAmount,
                                          Double paidAmount,
                                          Double shippingFee,
                                          String currency,
                                          boolean paidAmountIncludesShipping) {
        ProductRuleMatchResult ruleMatch = productRuleMatcher.match(productName);
        ProductSpecParseResult spec = productSpecParser.parse(sku, productName, ruleMatch);
        Double resolvedQuantity = spec.parsed() ? spec.quantity() : quantity;
        String resolvedUnit = spec.parsed() ? spec.unit() : unit;
        String specReviewReasonCode = resolveSpecReviewReasonCode(spec);
        return new RawPurchaseRecord(
                orderTime, platform, owner, orderGroupKey, productName, sku, category, subCategory, resolvedQuantity, resolvedUnit,
                totalAmount, productAmount == null ? totalAmount : productAmount, paidAmount == null ? totalAmount : paidAmount,
                shippingFee, currency, spec.reviewRequired(), specReviewReasonCode,
                spec.reviewRequired() ? specReviewMessage(specReviewReasonCode) : null,
                paidAmountIncludesShipping, null, false, null, null
        );
    }

    /**
     * 根据规格解析结果选择复核原因。
     *
     * <p>已解析出数量但需要复核，表示命中次卡、月卡或分次发货等多次权益；
     * 未解析出数量但需要复核，表示普通包装规格缺少可靠标准数量。</p>
     *
     * @param spec 规格解析结果，不允许为空
     * @return 复核原因编码；不需要复核时返回 null
     */
    private String resolveSpecReviewReasonCode(ProductSpecParseResult spec) {
        if (!spec.reviewRequired()) {
            return null;
        }
        return spec.parsed() ? SPEC_REVIEW_REASON_MULTIPACK_TIMES : SPEC_REVIEW_REASON_QUANTITY_UNIT_PARSE;
    }

    /**
     * 生成规格复核提示文案。
     *
     * @param reasonCode 规格复核原因编码
     * @return 写入 review_items 的提示文案
     */
    private String specReviewMessage(String reasonCode) {
        if (SPEC_REVIEW_REASON_MULTIPACK_TIMES.equals(reasonCode)) {
            return "规格包含次卡或分次发货语义，已按总数量折算，请人工确认是否为多次发货权益。";
        }
        return "规格只有包装数量，缺少每包标准数量，需人工确认后再纳入价格基准。";
    }

    /**
     * 将中文淘宝导出的单件商品金额乘以购买件数，得到当前商品行金额。
     *
     * @param unitProductAmount 单件商品金额，单位为元
     * @param purchaseQuantity  淘宝订单购买件数
     * @return 当前商品行金额，单位为元
     */
    private Double multiplyAmount(Double unitProductAmount, Double purchaseQuantity) {
        if (unitProductAmount == null) {
            return null;
        }
        BigDecimal amount = BigDecimal.valueOf(unitProductAmount);
        BigDecimal quantity = purchaseQuantity == null ? BigDecimal.ONE : BigDecimal.valueOf(purchaseQuantity);
        return amount.multiply(quantity).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 解析订单分组键，优先使用原始文件中的订单编号类字段。
     *
     * <p>如果文件没有订单编号，则退化为弱分组 key：platform + owner + sourceFile + orderTime + orderPaidAmount。
     * 该兜底 key 可能把同一时间同一金额的不同订单误归为一组，后续应优先接入稳定订单编号。</p>
     *
     * @param values          当前行字段值
     * @param file            来源文件路径
     * @param platform        平台标识
     * @param owner           订单归属人
     * @param orderTime       下单时间
     * @param orderPaidAmount 订单级实付金额
     * @return 可用于同一订单内多商品行分组的 key
     */
    private String resolveOrderGroupKey(Map<String, String> values,
                                        Path file,
                                        String platform,
                                        String owner,
                                        String orderTime,
                                        Double orderPaidAmount) {
        String explicitOrderKey = firstNonBlank(
                get(values, CHINESE_HEADER_MAIN_ORDER_ID),
                get(values, STANDARD_HEADER_MAIN_ORDER_ID),
                get(values, CHINESE_HEADER_ORDER_ID),
                get(values, CHINESE_HEADER_ORDER_NO),
                get(values, STANDARD_HEADER_ORDER_ID),
                get(values, STANDARD_HEADER_ORDER_NO),
                get(values, CHINESE_HEADER_SUB_ORDER_ID),
                get(values, STANDARD_HEADER_SUB_ORDER_ID)
        );
        if (explicitOrderKey != null) {
            return explicitOrderKey;
        }
        return String.join("|",
                safeKeyPart(platform),
                safeKeyPart(owner),
                safeKeyPart(file.toString()),
                safeKeyPart(orderTime),
                orderPaidAmount == null ? "" : orderPaidAmount.toString());
    }

    /**
     * 返回第一个非空文本。
     *
     * @param values 候选文本列表
     * @return 第一个非空文本；全部为空时返回 null
     */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 将分组 key 的空片段归一为空字符串。
     *
     * @param value 原始片段
     * @return 非空片段
     */
    private String safeKeyPart(String value) {
        return value == null ? "" : value;
    }

    /**
     * 从字段值 Map 中读取指定字段，缺失时返回空字符串。
     *
     * @param values 字段值 Map
     * @param name   字段名
     * @return 字段值或空字符串
     */
    private String get(Map<String, String> values, String name) {
        return values.getOrDefault(name, "");
    }

    /**
     * 从字段值 Map 中读取指定字段，为空白时返回默认值。
     *
     * @param values       字段值 Map
     * @param name         字段名
     * @param defaultValue 默认值
     * @return 字段值或默认值
     */
    private String getOrDefault(Map<String, String> values, String name, String defaultValue) {
        String value = get(values, name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /**
     * 解析订单归属人。
     *
     * <p>优先级：接口传入的 ownerOverride &gt; 订单记录字段中的 owner &gt; 文件名后缀中的 owner。
     * 最终 owner 会经过 {@link OwnerNormalizer} 归一化。无法确定时抛出异常。</p>
     *
     * @param recordOwner   订单记录字段中的 owner
     * @param file          来源文件路径
     * @param ownerOverride 导入时指定的订单归属人
     * @return 归一化后的 owner
     * @throws IllegalArgumentException 无法确定订单归属人
     */
    private String resolveOwner(String recordOwner, Path file, String ownerOverride) {
        if (ownerOverride != null && !ownerOverride.isBlank()) {
            return normalizeOwner(ownerOverride);
        }
        if (recordOwner != null && !recordOwner.isBlank()) {
            return normalizeOwner(recordOwner);
        }
        String filenameOwner = extractOwnerFromFilename(file);
        if (filenameOwner != null && !filenameOwner.isBlank()) {
            return normalizeOwner(filenameOwner);
        }
        throw new IllegalArgumentException("无法确定订单归属 owner，请传入 owner 参数，或使用 文件名-owner.csv / 文件名-owner.xlsx 格式。");
    }

    /**
     * 委托 {@link OwnerNormalizer} 做归属人归一化。
     *
     * @param owner 原始归属人
     * @return 归一化后的归属人
     */
    private String normalizeOwner(String owner) {
        return ownerNormalizer.normalize(owner);
    }

    /**
     * 从文件名中提取最后一个短横线后的 owner。
     *
     * <p>示例：{@code sample_orders-jtxw.csv} 提取 {@code jtxw}。
     * 如果文件名不符合格式则返回 null。</p>
     *
     * @param file 文件路径
     * @return 提取的 owner，或 null
     */
    private String extractOwnerFromFilename(Path file) {
        String filename = file.getFileName().toString();
        int dotIndex = filename.lastIndexOf('.');
        String basename = dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
        int dashIndex = basename.lastIndexOf('-');
        if (dashIndex < 0 || dashIndex == basename.length() - 1) {
            return null;
        }
        return basename.substring(dashIndex + 1).trim();
    }

    /**
     * 将金额或数量文本解析为 Double。
     *
     * <p>会去除人民币符号、元和千分位逗号。空文本返回 null。
     * 非法数字保持原有异常行为。</p>
     *
     * @param text 金额或数量文本
     * @return 解析后的数值，或 null
     */
    private Double parseDouble(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim()
                .replace("￥", "")
                .replace("¥", "")
                .replace("元", "")
                .replace(",", "");
        if (normalized.isBlank()) {
            return null;
        }
        return Double.parseDouble(normalized);
    }

    /**
     * 根据商品链接推断平台。
     *
     * <p>taobao.com / tmall.com 返回 taobao；jd.com 返回 jd；
     * yangkeduo.com / pinduoduo.com 返回 pdd；无法识别返回 unknown。</p>
     *
     * @param link 商品链接
     * @return 平台标识
     */
    private String inferPlatform(String link) {
        String value = link == null ? "" : link.toLowerCase(Locale.ROOT);
        if (value.contains(PLATFORM_DOMAIN_TAOBAO) || value.contains(PLATFORM_DOMAIN_TMALL)) {
            return PLATFORM_TAOBAO;
        }
        if (value.contains(PLATFORM_DOMAIN_JD)) {
            return PLATFORM_JD;
        }
        if (value.contains(PLATFORM_DOMAIN_YANGKEDUO) || value.contains(PLATFORM_DOMAIN_PINDUODUO)) {
            return PLATFORM_PDD;
        }
        return PLATFORM_UNKNOWN;
    }

    /**
     * 订单导入模板类型。
     */
    public enum ImportSchema {
        /**
         * 项目标准模板，使用 order_time、product_name 等英文字段
         */
        STANDARD,
        /**
         * 中文电商订单导出模板，使用订单提交时间、商品名称、商品金额、实付金额等中文字段
         */
        CHINESE_ORDER_EXPORT
    }
}
