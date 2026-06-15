package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.NormalizationLibraryOperationCommand;
import com.jtxw.familyagent.domain.model.NormalizationApplyRuleRecordSnapshot;
import com.jtxw.familyagent.domain.model.NormalizationApplyRuleToRecordsItem;
import com.jtxw.familyagent.domain.model.NormalizationApplyRuleToRecordsResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.*;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationRuleRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
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
     * 重新计算 unit_price 时保留的小数位数，避免 BigDecimal 除法出现无限循环小数。
     */
    private static final int UNIT_PRICE_SCALE = 12;
    /**
     * 包装数量单位，仅用于识别“只有包数 / 罐数但没有每份规格”的不可靠场景，不会被直接换算成目标单位。
     */
    private static final String PACKAGE_UNIT_PATTERN = "包|袋|罐|瓶|支|件|盒|条|杯";
    /**
     * 重量单位表达式，限定为可安全换算到 g / kg 的单位。
     */
    private static final String WEIGHT_UNIT_PATTERN = "kg|KG|Kg|g|G|克|千克|公斤";
    /**
     * 容量单位表达式，限定为可安全换算到 ml / L 的单位。
     */
    private static final String VOLUME_UNIT_PATTERN = "ml|mL|ML|L|l|毫升|升";
    /**
     * 规格数量无法可靠解析时使用的复核原因码。
     */
    private static final String REVIEW_REASON_QUANTITY_UNIT_PARSE = "QUANTITY_UNIT_PARSE_REVIEW";
    /**
     * 商品归一化无法自动确认时使用的复核原因码。
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
     * @param databaseInitializer         数据库初始化组件
     * @param normalizationRuleRepository 归一化规则仓储
     * @param purchaseRecordRepository    购买记录仓储
     * @param reviewItemRepository        复核项仓储
     * @param productRuleMatcher          规则匹配器
     * @param quantityUnitParser          规格数量解析器
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
    @Transactional(rollbackFor = Exception.class)
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

    /**
     * 判断单条购买记录是否可以由目标规则自动回填，并在非 dry-run 时执行受控更新。
     *
     * @param record     候选购买记录，不允许为空
     * @param targetRule 本次应用的归一化规则，不允许为空
     * @param dryRun     是否只预览；为 false 时会写入 purchase_records 并关闭相关复核项
     * @return 单条回填结果；当记录不属于目标规则候选时返回 null
     */
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
                targetRule.normalizedName(), normalizeDecimalForResponse(parseResult.quantity()), parseResult.unit(),
                parseResult.unitPrice(), DECISION_INCLUDE, targetRule.id());
        List<String> itemWarnings = parseSuccessWarnings(parseResult);
        if (dryRun) {
            return new NormalizationApplyRuleToRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_APPLICABLE, before, after, itemWarnings);
        }
        int updatedCount = purchaseRecordRepository.updateNormalizedRecordAfterRuleApply(record.id(),
                targetRule.normalizedName(), parseResult.quantity().doubleValue(), parseResult.unit(),
                parseResult.unitPrice().doubleValue(), DECISION_INCLUDE, targetRule.id());
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

    /**
     * 校验解析结果是否足以安全回填统计字段。
     *
     * @param record      当前购买记录，用于检查金额是否可用于重算单价
     * @param targetRule  目标归一化规则，用于校验标准单位
     * @param parseResult 目标数量解析结果
     * @return 空列表表示可以自动回填；非空表示应进入人工复核
     */
    private List<String> validateParsedResult(PurchaseRecord record,
                                              ProductRule targetRule,
                                              TargetUnitQuantityParseResult parseResult) {
        Set<String> warnings = new LinkedHashSet<>();

        boolean amountInvalid = record.totalAmount() == null || record.totalAmount() <= 0D;
        boolean quantityInvalid = !parseResult.confident()
                || parseResult.quantity() == null
                || parseResult.quantity().compareTo(BigDecimal.ZERO) <= 0;
        boolean unitMismatch = !targetRule.standardUnit().equals(parseResult.unit());
        boolean unitPriceInvalid = parseResult.unitPrice() == null
                || parseResult.unitPrice().compareTo(BigDecimal.ZERO) <= 0;

        List<String> parseWarnings = parseResult.warnings() == null
                ? List.of()
                : parseResult.warnings();

        if (!parseResult.confident()) {
            warnings.addAll(parseWarnings);
        }

        if (quantityInvalid && parseWarnings.isEmpty()) {
            warnings.add("无法从 SKU、商品标题或原始记录可靠解析标准数量：" + targetRule.standardUnit());
        }

        if (unitMismatch) {
            warnings.add("解析单位与规则标准单位不一致：" + parseResult.unit() + " / " + targetRule.standardUnit());
        }

        if (amountInvalid) {
            warnings.add("统计金额为空或异常，无法重算 unit_price");
        } else if (unitPriceInvalid) {
            if (quantityInvalid) {
                warnings.add("标准数量未确认，无法重算 unit_price");
            } else {
                warnings.add("无法重算有效 unit_price");
            }
        }

        return new ArrayList<>(warnings);
    }

    /**
     * 按 SKU、商品标题、原记录同单位族的优先级解析目标标准数量。
     *
     * @param record     待回填购买记录
     * @param targetRule 目标归一化规则
     * @return 可自动回填的目标数量结果；存在冲突或无法解析时返回复核结果
     */
    private TargetUnitQuantityParseResult parseTargetUnitQuantity(PurchaseRecord record, ProductRule targetRule) {
        String targetUnit = targetRule.standardUnit();
        UnitFamily targetUnitFamily = targetUnitFamily(targetRule);
        TargetTextQuantityParseResult skuResult = parseTargetQuantityFromText(record.sku(), "sku",
                targetUnit, targetUnitFamily);
        if (skuResult.conflicted()) {
            return TargetUnitQuantityParseResult.reviewRequired(targetUnit, skuResult.warnings());
        }
        if (skuResult.quantity() != null) {
            return targetQuantityResult(record, skuResult.quantity(), targetUnit, skuResult.source(),
                    skuResult.warnings());
        }

        TargetTextQuantityParseResult productNameResult = parseTargetQuantityFromText(record.productName(),
                "product_name", targetUnit, targetUnitFamily);
        if (productNameResult.conflicted()) {
            return TargetUnitQuantityParseResult.reviewRequired(targetUnit, productNameResult.warnings());
        }
        if (productNameResult.quantity() != null) {
            List<String> packageConflictWarnings = validateProductNameFallbackPackageCount(record.sku(),
                    productNameResult);
            if (!packageConflictWarnings.isEmpty()) {
                return TargetUnitQuantityParseResult.reviewRequired(targetUnit, packageConflictWarnings);
            }
            return targetQuantityResult(record, productNameResult.quantity(), targetUnit,
                    productNameResult.source(), productNameResult.warnings());
        }

        TargetUnitQuantityParseResult originalRecordResult = parseOriginalRecordQuantity(record, targetUnit,
                targetUnitFamily);
        if (originalRecordResult.confident()) {
            return originalRecordResult;
        }

        List<String> warnings = new ArrayList<>();
        warnings.add("无法从 SKU 或商品标题可靠解析标准数量：" + targetUnit);
        warnings.addAll(skuResult.warnings());
        warnings.addAll(productNameResult.warnings());
        warnings.addAll(originalRecordResult.warnings());
        return TargetUnitQuantityParseResult.reviewRequired(targetUnit, warnings);
    }

    /**
     * 校验是否允许使用商品标题规格作为 SKU 缺规格时的兜底数量。
     *
     * <p>当 SKU 已出现实际包装数量，而商品标题规格中的包装数量不一致或不可比较时，
     * 不使用标题通用规格自动回填，避免把商品主规格误当成实际购买规格。</p>
     *
     * @param sku               当前记录 SKU 文本，允许为空
     * @param productNameResult 商品标题解析结果，需包含标题规格中的包装数量
     * @return 空列表表示允许兜底；非空表示应进入人工复核
     */
    private List<String> validateProductNameFallbackPackageCount(String sku,
                                                                 TargetTextQuantityParseResult productNameResult) {
        List<BigDecimal> skuPackageCounts = extractPackageOnlyCounts(sku);
        if (skuPackageCounts.isEmpty()) {
            return List.of();
        }
        if (skuPackageCounts.size() > 1 || productNameResult.packageCount() == null) {
            return List.of("SKU 存在实际包装数量但缺少每份克重，不能使用商品标题通用规格自动回填");
        }
        BigDecimal skuPackageCount = skuPackageCounts.get(0);
        if (skuPackageCount.compareTo(productNameResult.packageCount()) != 0) {
            return List.of("SKU 包装数量与商品标题规格数量不一致，需人工复核");
        }
        return List.of();
    }

    /**
     * 根据解析出的目标数量生成可回填结果，并按订单金额重算标准单价。
     *
     * @param record   当前购买记录，用于读取实付金额
     * @param quantity 目标单位数量，必须大于 0
     * @param unit     目标标准单位
     * @param source   数量来源，取值为 sku、product_name 或 original_record
     * @param warnings 解析过程中的可读提示
     * @return 可自动回填的目标数量结果
     */
    private TargetUnitQuantityParseResult targetQuantityResult(PurchaseRecord record,
                                                               BigDecimal quantity,
                                                               String unit,
                                                               String source,
                                                               List<String> warnings) {
        BigDecimal unitPrice = calculateUnitPrice(record.totalAmount(), quantity);
        return new TargetUnitQuantityParseResult(quantity, unit, unitPrice, source, true, warnings);
    }

    /**
     * 按规则目标单位族解析单段文本。SKU 与商品标题会分别调用该方法，避免两者结果互相污染。
     *
     * @param text             待解析的 SKU 或商品标题
     * @param source           文本来源
     * @param targetUnit       规则标准单位
     * @param targetUnitFamily 规则标准单位族
     * @return 可回填的目标数量；存在冲突或高风险歧义时返回 conflicted
     */
    private TargetTextQuantityParseResult parseTargetQuantityFromText(String text,
                                                                      String source,
                                                                      String targetUnit,
                                                                      UnitFamily targetUnitFamily) {
        if (text == null || text.isBlank()) {
            return TargetTextQuantityParseResult.notFound(source);
        }
        return switch (targetUnitFamily) {
            case WEIGHT -> parseMeasurementQuantityFromText(text, source, targetUnit, true);
            case VOLUME -> parseMeasurementQuantityFromText(text, source, targetUnit, false);
            case COUNT, DRAW_COUNT -> parseTargetCountQuantityFromText(text, source, targetUnit);
            default -> parseByExistingQuantityParser(text, source, targetUnit);
        };
    }

    /**
     * 从重量或容量文本中解析目标单位数量。
     *
     * @param text         SKU 或商品标题原文
     * @param source       文本来源，取值为 sku 或 product_name
     * @param targetUnit   规则标准单位
     * @param weightFamily true 表示重量单位族，false 表示容量单位族
     * @return 单段文本解析结果；存在松散包装关系或多个不同结果时返回冲突
     */
    private TargetTextQuantityParseResult parseMeasurementQuantityFromText(String text,
                                                                           String source,
                                                                           String targetUnit,
                                                                           boolean weightFamily) {
        String normalizedText = normalizeSpecText(text);
        String unitPattern = weightFamily ? WEIGHT_UNIT_PATTERN : VOLUME_UNIT_PATTERN;
        List<ParsedQuantityCandidate> candidates = new ArrayList<>();
        collectPerPackageCandidates(normalizedText, source, targetUnit, weightFamily, unitPattern, candidates);
        collectUnitMultiplierCandidates(normalizedText, source, targetUnit, weightFamily, unitPattern, candidates);
        collectDirectMeasurementCandidates(normalizedText, source, targetUnit, weightFamily, unitPattern, candidates);
        if (hasAmbiguousLoosePackageSpec(normalizedText, unitPattern) && noExplicitPackageCandidate(candidates)) {
            return TargetTextQuantityParseResult.conflicted(source,
                    sourceName(source) + " 中数量和规格没有明确乘法或每份关系，需人工复核");
        }
        if (candidates.isEmpty()) {
            List<String> warnings = containsPackageOnlyText(normalizedText)
                    ? List.of(sourceName(source) + " 仅包含件数/包数/罐数，缺少每份克重或容量")
                    : List.of(sourceName(source) + " 未找到明确 " + targetUnit + " 规格");
            return TargetTextQuantityParseResult.notFound(source, warnings);
        }
        return uniqueCandidateOrConflict(source, candidates);
    }

    /**
     * 收集“包装数量 + 每份规格”的候选数量，例如 6包 80g/包。
     *
     * @param normalizedText 已归一化符号的规格文本
     * @param source         文本来源，仅用于保持解析调用边界
     * @param targetUnit     规则标准单位
     * @param weightFamily   true 表示重量单位族，false 表示容量单位族
     * @param unitPattern    当前单位族允许识别的单位正则
     * @param candidates     解析出的候选数量集合，会被追加写入
     */
    private void collectPerPackageCandidates(String normalizedText,
                                             String source,
                                             String targetUnit,
                                             boolean weightFamily,
                                             String unitPattern,
                                             List<ParsedQuantityCandidate> candidates) {
        Pattern countBeforeSpec = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(" + PACKAGE_UNIT_PATTERN
                + ").{0,12}?(\\d+(?:\\.\\d+)?)\\s*(" + unitPattern + ")\\s*/\\s*("
                + PACKAGE_UNIT_PATTERN + ")");
        Matcher countBeforeMatcher = countBeforeSpec.matcher(normalizedText);
        while (countBeforeMatcher.find()) {
            BigDecimal count = decimal(countBeforeMatcher.group(1));
            String packageUnit = countBeforeMatcher.group(2);
            BigDecimal singleQuantity = convertToTargetUnit(decimal(countBeforeMatcher.group(3)),
                    countBeforeMatcher.group(4), targetUnit, weightFamily);
            BigDecimal quantity = singleQuantity.multiply(count);
            String expression = formatQuantity(count) + packageUnit + " * " + formatQuantity(singleQuantity)
                    + targetUnit + "/" + packageUnit;
            candidates.add(new ParsedQuantityCandidate(quantity, expression,
                    "规格解析：" + expression + " => " + formatQuantity(quantity) + targetUnit, count));
        }

        Pattern specBeforeCount = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(" + unitPattern
                + ")\\s*/\\s*(" + PACKAGE_UNIT_PATTERN + ")\\s*(?:[xX*]\\s*)?(\\d+(?:\\.\\d+)?)\\s*(?:"
                + PACKAGE_UNIT_PATTERN + ")?");
        Matcher specBeforeMatcher = specBeforeCount.matcher(normalizedText);
        while (specBeforeMatcher.find()) {
            BigDecimal singleQuantity = convertToTargetUnit(decimal(specBeforeMatcher.group(1)),
                    specBeforeMatcher.group(2), targetUnit, weightFamily);
            String packageUnit = specBeforeMatcher.group(3);
            BigDecimal count = decimal(specBeforeMatcher.group(4));
            BigDecimal quantity = singleQuantity.multiply(count);
            String expression = formatQuantity(singleQuantity) + targetUnit + "/" + packageUnit
                    + " * " + formatQuantity(count);
            candidates.add(new ParsedQuantityCandidate(quantity, expression,
                    "规格解析：" + expression + " => " + formatQuantity(quantity) + targetUnit, count));
        }
    }

    /**
     * 收集“单份规格 * 包装数量”的候选数量，例如 50g*3包 或 500ml*2瓶。
     *
     * @param normalizedText 已归一化符号的规格文本
     * @param source         文本来源，仅用于保持解析调用边界
     * @param targetUnit     规则标准单位
     * @param weightFamily   true 表示重量单位族，false 表示容量单位族
     * @param unitPattern    当前单位族允许识别的单位正则
     * @param candidates     解析出的候选数量集合，会被追加写入
     */
    private void collectUnitMultiplierCandidates(String normalizedText,
                                                 String source,
                                                 String targetUnit,
                                                 boolean weightFamily,
                                                 String unitPattern,
                                                 List<ParsedQuantityCandidate> candidates) {
        Pattern unitMultiplier = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(" + unitPattern
                + ")\\s*[xX*]\\s*(\\d+(?:\\.\\d+)?)\\s*(?:(?:" + PACKAGE_UNIT_PATTERN
                + ")|(?:/\\s*(?:" + PACKAGE_UNIT_PATTERN + ")))?");
        Matcher matcher = unitMultiplier.matcher(normalizedText);
        while (matcher.find()) {
            BigDecimal singleQuantity = convertToTargetUnit(decimal(matcher.group(1)), matcher.group(2),
                    targetUnit, weightFamily);
            BigDecimal count = decimal(matcher.group(3));
            BigDecimal quantity = singleQuantity.multiply(count);
            String packageUnit = extractTrailingPackageUnit(matcher.group(0));
            String expression = formatQuantity(singleQuantity) + targetUnit + " * " + formatQuantity(count)
                    + packageUnit;
            candidates.add(new ParsedQuantityCandidate(quantity, expression,
                    "规格解析：" + expression + " => " + formatQuantity(quantity) + targetUnit, count));
        }
    }

    /**
     * 收集直接标注的总规格候选数量，例如 600g、0.6kg 或 1L。
     *
     * @param normalizedText 已归一化符号的规格文本
     * @param source         文本来源，仅用于保持解析调用边界
     * @param targetUnit     规则标准单位
     * @param weightFamily   true 表示重量单位族，false 表示容量单位族
     * @param unitPattern    当前单位族允许识别的单位正则
     * @param candidates     解析出的候选数量集合，会被追加写入
     */
    private void collectDirectMeasurementCandidates(String normalizedText,
                                                    String source,
                                                    String targetUnit,
                                                    boolean weightFamily,
                                                    String unitPattern,
                                                    List<ParsedQuantityCandidate> candidates) {
        Pattern directMeasurement = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(" + unitPattern + ")");
        Matcher matcher = directMeasurement.matcher(normalizedText);
        while (matcher.find()) {
            if (isPartOfExplicitExpression(normalizedText, matcher.start(), matcher.end())) {
                continue;
            }
            BigDecimal quantity = convertToTargetUnit(decimal(matcher.group(1)), matcher.group(2),
                    targetUnit, weightFamily);
            String expression = formatQuantity(quantity) + targetUnit;
            candidates.add(new ParsedQuantityCandidate(quantity, expression,
                    "规格解析：" + expression + " => " + formatQuantity(quantity) + targetUnit, null));
        }
    }

    /**
     * 从计数类文本中解析目标单位数量。
     *
     * <p>COUNT / DRAW_COUNT 不做同族单位换算，只允许解析目标单位本身。
     * 例如目标单位为“片”时，只能解析“10片”“2盒 * 10片/盒”等明确片数，
     * 不能把“条”“件”“颗”“包”“盒”等其它数量单位换算成“片”。</p>
     *
     * @param text       SKU 或商品标题原文
     * @param source     文本来源，取值为 sku 或 product_name
     * @param targetUnit 规则标准计数单位
     * @return 单段文本解析结果；多个不同目标数量会返回冲突
     */
    private TargetTextQuantityParseResult parseTargetCountQuantityFromText(String text,
                                                                           String source,
                                                                           String targetUnit) {
        if (text == null || text.isBlank()) {
            return TargetTextQuantityParseResult.notFound(source);
        }
        String normalizedText = normalizeSpecText(text);
        List<ParsedQuantityCandidate> candidates = new ArrayList<>();

        collectTargetCountPerPackageCandidates(normalizedText, targetUnit, candidates);
        collectTargetCountMultiplierCandidates(normalizedText, targetUnit, candidates);
        collectDirectTargetCountCandidates(normalizedText, targetUnit, candidates);

        if (candidates.isEmpty()) {
            return TargetTextQuantityParseResult.notFound(source,
                    List.of(sourceName(source) + " 未找到明确 " + targetUnit + " 数量"));
        }
        return uniqueCandidateOrConflict(source, candidates);
    }

    /**
     * 解析“2盒 10片/盒”“10片/盒 * 2盒”这类每包装目标数量表达。
     *
     * @param normalizedText 已归一化符号的规格文本
     * @param targetUnit     规则标准计数单位
     * @param candidates     解析出的候选数量集合，会被追加写入
     */
    private void collectTargetCountPerPackageCandidates(String normalizedText,
                                                        String targetUnit,
                                                        List<ParsedQuantityCandidate> candidates) {
        String quotedTargetUnit = Pattern.quote(targetUnit);

        Pattern packageBeforeTarget = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:" + PACKAGE_UNIT_PATTERN
                + ")\\s*(?:[xX*])?.{0,12}?(\\d+(?:\\.\\d+)?)\\s*" + quotedTargetUnit
                + "\\s*/\\s*(?:" + PACKAGE_UNIT_PATTERN + ")");
        Matcher packageBeforeMatcher = packageBeforeTarget.matcher(normalizedText);
        while (packageBeforeMatcher.find()) {
            BigDecimal packageCount = decimal(packageBeforeMatcher.group(1));
            BigDecimal singleCount = decimal(packageBeforeMatcher.group(2));
            BigDecimal quantity = singleCount.multiply(packageCount);
            String expression = formatQuantity(packageCount) + "份 * " + formatQuantity(singleCount)
                    + targetUnit + "/份";
            candidates.add(new ParsedQuantityCandidate(quantity, expression,
                    "规格解析：" + expression + " => " + formatQuantity(quantity) + targetUnit,
                    packageCount));
        }

        Pattern targetBeforePackage = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*" + quotedTargetUnit
                + "\\s*/\\s*(?:" + PACKAGE_UNIT_PATTERN + ")\\s*(?:[xX*]\\s*)?"
                + "(\\d+(?:\\.\\d+)?)\\s*(?:" + PACKAGE_UNIT_PATTERN + ")?");
        Matcher targetBeforeMatcher = targetBeforePackage.matcher(normalizedText);
        while (targetBeforeMatcher.find()) {
            BigDecimal singleCount = decimal(targetBeforeMatcher.group(1));
            BigDecimal packageCount = decimal(targetBeforeMatcher.group(2));
            BigDecimal quantity = singleCount.multiply(packageCount);
            String expression = formatQuantity(singleCount) + targetUnit + "/份 * "
                    + formatQuantity(packageCount) + "份";
            candidates.add(new ParsedQuantityCandidate(quantity, expression,
                    "规格解析：" + expression + " => " + formatQuantity(quantity) + targetUnit,
                    packageCount));
        }
    }

    /**
     * 解析“10片*2盒”“2盒*10片”这类目标单位乘包装数量表达。
     *
     * @param normalizedText 已归一化符号的规格文本
     * @param targetUnit     规则标准计数单位
     * @param candidates     解析出的候选数量集合，会被追加写入
     */
    private void collectTargetCountMultiplierCandidates(String normalizedText,
                                                        String targetUnit,
                                                        List<ParsedQuantityCandidate> candidates) {
        String quotedTargetUnit = Pattern.quote(targetUnit);

        Pattern targetBeforePackage = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*" + quotedTargetUnit
                + "\\s*[xX*]\\s*(\\d+(?:\\.\\d+)?)\\s*(?:" + PACKAGE_UNIT_PATTERN + ")");
        Matcher targetBeforeMatcher = targetBeforePackage.matcher(normalizedText);
        while (targetBeforeMatcher.find()) {
            BigDecimal singleCount = decimal(targetBeforeMatcher.group(1));
            BigDecimal packageCount = decimal(targetBeforeMatcher.group(2));
            BigDecimal quantity = singleCount.multiply(packageCount);
            String expression = formatQuantity(singleCount) + targetUnit + " * "
                    + formatQuantity(packageCount) + "份";
            candidates.add(new ParsedQuantityCandidate(quantity, expression,
                    "规格解析：" + expression + " => " + formatQuantity(quantity) + targetUnit,
                    packageCount));
        }

        Pattern packageBeforeTarget = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:" + PACKAGE_UNIT_PATTERN
                + ")\\s*[xX*]\\s*(\\d+(?:\\.\\d+)?)\\s*" + quotedTargetUnit);
        Matcher packageBeforeMatcher = packageBeforeTarget.matcher(normalizedText);
        while (packageBeforeMatcher.find()) {
            BigDecimal packageCount = decimal(packageBeforeMatcher.group(1));
            BigDecimal singleCount = decimal(packageBeforeMatcher.group(2));
            BigDecimal quantity = packageCount.multiply(singleCount);
            String expression = formatQuantity(packageCount) + "份 * "
                    + formatQuantity(singleCount) + targetUnit;
            candidates.add(new ParsedQuantityCandidate(quantity, expression,
                    "规格解析：" + expression + " => " + formatQuantity(quantity) + targetUnit,
                    packageCount));
        }
    }

    /**
     * 解析“10片”“10片装”这类直接目标单位数量。
     *
     * <p>如果该目标数量已经属于明确乘法或每份规格表达的一部分，则跳过，
     * 避免同一表达重复生成候选。</p>
     *
     * @param normalizedText 已归一化符号的规格文本
     * @param targetUnit     规则标准计数单位
     * @param candidates     解析出的候选数量集合，会被追加写入
     */
    private void collectDirectTargetCountCandidates(String normalizedText,
                                                    String targetUnit,
                                                    List<ParsedQuantityCandidate> candidates) {
        String quotedTargetUnit = Pattern.quote(targetUnit);
        Pattern countPattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*" + quotedTargetUnit + "\\s*(?:装)?");
        Matcher matcher = countPattern.matcher(normalizedText);
        while (matcher.find()) {
            if (isPartOfExplicitExpression(normalizedText, matcher.start(), matcher.end())) {
                continue;
            }
            BigDecimal quantity = decimal(matcher.group(1));
            String expression = formatQuantity(quantity) + targetUnit;
            candidates.add(new ParsedQuantityCandidate(quantity, expression,
                    "规格解析：" + expression + " => " + formatQuantity(quantity) + targetUnit,
                    null));
        }
    }

    /**
     * 使用通用数量解析器处理无法归入专用单位族的目标单位。
     *
     * @param text       SKU 或商品标题原文
     * @param source     文本来源，取值为 sku 或 product_name
     * @param targetUnit 规则标准单位
     * @return 通用解析器给出的安全解析结果；需要复核或单位不一致时返回未找到
     */
    private TargetTextQuantityParseResult parseByExistingQuantityParser(String text, String source, String targetUnit) {
        QuantityUnitParseResult fallbackResult = quantityUnitParser.parse("", targetUnit, text, null,
                null, null, null);
        if (fallbackResult.needReview() || fallbackResult.quantity() == null
                || fallbackResult.unit() == null || !targetUnit.equals(fallbackResult.unit())) {
            return TargetTextQuantityParseResult.notFound(source);
        }
        BigDecimal quantity = BigDecimal.valueOf(fallbackResult.quantity());
        return new TargetTextQuantityParseResult(quantity, source, false,
                List.of("规格解析：" + fallbackResult.parseEvidence() + " => " + formatQuantity(quantity)
                        + targetUnit), null);
    }

    /**
     * 当 SKU 和商品标题都无法解析时，尝试使用原记录数量按同单位族换算。
     *
     * @param record           当前购买记录
     * @param targetUnit       规则标准单位
     * @param targetUnitFamily 规则标准单位族
     * @return 原记录同单位族换算结果；单位族不一致或原值异常时返回复核结果
     */
    private TargetUnitQuantityParseResult parseOriginalRecordQuantity(PurchaseRecord record,
                                                                      String targetUnit,
                                                                      UnitFamily targetUnitFamily) {
        if (record.quantity() == null || record.quantity() <= 0D || record.unit() == null || record.unit().isBlank()) {
            return TargetUnitQuantityParseResult.reviewRequired(targetUnit, List.of("原始记录数量或单位为空，无法兜底换算"));
        }
        BigDecimal convertedQuantity = convertOriginalQuantity(BigDecimal.valueOf(record.quantity()), record.unit(),
                targetUnit, targetUnitFamily);
        if (convertedQuantity == null) {
            return TargetUnitQuantityParseResult.reviewRequired(targetUnit,
                    List.of(originalUnitNotConvertibleWarning(record.unit(), targetUnit, targetUnitFamily)));
        }
        return targetQuantityResult(record, convertedQuantity, targetUnit, "original_record",
                List.of("单位换算：" + formatQuantity(BigDecimal.valueOf(record.quantity())) + record.unit()
                        + " => " + formatQuantity(convertedQuantity) + targetUnit));
    }

    /**
     * 将原记录数量按目标规则单位族换算为目标单位数量。
     *
     * @param quantity         原记录数量
     * @param sourceUnit       原记录单位
     * @param targetUnit       规则标准单位
     * @param targetUnitFamily 规则标准单位族
     * @return 可换算时返回目标单位数量；不同单位族或未知单位返回 null
     */
    private BigDecimal convertOriginalQuantity(BigDecimal quantity,
                                               String sourceUnit,
                                               String targetUnit,
                                               UnitFamily targetUnitFamily) {
        if (targetUnitFamily == UnitFamily.WEIGHT) {
            return convertToTargetUnit(quantity, sourceUnit, targetUnit, true);
        }
        if (targetUnitFamily == UnitFamily.VOLUME) {
            return convertToTargetUnit(quantity, sourceUnit, targetUnit, false);
        }
        if ((targetUnitFamily == UnitFamily.COUNT || targetUnitFamily == UnitFamily.DRAW_COUNT)
                && targetUnit.equals(sourceUnit.trim())) {
            return quantity;
        }
        return null;
    }

    /**
     * 生成原始记录单位无法自动换算时的复核提示。
     *
     * @param sourceUnit       原始记录单位
     * @param targetUnit       规则标准单位
     * @param targetUnitFamily 规则标准单位族
     * @return 面向调用方的复核提示
     */
    private String originalUnitNotConvertibleWarning(String sourceUnit,
                                                     String targetUnit,
                                                     UnitFamily targetUnitFamily) {
        if (targetUnitFamily == UnitFamily.COUNT || targetUnitFamily == UnitFamily.DRAW_COUNT) {
            return "原始记录单位不是目标计数单位，无法自动换算：" + sourceUnit + " / " + targetUnit;
        }
        return "原始记录单位无法按同单位族换算：" + sourceUnit + " / " + targetUnit;
    }

    /**
     * 将重量或容量数量从来源单位换算为目标单位。
     *
     * @param quantity     来源数量
     * @param sourceUnit   来源单位
     * @param targetUnit   目标单位
     * @param weightFamily true 表示重量换算，false 表示容量换算
     * @return 可换算时返回目标单位数量；单位未知或目标单位非法时返回 null
     */
    private BigDecimal convertToTargetUnit(BigDecimal quantity,
                                           String sourceUnit,
                                           String targetUnit,
                                           boolean weightFamily) {
        BigDecimal sourceFactor = weightFamily ? weightUnitToGramFactor(sourceUnit) : volumeUnitToMilliliterFactor(sourceUnit);
        if (sourceFactor == null) {
            return null;
        }
        BigDecimal baseQuantity = quantity.multiply(sourceFactor);
        BigDecimal targetFactor = weightFamily ? weightUnitToGramFactor(targetUnit) : volumeUnitToMilliliterFactor(targetUnit);
        if (targetFactor == null || targetFactor.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return baseQuantity.divide(targetFactor, UNIT_PRICE_SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /**
     * 返回重量单位换算到克的倍率。
     *
     * @param unit 待识别重量单位，允许为空
     * @return 已知重量单位的克倍率；未知单位返回 null
     */
    private BigDecimal weightUnitToGramFactor(String unit) {
        String normalizedUnit = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedUnit) {
            case "g", "克" -> BigDecimal.ONE;
            case "kg", "千克", "公斤" -> BigDecimal.valueOf(1000);
            default -> null;
        };
    }

    /**
     * 返回容量单位换算到毫升的倍率。
     *
     * @param unit 待识别容量单位，允许为空
     * @return 已知容量单位的毫升倍率；未知单位返回 null
     */
    private BigDecimal volumeUnitToMilliliterFactor(String unit) {
        String normalizedUnit = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedUnit) {
            case "ml", "毫升" -> BigDecimal.ONE;
            case "l", "升" -> BigDecimal.valueOf(1000);
            default -> null;
        };
    }

    /**
     * 按实付金额和标准数量重算标准单价。
     *
     * @param totalAmount 实付金额，单位为元
     * @param quantity    标准数量，必须大于 0
     * @return 可计算时返回标准单价；金额或数量异常时返回 null
     */
    private BigDecimal calculateUnitPrice(Double totalAmount, BigDecimal quantity) {
        if (totalAmount == null || totalAmount <= 0D || quantity == null
                || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return BigDecimal.valueOf(totalAmount).divide(quantity, UNIT_PRICE_SCALE, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    /**
     * 生成自动回填成功时返回给调用方的解析提示。
     *
     * @param parseResult 已确认可回填的目标数量解析结果
     * @return 包含数量来源和规格解析证据的提示列表
     */
    private List<String> parseSuccessWarnings(TargetUnitQuantityParseResult parseResult) {
        List<String> warnings = new ArrayList<>();
        if (parseResult.source() != null) {
            warnings.add("数量来源：" + parseResult.source());
        }
        warnings.addAll(parseResult.warnings());
        return warnings;
    }

    /**
     * 判断候选集中是否没有任何明确包装数量候选。
     *
     * @param candidates 规格解析候选集合
     * @return true 表示没有明确包装数量候选，松散规格需要更保守地复核
     */
    private boolean noExplicitPackageCandidate(List<ParsedQuantityCandidate> candidates) {
        return candidates.stream().noneMatch(candidate -> candidate.packageCount() != null);
    }

    /**
     * 合并同一文本中的候选规格，只有换算结果不一致时才视为冲突。
     *
     * @param source     文本来源，取值为 sku 或 product_name
     * @param candidates 已收集的候选规格
     * @return 唯一数量解析结果；多个不同数量时返回冲突结果
     */
    private TargetTextQuantityParseResult uniqueCandidateOrConflict(String source,
                                                                    List<ParsedQuantityCandidate> candidates) {
        List<ParsedQuantityCandidate> uniqueCandidates = new ArrayList<>();
        for (ParsedQuantityCandidate candidate : candidates) {
            boolean exists = uniqueCandidates.stream()
                    .anyMatch(existing -> existing.quantity().compareTo(candidate.quantity()) == 0);
            if (!exists) {
                uniqueCandidates.add(candidate);
            }
        }
        if (uniqueCandidates.size() > 1) {
            return TargetTextQuantityParseResult.conflicted(source,
                    sourceName(source) + " 内存在多个冲突规格，需人工复核：" + uniqueCandidates.stream()
                            .map(candidate -> candidate.expression() + "=" + formatQuantity(candidate.quantity()))
                            .toList());
        }
        ParsedQuantityCandidate candidate = uniqueCandidates.get(0);
        BigDecimal packageCount = null;
        for (ParsedQuantityCandidate currentCandidate : candidates) {
            if (currentCandidate.packageCount() == null) {
                continue;
            }
            if (packageCount == null) {
                packageCount = currentCandidate.packageCount();
                continue;
            }
            if (packageCount.compareTo(currentCandidate.packageCount()) != 0) {
                packageCount = null;
                break;
            }
        }
        return new TargetTextQuantityParseResult(candidate.quantity(), source, false,
                List.of(candidate.evidence()), packageCount);
    }

    /**
     * 推断目标规则的标准单位族。
     *
     * @param targetRule 目标归一化规则
     * @return 规则显式单位族；未显式配置时根据标准单位推断
     */
    private UnitFamily targetUnitFamily(ProductRule targetRule) {
        if (targetRule.unitFamily() != null && targetRule.unitFamily() != UnitFamily.UNKNOWN) {
            return targetRule.unitFamily();
        }
        if (weightUnitToGramFactor(targetRule.standardUnit()) != null) {
            return UnitFamily.WEIGHT;
        }
        if (volumeUnitToMilliliterFactor(targetRule.standardUnit()) != null) {
            return UnitFamily.VOLUME;
        }
        return UnitFamily.COUNT;
    }

    /**
     * 判断文本是否存在“包装数量 + 松散规格”的高风险表达。
     *
     * @param text        已归一化符号的规格文本
     * @param unitPattern 当前单位族允许识别的单位正则
     * @return true 表示存在不能自动换算的松散包装规格
     */
    private boolean hasAmbiguousLoosePackageSpec(String text, String unitPattern) {
        Pattern ambiguousPattern = Pattern.compile("[xX*]\\s*\\d+(?:\\.\\d+)?\\s*(?:" + PACKAGE_UNIT_PATTERN
                + ").{0,12}?\\d+(?:\\.\\d+)?\\s*(?:" + unitPattern + ")");
        return ambiguousPattern.matcher(text).find() && !hasExplicitPerPackageSpec(text, unitPattern);
    }

    /**
     * 判断文本中是否存在明确的每份规格表达。
     *
     * @param text        已归一化符号的规格文本
     * @param unitPattern 当前单位族允许识别的单位正则
     * @return true 表示存在类似 80g/包 或 6包 80g/包 的明确关系
     */
    private boolean hasExplicitPerPackageSpec(String text, String unitPattern) {
        Pattern explicitPattern = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:" + unitPattern + ")\\s*/\\s*(?:"
                + PACKAGE_UNIT_PATTERN + ")|\\d+(?:\\.\\d+)?\\s*(?:" + PACKAGE_UNIT_PATTERN
                + ").{0,12}?\\d+(?:\\.\\d+)?\\s*(?:" + unitPattern + ")\\s*/\\s*(?:"
                + PACKAGE_UNIT_PATTERN + ")");
        return explicitPattern.matcher(text).find();
    }

    /**
     * 判断文本是否只表达包装数量。
     *
     * @param text 已归一化符号的规格文本
     * @return true 表示文本中存在包、袋、罐等包装数量
     */
    private boolean containsPackageOnlyText(String text) {
        return Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:" + PACKAGE_UNIT_PATTERN + ")").matcher(text).find();
    }

    /**
     * 从 SKU 文本中提取包装数量，用于判断是否允许商品标题兜底。
     *
     * @param text SKU 文本，允许为空
     * @return 去重后的包装数量列表；无包装数量时返回空列表
     */
    private List<BigDecimal> extractPackageOnlyCounts(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:" + PACKAGE_UNIT_PATTERN + ")")
                .matcher(normalizeSpecText(text));
        List<BigDecimal> counts = new ArrayList<>();
        while (matcher.find()) {
            BigDecimal count = decimal(matcher.group(1));
            if (counts.stream().noneMatch(existing -> existing.compareTo(count) == 0)) {
                counts.add(count);
            }
        }
        return counts;
    }

    /**
     * 从乘法规格表达式尾部提取包装单位。
     *
     * @param expression 乘法规格片段，例如 50g*3包 或 40g*7/盒
     * @return 包装单位展示文本；未识别时返回空字符串
     */
    private String extractTrailingPackageUnit(String expression) {
        Matcher matcher = Pattern.compile("(?:/\\s*)?(" + PACKAGE_UNIT_PATTERN + ")\\s*$")
                .matcher(expression);
        if (!matcher.find()) {
            return "";
        }
        String packageUnit = matcher.group(1);
        String prefix = expression.substring(0, matcher.start());
        return prefix.trim().endsWith("/") ? "/" + packageUnit : packageUnit;
    }

    /**
     * 判断直接规格片段是否已经属于明确乘法或每份规格表达。
     *
     * @param text  已归一化符号的规格文本
     * @param start 当前直接规格匹配起始位置
     * @param end   当前直接规格匹配结束位置
     * @return true 表示该片段不应再作为独立总规格候选
     */
    private boolean isPartOfExplicitExpression(String text, int start, int end) {
        String tail = text.substring(end, Math.min(text.length(), end + 8));
        if (tail.matches("^\\s*(?:/|[xX*]).*")) {
            return true;
        }
        int prefixStart = Math.max(0, start - 8);
        String prefix = text.substring(prefixStart, start);
        return prefix.matches(".*\\d+(?:\\.\\d+)?\\s*(?:" + PACKAGE_UNIT_PATTERN + ").*");
    }

    /**
     * 将字符串数字转换为 BigDecimal 并去掉无意义尾零。
     *
     * @param value 数字字符串，不允许为空
     * @return 标准化后的 BigDecimal
     */
    private BigDecimal decimal(String value) {
        return new BigDecimal(value).stripTrailingZeros();
    }

    /**
     * 将数量格式化为普通十进制文本，用于 warnings 展示。
     *
     * @param quantity 待展示数量
     * @return 不使用科学计数法的数量文本
     */
    private String formatQuantity(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    /**
     * 规范响应快照中的数量，避免整数 BigDecimal 被 JSON 序列化为科学计数法。
     *
     * @param value 待输出数量，允许为空
     * @return 适合接口响应展示的 BigDecimal
     */
    private BigDecimal normalizeDecimalForResponse(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            return stripped.setScale(0);
        }
        return stripped;
    }

    /**
     * 归一化规格文本中的常见全角符号和乘法符号。
     *
     * @param text 原始 SKU 或商品标题文本
     * @return 便于正则解析的规格文本
     */
    private String normalizeSpecText(String text) {
        return text.trim()
                .replace('（', '(')
                .replace('）', ')')
                .replace('＊', '*')
                .replace('×', 'x')
                .replace('Ｘ', 'X')
                .replace('脳', 'x');
    }

    /**
     * 将内部来源编码转换为面向调用方的中文来源名称。
     *
     * @param source 来源编码，取值通常为 sku 或 product_name
     * @return 中文来源名称
     */
    private String sourceName(String source) {
        return "sku".equals(source) ? "SKU" : "商品标题";
    }

    /**
     * 在非 dry-run 且需要复核时创建待处理复核项。
     *
     * @param record   当前购买记录
     * @param dryRun   是否只预览；为 true 时不会写库
     * @param warnings 复核原因列表
     */
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

    /**
     * 查询启用状态下的目标归一化规则。
     *
     * @param ruleCode 规则编码，不允许为空
     * @return 启用的商品归一化规则
     * @throws IllegalArgumentException 当规则不存在或未启用时抛出
     */
    private ProductRule enabledRule(String ruleCode) {
        return normalizationRuleRepository.listEnabledProductRules().stream()
                .filter(rule -> ruleCode.equals(rule.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("归一化规则未启用：" + ruleCode));
    }

    /**
     * 校验并归一化规则回填请求参数。
     *
     * @param command 规则库统一操作命令
     * @return 内部规则回填请求对象
     * @throws IllegalArgumentException 当 ruleCode 缺失或 limit 非法时抛出
     */
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

    /**
     * 构造购买记录当前归一化统计字段快照。
     *
     * @param record 当前购买记录
     * @return 回填前快照
     */
    private NormalizationApplyRuleRecordSnapshot snapshot(PurchaseRecord record) {
        return new NormalizationApplyRuleRecordSnapshot(record.normalizedName(), decimal(record.quantity()),
                record.unit(), decimal(record.unitPrice()), record.decision(), record.normalizationRule());
    }

    /**
     * 判断文本是否命中目标规则的排除关键词。
     *
     * @param rule 目标归一化规则
     * @param text 商品标题和 SKU 合并文本
     * @return true 表示命中排除关键词，应跳过自动回填
     */
    private boolean matchesExcludeKeyword(ProductRule rule, String text) {
        return firstMatchedKeyword(rule.excludeKeywords(), text) != null;
    }

    /**
     * 判断文本是否命中目标规则的包含关键词。
     *
     * @param rule 目标归一化规则
     * @param text 商品标题和 SKU 合并文本
     * @return true 表示命中包含关键词，可继续规则匹配校验
     */
    private boolean matchesIncludeKeyword(ProductRule rule, String text) {
        return firstMatchedKeyword(rule.includeKeywords(), text) != null;
    }

    /**
     * 判断购买记录是否为重复或非唯一记录。
     *
     * @param record 当前购买记录
     * @return true 表示不允许自动回填
     */
    private boolean isDuplicateOrNotUnique(PurchaseRecord record) {
        return record.duplicate() || !"unique".equalsIgnoreCase(safeText(record.dedupeStatus()).trim());
    }

    /**
     * 返回文本中第一个命中的关键词。
     *
     * @param keywords 待匹配关键词列表，允许包含空值
     * @param text     待匹配文本
     * @return 第一个命中关键词；未命中时返回 null
     */
    private String firstMatchedKeyword(List<String> keywords, String text) {
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword.trim())) {
                return keyword.trim();
            }
        }
        return null;
    }

    /**
     * 合并商品标题和 SKU 文本，用于规则关键词匹配。
     *
     * @param productName 商品标题，允许为空
     * @param sku         SKU 文本，允许为空
     * @return 合并后的匹配文本
     */
    private String combinedText(String productName, String sku) {
        return safeText(productName) + " " + safeText(sku);
    }

    /**
     * 将 Double 转换为 BigDecimal，保留空值语义。
     *
     * @param value Double 数值，允许为空
     * @return BigDecimal 数值；输入为空时返回 null
     */
    private BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    /**
     * 读取必填文本参数并去除首尾空白。
     *
     * @param value   原始文本
     * @param message 参数缺失时的异常消息
     * @return 去除首尾空白后的文本
     * @throws IllegalArgumentException 当文本为空时抛出
     */
    private String requiredText(String value, String message) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /**
     * 将空白文本统一转换为 null。
     *
     * @param value 原始文本
     * @return 非空文本的 trim 结果；空白文本返回 null
     */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 将可空文本转换为安全匹配文本。
     *
     * @param value 原始文本，允许为空
     * @return 非空原文或空字符串
     */
    private String safeText(String value) {
        return value == null ? "" : value;
    }


    /**
     * 目标单位数量解析结果，作为规则回填内部判断对象，不暴露到接口协议。
     *
     * @param quantity  解析出的目标单位数量，无法解析时为空
     * @param unit      目标单位，通常来自规则 standardUnit
     * @param unitPrice 按 totalAmount / quantity 重算出的单价
     * @param source    数量来源，取值为 sku、product_name 或 original_record
     * @param confident 是否可自动用于回填
     * @param warnings  解析风险说明，无法自动回填时返回给调用方
     */
    private record TargetUnitQuantityParseResult(BigDecimal quantity,
                                                 String unit,
                                                 BigDecimal unitPrice,
                                                 String source,
                                                 boolean confident,
                                                 List<String> warnings) {
        private TargetUnitQuantityParseResult {
            warnings = warnings == null ? List.of() : warnings.stream().toList();
        }

        /**
         * 创建需要人工复核的目标数量解析结果。
         *
         * @param unit     目标规则标准单位
         * @param warnings 复核原因列表
         * @return 不可自动回填的解析结果
         */
        private static TargetUnitQuantityParseResult reviewRequired(String unit, List<String> warnings) {
            return new TargetUnitQuantityParseResult(null, unit, null, null, false, warnings);
        }
    }

    /**
     * 单段文本中的目标单位数量解析结果。
     *
     * @param quantity     文本中的唯一目标数量
     * @param source       文本来源，sku 或 product_name
     * @param conflicted   是否存在多个不同目标数量
     * @param warnings     冲突说明
     * @param packageCount 明确规格中的包装数量；无法可靠识别时为空
     */
    private record TargetTextQuantityParseResult(BigDecimal quantity,
                                                 String source,
                                                 boolean conflicted,
                                                 List<String> warnings,
                                                 BigDecimal packageCount) {
        private TargetTextQuantityParseResult {
            warnings = warnings == null ? List.of() : warnings.stream().toList();
        }

        /**
         * 创建未解析到目标数量的文本解析结果。
         *
         * @param source 文本来源，sku 或 product_name
         * @return 未找到目标数量且无额外说明的解析结果
         */
        private static TargetTextQuantityParseResult notFound(String source) {
            return new TargetTextQuantityParseResult(null, source, false, List.of(), null);
        }

        /**
         * 创建未解析到目标数量且带原因说明的文本解析结果。
         *
         * @param source   文本来源，sku 或 product_name
         * @param warnings 未解析到目标数量的原因说明
         * @return 未找到目标数量的解析结果
         */
        private static TargetTextQuantityParseResult notFound(String source, List<String> warnings) {
            return new TargetTextQuantityParseResult(null, source, false, warnings, null);
        }

        /**
         * 创建存在规格冲突的文本解析结果。
         *
         * @param source  文本来源，sku 或 product_name
         * @param warning 冲突原因说明
         * @return 需要人工复核的文本解析结果
         */
        private static TargetTextQuantityParseResult conflicted(String source, String warning) {
            return new TargetTextQuantityParseResult(null, source, true, List.of(warning), null);
        }
    }

    /**
     * 规格解析候选，保留换算结果、规范表达式、展示证据和包装数量。
     *
     * @param quantity     换算到目标单位后的数量
     * @param expression   用于冲突说明的规范规格表达式
     * @param evidence     用于成功结果 warnings 的规格解析证据
     * @param packageCount 规格中的包装数量；直接总规格没有包装数量时为空
     */
    private record ParsedQuantityCandidate(BigDecimal quantity,
                                           String expression,
                                           String evidence,
                                           BigDecimal packageCount) {
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

        /**
         * 按单条回填状态累加统计数量。
         *
         * @param item 单条规则回填结果
         */
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

        /**
         * 返回 dry-run 下可自动回填的记录数量。
         *
         * @return 可自动回填记录数量
         */
        private int applicableCount() {
            return applicableCount;
        }

        /**
         * 返回需要人工复核的记录数量。
         *
         * @return 需要复核记录数量
         */
        private int reviewRequiredCount() {
            return reviewRequiredCount;
        }

        /**
         * 返回实际写库更新成功的记录数量。
         *
         * @return 实际更新记录数量
         */
        private int updatedCount() {
            return updatedCount;
        }

        /**
         * 返回因安全边界跳过的记录数量。
         *
         * @return 跳过记录数量
         */
        private int skippedCount() {
            return skippedCount;
        }
    }
}
