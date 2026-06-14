package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.NormalizationLibraryOperationCommand;
import com.jtxw.familyagent.domain.model.NormalizationApplyRuleRecordSnapshot;
import com.jtxw.familyagent.domain.model.NormalizationApplyRuleToRecordsItem;
import com.jtxw.familyagent.domain.model.NormalizationApplyRuleToRecordsResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.ProductRuleMatchResult;
import com.jtxw.familyagent.domain.policy.ProductRuleMatcher;
import com.jtxw.familyagent.domain.policy.QuantityUnitParseResult;
import com.jtxw.familyagent.domain.policy.QuantityUnitParser;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationRuleRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 06:31:45
 * @Description: 归一化规则历史记录回填应用服务，负责受控扫描候选购买记录、预览回填结果并在显式 apply 时更新统计字段
 */
@Service
public class NormalizationRuleApplyService {
    /**
     * 规则回填 action 名称。
     */
    private static final String ACTION_APPLY_RULE_TO_RECORDS = "apply_rule_to_records";
    /**
     * 单条状态：dry-run 下可自动回填。
     */
    private static final String STATUS_APPLICABLE = "applicable";
    /**
     * 单条状态：实际回填成功。
     */
    private static final String STATUS_UPDATED = "updated";
    /**
     * 单条状态：需要人工复核。
     */
    private static final String STATUS_REVIEW_REQUIRED = "review_required";
    /**
     * 单条状态：因安全原因跳过。
     */
    private static final String STATUS_SKIPPED = "skipped";
    /**
     * 自动回填后的统计决策。
     */
    private static final String DECISION_INCLUDE = "include";
    /**
     * 规则回填成功后写入 review_items.review_decision 的处理结果。
     */
    private static final String REVIEW_DECISION_RULE_APPLIED = "rule_applied";
    /**
     * 默认最大候选记录数，单位为条。
     */
    private static final int DEFAULT_LIMIT = 100;
    /**
     * 最大候选记录数上限，单位为条。
     */
    private static final int MAX_LIMIT = 500;
    /**
     * 片数解析表达式，仅识别“数字 + 片”或“数字 + 片装”，避免误读度数、直径、含水量、厚度和色号。
     */
    private static final Pattern PIECE_COUNT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*片\\s*装?");
    /**
     * 规格数量无法可靠解析时使用的复核原因码。
     */
    private static final String REVIEW_REASON_QUANTITY_UNIT_PARSE = "QUANTITY_UNIT_PARSE_REVIEW";
    /**
     * 金额异常时使用的商品归一化复核原因码。
     */
    private static final String REVIEW_REASON_PRODUCT_NAME_NORMALIZATION = "PRODUCT_NAME_NORMALIZATION_REVIEW";

    /**
     * 数据库初始化组件，确保规则表和购买记录表存在。
     */
    private final DatabaseInitializer databaseInitializer;
    /**
     * 归一化规则仓储，负责读取规则主表和启用关键词。
     */
    private final NormalizationRuleRepository normalizationRuleRepository;
    /**
     * 购买记录仓储，负责候选查询和受控回填。
     */
    private final PurchaseRecordRepository purchaseRecordRepository;
    /**
     * 复核项仓储，负责判断待处理复核项和创建必要复核项。
     */
    private final ReviewItemRepository reviewItemRepository;
    /**
     * 规则匹配器，复用运行期规则优先级、include 和 exclude 关键词逻辑。
     */
    private final ProductRuleMatcher productRuleMatcher;
    /**
     * 规格数量解析器，复用导入链路的数量、单位和单价计算规则。
     */
    private final QuantityUnitParser quantityUnitParser;

    /**
     * 创建归一化规则历史记录回填应用服务。
     *
     * @param databaseInitializer        数据库初始化组件
     * @param normalizationRuleRepository 归一化规则仓储
     * @param purchaseRecordRepository   购买记录仓储
     * @param reviewItemRepository       复核项仓储
     * @param productRuleMatcher         规则匹配器
     * @param quantityUnitParser         规格数量解析器
     */
    public NormalizationRuleApplyService(DatabaseInitializer databaseInitializer,
                                         NormalizationRuleRepository normalizationRuleRepository,
                                         PurchaseRecordRepository purchaseRecordRepository,
                                         ReviewItemRepository reviewItemRepository,
                                         ProductRuleMatcher productRuleMatcher,
                                         QuantityUnitParser quantityUnitParser) {
        this.databaseInitializer = databaseInitializer;
        this.normalizationRuleRepository = normalizationRuleRepository;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.productRuleMatcher = productRuleMatcher;
        this.quantityUnitParser = quantityUnitParser;
    }

    /**
     * 将指定归一化规则应用到历史购买记录。
     *
     * <p>默认 dry-run，只返回预览不写库；只有请求显式 dryRun=false 时才更新 purchase_records，
     * 且只更新归一化统计字段，不能修改订单原始溯源字段。</p>
     *
     * @param command 规则库统一操作命令，action 必须为 apply_rule_to_records
     * @return 规则回填结果
     */
    @Transactional
    public NormalizationApplyRuleToRecordsResult apply(NormalizationLibraryOperationCommand command) {
        ApplyRequest request = normalizeRequest(command);
        databaseInitializer.initialize();
        NormalizationRuleRepository.NormalizationRuleRow ruleRow = normalizationRuleRepository
                .findRuleByCode(request.ruleCode())
                .orElseThrow(() -> new IllegalArgumentException("归一化规则不存在：" + request.ruleCode()));
        if (!ruleRow.enabled()) {
            throw new IllegalArgumentException("归一化规则未启用：" + request.ruleCode());
        }
        ProductRule targetRule = enabledRule(request.ruleCode());
        List<PurchaseRecord> candidates = purchaseRecordRepository.listRuleApplyCandidates(
                request.batchId(), request.owner(), request.onlyLegacyFallback(), request.onlyExcluded(),
                request.limit());
        List<String> taskWarnings = new ArrayList<>();
        if (request.dryRun()) {
            taskWarnings.add("dryRun=true，仅返回预览，不写入 purchase_records");
        }
        ApplyCounters counters = new ApplyCounters();
        List<NormalizationApplyRuleToRecordsItem> items = new ArrayList<>();
        for (PurchaseRecord record : candidates) {
            NormalizationApplyRuleToRecordsItem item = evaluateRecord(record, targetRule, request.dryRun());
            if (item == null) {
                continue;
            }
            counters.accept(item);
            items.add(item);
        }
        return new NormalizationApplyRuleToRecordsResult(ACTION_APPLY_RULE_TO_RECORDS, true,
                request.ruleCode(), targetRule.normalizedName(), request.dryRun(), candidates.size(), items.size(),
                counters.applicableCount(), counters.reviewRequiredCount(), counters.updatedCount(),
                counters.skippedCount(), taskWarnings, items);
    }

    private NormalizationApplyRuleToRecordsItem evaluateRecord(PurchaseRecord record,
                                                               ProductRule targetRule,
                                                               boolean dryRun) {
        String text = combinedText(record.productName(), record.sku());
        NormalizationApplyRuleRecordSnapshot before = snapshot(record);
        if (!matchesIncludeKeyword(targetRule, text)) {
            return null;
        }
        if (matchesExcludeKeyword(targetRule, text)) {
            return new NormalizationApplyRuleToRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_SKIPPED, before, null,
                    List.of("命中规则排除关键词：" + firstMatchedKeyword(targetRule.excludeKeywords(), text)));
        }
        ProductRuleMatchResult matchResult = productRuleMatcher.match(text);
        if (!matchResult.matched() || !targetRule.id().equals(matchResult.ruleId())) {
            return null;
        }
        if (isDuplicateOrNotUnique(record)) {
            return new NormalizationApplyRuleToRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_SKIPPED, before, null, List.of("记录为重复订单或 dedupe_status 非 unique，跳过自动回填"));
        }
        if (reviewItemRepository.existsBlockingReviewForRuleApply(record.id())) {
            return new NormalizationApplyRuleToRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_SKIPPED, before, null, List.of("存在阻断型复核项，跳过自动回填"));
        }
        TargetUnitQuantityParseResult parseResult = parseTargetUnitQuantity(record, targetRule);
        List<String> warnings = validateParsedResult(record, targetRule, parseResult);
        if (!warnings.isEmpty()) {
            createReviewIfNeeded(record, dryRun, warnings);
            return new NormalizationApplyRuleToRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_REVIEW_REQUIRED, before, null, warnings);
        }
        NormalizationApplyRuleRecordSnapshot after = new NormalizationApplyRuleRecordSnapshot(
                targetRule.normalizedName(), decimal(parseResult.quantity()), parseResult.unit(),
                decimal(parseResult.unitPrice()), DECISION_INCLUDE, targetRule.id());
        List<String> itemWarnings = parseResult.source() == null
                ? List.of()
                : List.of("数量来源：" + parseResult.source());
        if (dryRun) {
            return new NormalizationApplyRuleToRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_APPLICABLE, before, after, itemWarnings);
        }
        int updatedCount = purchaseRecordRepository.updateNormalizedRecordAfterRuleApply(record.id(),
                targetRule.normalizedName(), parseResult.quantity(), parseResult.unit(), parseResult.unitPrice(),
                DECISION_INCLUDE, targetRule.id());
        if (updatedCount == 0) {
            return new NormalizationApplyRuleToRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_SKIPPED, before, null, List.of("购买记录不存在或已被并发修改，跳过自动回填"));
        }
        reviewItemRepository.resolvePendingRuleApplyReviews(record.id(), REVIEW_DECISION_RULE_APPLIED,
                "已通过规则 " + targetRule.id() + " 回填历史记录，normalizedName=" + targetRule.normalizedName()
                        + "，unit=" + parseResult.unit());
        return new NormalizationApplyRuleToRecordsItem(record.id(), record.productName(), record.sku(),
                STATUS_UPDATED, before, after, itemWarnings);
    }

    private List<String> validateParsedResult(PurchaseRecord record,
                                              ProductRule targetRule,
                                              TargetUnitQuantityParseResult parseResult) {
        List<String> warnings = new ArrayList<>();
        if (record.totalAmount() == null || record.totalAmount() <= 0D) {
            warnings.add("统计金额为空或异常，不能自动纳入价格基准");
        }
        if (!parseResult.confident() || parseResult.quantity() == null || parseResult.quantity() <= 0D) {
            warnings.add("无法从商品标题或 SKU 可靠解析标准数量：" + targetRule.standardUnit());
        }
        warnings.addAll(parseResult.warnings());
        if (!targetRule.standardUnit().equals(parseResult.unit())) {
            warnings.add("解析单位与规则标准单位不一致：" + parseResult.unit() + " / " + targetRule.standardUnit());
        }
        if (parseResult.unitPrice() == null || parseResult.unitPrice() <= 0D) {
            warnings.add("无法重算有效 unit_price");
        }
        return warnings;
    }

    private TargetUnitQuantityParseResult parseTargetUnitQuantity(PurchaseRecord record, ProductRule targetRule) {
        String targetUnit = targetRule.standardUnit();
        if ("片".equals(targetUnit)) {
            TargetTextQuantityParseResult skuResult = parsePieceQuantityFromText(record.sku(), "sku");
            if (skuResult.conflicted()) {
                return TargetUnitQuantityParseResult.reviewRequired(targetUnit, skuResult.warnings());
            }
            if (skuResult.quantity() != null) {
                return targetQuantityResult(record, skuResult.quantity(), targetUnit, skuResult.source());
            }
            TargetTextQuantityParseResult productNameResult = parsePieceQuantityFromText(record.productName(),
                    "productName");
            if (productNameResult.conflicted()) {
                return TargetUnitQuantityParseResult.reviewRequired(targetUnit, productNameResult.warnings());
            }
            if (productNameResult.quantity() != null) {
                return targetQuantityResult(record, productNameResult.quantity(), targetUnit,
                        productNameResult.source());
            }
            if (targetUnit.equals(record.unit()) && record.quantity() != null && record.quantity() > 0D) {
                return targetQuantityResult(record, record.quantity(), targetUnit, "original_record");
            }
            return TargetUnitQuantityParseResult.reviewRequired(targetUnit,
                    List.of("SKU 和商品标题均未找到明确片数"));
        }
        QuantityUnitParseResult fallbackResult = quantityUnitParser.parse(targetRule.normalizedName(),
                targetUnit, record.productName(), record.sku(), record.totalAmount(), record.quantity(), record.unit());
        if (fallbackResult.needReview()) {
            return TargetUnitQuantityParseResult.reviewRequired(targetUnit, List.of(fallbackResult.parseEvidence()));
        }
        return new TargetUnitQuantityParseResult(fallbackResult.quantity(), fallbackResult.unit(),
                fallbackResult.unitPrice(), "existing_parser", true, List.of());
    }

    private TargetUnitQuantityParseResult targetQuantityResult(PurchaseRecord record,
                                                               Double quantity,
                                                               String unit,
                                                               String source) {
        Double unitPrice = record.totalAmount() == null || quantity == null || quantity <= 0D
                ? null
                : record.totalAmount() / quantity;
        return new TargetUnitQuantityParseResult(quantity, unit, unitPrice, source, true, List.of());
    }

    private TargetTextQuantityParseResult parsePieceQuantityFromText(String text, String source) {
        if (text == null || text.isBlank()) {
            return TargetTextQuantityParseResult.notFound(source);
        }
        Matcher matcher = PIECE_COUNT_PATTERN.matcher(text);
        Set<Double> quantities = new LinkedHashSet<>();
        while (matcher.find()) {
            quantities.add(Double.parseDouble(matcher.group(1)));
        }
        if (quantities.isEmpty()) {
            return TargetTextQuantityParseResult.notFound(source);
        }
        if (quantities.size() > 1) {
            return TargetTextQuantityParseResult.conflicted(source,
                    "SKU 内部出现多个不同片数，需人工确认：" + quantities);
        }
        return new TargetTextQuantityParseResult(quantities.iterator().next(), source, false, List.of());
    }

    private void createReviewIfNeeded(PurchaseRecord record, boolean dryRun, List<String> warnings) {
        if (dryRun) {
            return;
        }
        String reasonCode = warnings.stream().anyMatch(warning -> warning.contains("标准数量"))
                ? REVIEW_REASON_QUANTITY_UNIT_PARSE
                : REVIEW_REASON_PRODUCT_NAME_NORMALIZATION;
        if (reviewItemRepository.existsPendingByRecordIdAndReasonCode(record.id(), reasonCode)) {
            return;
        }
        reviewItemRepository.create(record.id(), reasonCode, "规则回填需要人工复核：" + String.join("；", warnings));
    }

    private ProductRule enabledRule(String ruleCode) {
        return normalizationRuleRepository.listEnabledProductRules().stream()
                .filter(rule -> ruleCode.equals(rule.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("归一化规则未启用：" + ruleCode));
    }

    private ApplyRequest normalizeRequest(NormalizationLibraryOperationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("归一化规则回填请求不能为空");
        }
        String ruleCode = requiredText(command.ruleCode(), "ruleCode 不能为空");
        String owner = blankToNull(command.owner());
        int limit = command.limit() == null ? DEFAULT_LIMIT : command.limit();
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        limit = Math.min(limit, MAX_LIMIT);
        return new ApplyRequest(ruleCode, command.batchId(), owner,
                command.onlyLegacyFallback() == null || command.onlyLegacyFallback(),
                command.onlyExcluded() == null || command.onlyExcluded(),
                command.dryRun() == null || command.dryRun(),
                limit);
    }

    private NormalizationApplyRuleRecordSnapshot snapshot(PurchaseRecord record) {
        return new NormalizationApplyRuleRecordSnapshot(record.normalizedName(), decimal(record.quantity()),
                record.unit(), decimal(record.unitPrice()), record.decision(), record.normalizationRule());
    }

    private boolean matchesExcludeKeyword(ProductRule rule, String text) {
        return firstMatchedKeyword(rule.excludeKeywords(), text) != null;
    }

    private boolean matchesIncludeKeyword(ProductRule rule, String text) {
        return firstMatchedKeyword(rule.includeKeywords(), text) != null;
    }

    private boolean isDuplicateOrNotUnique(PurchaseRecord record) {
        return record.duplicate() || !"unique".equalsIgnoreCase(safeText(record.dedupeStatus()).trim());
    }

    private String firstMatchedKeyword(List<String> keywords, String text) {
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.trim())) {
                return keyword.trim();
            }
        }
        return null;
    }

    private String combinedText(String productName, String sku) {
        return safeText(productName) + " " + safeText(sku);
    }

    private BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private String requiredText(String value, String message) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * 归一化后的规则回填请求参数。
     *
     * @param ruleCode           规则编码
     * @param batchId            导入批次筛选；为空时不按批次筛选
     * @param owner              归属人筛选；为空时不按归属人筛选；batchId 和 owner 都为空时按全家庭历史样本扫描
     * @param onlyLegacyFallback 是否只处理未命中明确规则的记录
     * @param onlyExcluded       是否只处理当前已排除记录
     * @param dryRun             是否只预览不写库
     * @param limit              最大候选数量
     */
    /**
     * 目标单位数量解析结果，作为规则回填内部判断对象，不暴露到接口协议。
     *
     * @param quantity  解析出的目标单位数量，无法解析时为空
     * @param unit      目标单位，通常来自规则 standardUnit
     * @param unitPrice 按 totalAmount / quantity 重算出的单价
     * @param source    数量来源，取值为 sku、productName、original_record 或 existing_parser
     * @param confident 是否可自动用于回填
     * @param warnings  解析风险说明，无法自动回填时返回给调用方
     */
    private record TargetUnitQuantityParseResult(Double quantity,
                                                 String unit,
                                                 Double unitPrice,
                                                 String source,
                                                 boolean confident,
                                                 List<String> warnings) {
        private TargetUnitQuantityParseResult {
            warnings = warnings == null ? List.of() : warnings.stream().toList();
        }

        private static TargetUnitQuantityParseResult reviewRequired(String unit, List<String> warnings) {
            return new TargetUnitQuantityParseResult(null, unit, null, null, false, warnings);
        }
    }

    /**
     * 单段文本中的目标单位数量解析结果。
     *
     * @param quantity   文本中的唯一目标数量
     * @param source     文本来源，sku 或 productName
     * @param conflicted 是否存在多个不同目标数量
     * @param warnings   冲突说明
     */
    private record TargetTextQuantityParseResult(Double quantity,
                                                 String source,
                                                 boolean conflicted,
                                                 List<String> warnings) {
        private TargetTextQuantityParseResult {
            warnings = warnings == null ? List.of() : warnings.stream().toList();
        }

        private static TargetTextQuantityParseResult notFound(String source) {
            return new TargetTextQuantityParseResult(null, source, false, List.of());
        }

        private static TargetTextQuantityParseResult conflicted(String source, String warning) {
            return new TargetTextQuantityParseResult(null, source, true, List.of(warning));
        }
    }

    private record ApplyRequest(String ruleCode,
                                Long batchId,
                                String owner,
                                boolean onlyLegacyFallback,
                                boolean onlyExcluded,
                                boolean dryRun,
                                int limit) {
    }

    /**
     * 规则回填统计计数器。
     */
    private static class ApplyCounters {
        /**
         * 可自动回填记录数量。
         */
        private int applicableCount;
        /**
         * 需要复核记录数量。
         */
        private int reviewRequiredCount;
        /**
         * 实际更新记录数量。
         */
        private int updatedCount;
        /**
         * 跳过记录数量。
         */
        private int skippedCount;

        private void accept(NormalizationApplyRuleToRecordsItem item) {
            String status = item.status().toLowerCase(Locale.ROOT);
            switch (status) {
                case STATUS_APPLICABLE -> applicableCount++;
                case STATUS_UPDATED -> updatedCount++;
                case STATUS_REVIEW_REQUIRED -> reviewRequiredCount++;
                case STATUS_SKIPPED -> skippedCount++;
                default -> throw new IllegalStateException("未知规则回填状态：" + item.status());
            }
        }

        private int applicableCount() {
            return applicableCount;
        }

        private int reviewRequiredCount() {
            return reviewRequiredCount;
        }

        private int updatedCount() {
            return updatedCount;
        }

        private int skippedCount() {
            return skippedCount;
        }
    }
}
