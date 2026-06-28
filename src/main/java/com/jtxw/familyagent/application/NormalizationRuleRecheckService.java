package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.NormalizationLibraryOperationCommand;
import com.jtxw.familyagent.domain.model.NormalizationApplyRuleRecordSnapshot;
import com.jtxw.familyagent.domain.model.NormalizationRecheckRuleRecordsItem;
import com.jtxw.familyagent.domain.model.NormalizationRecheckRuleRecordsResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.ProductRuleMatchResult;
import com.jtxw.familyagent.domain.policy.ProductRuleMatcher;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationRuleRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @Author: jtxw
 * @Date: 2026/06/25 19:13:41
 * @Description: 归一化规则历史样本重算应用服务，负责按当前规则库重新清洗历史 include 样本的规则归属
 */
@Service
public class NormalizationRuleRecheckService {
    /**
     * 历史样本重算 action 名称。
     */
    private static final String ACTION_RECHECK_RULE_RECORDS = "recheck_rule_records";
    /**
     * dry-run 命中当前规则排除词时返回的单条状态。
     */
    private static final String STATUS_WOULD_RESET = "would_reset";
    /**
     * 实际从当前规则解绑成功时返回的单条状态。
     */
    private static final String STATUS_RESET = "reset";
    /**
     * dry-run 命中当前规则时返回的单条状态。
     */
    private static final String STATUS_WOULD_NORMALIZE = "would_normalize";
    /**
     * 实际重新归一化成功时返回的单条状态。
     */
    private static final String STATUS_NORMALIZED = "normalized";
    /**
     * 命中当前规则但无法安全自动归一化时返回的单条状态。
     */
    private static final String STATUS_REVIEW_REQUIRED = "review_required";
    /**
     * 命中规则但因重复、非唯一或并发更新等安全边界跳过时返回的单条状态。
     */
    private static final String STATUS_SKIPPED = "skipped";
    /**
     * 纳入价格基准线的统计决策。
     */
    private static final String DECISION_INCLUDE = "include";
    /**
     * 旧规则回退标记，表示记录等待后续明确规则重新匹配。
     */
    private static final String RULE_LEGACY_FALLBACK = "legacy_fallback";
    /**
     * 默认最大候选记录数，单位为条。
     */
    private static final int DEFAULT_LIMIT = 100;
    /**
     * 最大候选记录数上限，单位为条。
     */
    private static final int MAX_LIMIT = 500;

    /**
     * 数据库初始化组件，确保本地 SQLite 表结构已存在。
     */
    private final DatabaseInitializer databaseInitializer;
    /**
     * 归一化规则仓储，负责读取规则主表和启用关键词。
     */
    private final NormalizationRuleRepository normalizationRuleRepository;
    /**
     * 购买记录仓储，负责受限候选查询和统计字段更新。
     */
    private final PurchaseRecordRepository purchaseRecordRepository;
    /**
     * 商品规则匹配器，负责按当前启用规则优先级和 include / exclude 组合重新匹配历史样本。
     */
    private final ProductRuleMatcher productRuleMatcher;

    /**
     * 创建归一化规则历史样本重算应用服务。
     *
     * @param databaseInitializer         数据库初始化组件
     * @param normalizationRuleRepository 归一化规则仓储
     * @param purchaseRecordRepository    购买记录仓储
     * @param productRuleMatcher          商品规则匹配器
     */
    public NormalizationRuleRecheckService(DatabaseInitializer databaseInitializer,
                                           NormalizationRuleRepository normalizationRuleRepository,
                                           PurchaseRecordRepository purchaseRecordRepository,
                                           ProductRuleMatcher productRuleMatcher) {
        this.databaseInitializer = databaseInitializer;
        this.normalizationRuleRepository = normalizationRuleRepository;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.productRuleMatcher = productRuleMatcher;
    }

    /**
     * 按当前规则库重算历史 include 样本。
     *
     * <p>第一阶段把当前规则下命中当前排除词的记录解绑回 legacy_fallback；
     * 第二阶段通过完整 ProductRuleMatcher 重新匹配 include 样本，只有最终命中当前规则时才重新认领。</p>
     *
     * @param command 归一化规则库统一操作命令，action 应为 recheck_rule_records
     * @return 历史样本重算预览或执行结果
     */
    @Transactional(rollbackFor = Exception.class)
    public NormalizationRecheckRuleRecordsResult recheck(NormalizationLibraryOperationCommand command) {
        RecheckRequest request = normalizeRequest(command);
        databaseInitializer.initialize();
        NormalizationRuleRepository.NormalizationRuleRow ruleRow = normalizationRuleRepository
                .findRuleByCode(request.ruleCode())
                .orElseThrow(() -> new IllegalArgumentException("归一化规则不存在：" + request.ruleCode()));
        if (!ruleRow.enabled()) {
            throw new IllegalArgumentException("归一化规则未启用：" + request.ruleCode());
        }
        ProductRule targetRule = enabledRule(request.ruleCode());
        List<PurchaseRecord> resetCandidates = purchaseRecordRepository.listCurrentRuleIncludeRecords(
                request.ruleCode(), targetRule.normalizedName(), request.batchId(), request.owner(), request.limit());
        List<PurchaseRecord> normalizeCandidates = purchaseRecordRepository.listIncludeRecordsForRuleRecheck(
                request.batchId(), request.owner(), request.limit());
        List<String> taskWarnings = taskWarnings(request);
        RecheckCounters counters = new RecheckCounters();
        List<NormalizationRecheckRuleRecordsItem> items = new ArrayList<>();
        Set<Long> resetRecordIds = new HashSet<>();
        for (PurchaseRecord record : resetCandidates) {
            NormalizationRecheckRuleRecordsItem item = evaluateResetCandidate(record, targetRule, request.dryRun());
            if (item == null) {
                continue;
            }
            resetRecordIds.add(record.id());
            counters.accept(item);
            items.add(item);
        }
        for (PurchaseRecord record : normalizeCandidates) {
            if (resetRecordIds.contains(record.id())) {
                continue;
            }
            NormalizationRecheckRuleRecordsItem item = evaluateNormalizeCandidate(record, targetRule, request.dryRun());
            if (item == null) {
                continue;
            }
            counters.accept(item);
            items.add(item);
        }
        int candidateCount = resetCandidates.size() + normalizeCandidates.size();
        return new NormalizationRecheckRuleRecordsResult(ACTION_RECHECK_RULE_RECORDS, true,
                request.ruleCode(), targetRule.normalizedName(), request.dryRun(), candidateCount,
                counters.matchedCount(), counters.resetCount(), counters.resetCount(), counters.normalizedCount(),
                counters.reviewRequiredCount(), counters.updatedCount(), counters.skippedCount(), taskWarnings, items);
    }

    /**
     * 判断当前规则下的历史样本是否应从该规则解绑。
     *
     * @param record     候选购买记录，不允许为空
     * @param targetRule 当前启用规则，不允许为空
     * @param dryRun     是否只预览不写库
     * @return 解绑预览或执行结果；未命中排除词时返回 null
     */
    private NormalizationRecheckRuleRecordsItem evaluateResetCandidate(PurchaseRecord record,
                                                                       ProductRule targetRule,
                                                                       boolean dryRun) {
        String text = combinedText(record.productName(), record.sku());
        String matchedExcludeKeyword = firstMatchedKeyword(targetRule.excludeKeywords(), text);
        if (matchedExcludeKeyword == null) {
            return null;
        }
        NormalizationApplyRuleRecordSnapshot before = snapshot(record);
        NormalizationApplyRuleRecordSnapshot after = new NormalizationApplyRuleRecordSnapshot(
                record.productName(), decimal(record.quantity()), record.unit(), decimal(record.unitPrice()),
                DECISION_INCLUDE, RULE_LEGACY_FALLBACK);
        if (isDuplicateOrNotUnique(record)) {
            return new NormalizationRecheckRuleRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_SKIPPED, matchedExcludeKeyword, before, null,
                    List.of("记录为重复订单或 dedupe_status 非 unique，跳过从当前规则解绑"));
        }
        List<String> warnings = List.of("命中当前规则排除关键词：" + matchedExcludeKeyword + "，预览从当前规则解绑");
        if (dryRun) {
            return new NormalizationRecheckRuleRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_WOULD_RESET, matchedExcludeKeyword, before, after, warnings);
        }
        int updatedCount = purchaseRecordRepository.resetNormalizationAfterRuleRecheck(record.id(),
                record.productName(), RULE_LEGACY_FALLBACK, DECISION_INCLUDE);
        if (updatedCount == 0) {
            return new NormalizationRecheckRuleRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_SKIPPED, matchedExcludeKeyword, before, null,
                    List.of("购买记录不存在或已被并发修改，跳过从当前规则解绑"));
        }
        return new NormalizationRecheckRuleRecordsItem(record.id(), record.productName(), record.sku(),
                STATUS_RESET, matchedExcludeKeyword, before, after,
                List.of("命中当前规则排除关键词：" + matchedExcludeKeyword + "，已从当前规则解绑"));
    }

    /**
     * 判断 include 样本是否应重新归一化到当前规则。
     *
     * @param record     include 候选购买记录，不允许为空
     * @param targetRule 当前启用规则，不允许为空
     * @param dryRun     是否只预览不写库
     * @return 重新归一化预览、执行结果或待复核结果；未命中当前规则时返回 null
     */
    private NormalizationRecheckRuleRecordsItem evaluateNormalizeCandidate(PurchaseRecord record,
                                                                           ProductRule targetRule,
                                                                           boolean dryRun) {
        if (isDuplicateOrNotUnique(record)) {
            return skippedIfCurrentRuleMatched(record, targetRule);
        }
        if (record.productName() == null || record.productName().isBlank()) {
            return null;
        }
        if (targetRule.normalizedName().equals(record.normalizedName())
                && targetRule.id().equals(record.normalizationRule())) {
            return null;
        }
        String text = combinedText(record.productName(), record.sku());
        ProductRuleMatchResult matchResult = productRuleMatcher.match(text);
        if (!matchResult.matched() || !targetRule.id().equals(matchResult.ruleId())) {
            return null;
        }
        NormalizationApplyRuleRecordSnapshot before = snapshot(record);
        NormalizationApplyRuleRecordSnapshot after = new NormalizationApplyRuleRecordSnapshot(
                targetRule.normalizedName(), decimal(record.quantity()), record.unit(), decimal(record.unitPrice()),
                DECISION_INCLUDE, targetRule.id());
        if (!targetRule.standardUnit().equals(record.unit())) {
            return new NormalizationRecheckRuleRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_REVIEW_REQUIRED, null, before, null,
                    List.of("命中当前规则，但无法可靠换算到标准单位：" + targetRule.standardUnit() + "，需人工复核"));
        }
        if (dryRun) {
            return new NormalizationRecheckRuleRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_WOULD_NORMALIZE, null, before, after,
                    List.of("命中当前规则关键词，预览归一化为：" + targetRule.normalizedName()));
        }
        int updatedCount = purchaseRecordRepository.updateNormalizationRuleAfterRecheck(record.id(),
                targetRule.normalizedName(), targetRule.id());
        if (updatedCount == 0) {
            return new NormalizationRecheckRuleRecordsItem(record.id(), record.productName(), record.sku(),
                    STATUS_SKIPPED, null, before, null,
                    List.of("购买记录不存在或已被并发修改，跳过重新归一化"));
        }
        return new NormalizationRecheckRuleRecordsItem(record.id(), record.productName(), record.sku(),
                STATUS_NORMALIZED, null, before, after,
                List.of("命中当前规则关键词，已归一化为：" + targetRule.normalizedName()));
    }

    /**
     * 仅当重复或非唯一记录最终命中当前规则时，返回跳过明细。
     *
     * @param record     include 候选购买记录
     * @param targetRule 当前目标规则
     * @return 跳过明细；未命中当前规则时返回 null
     */
    private NormalizationRecheckRuleRecordsItem skippedIfCurrentRuleMatched(PurchaseRecord record,
                                                                            ProductRule targetRule) {
        if (record.productName() == null || record.productName().isBlank()) {
            return null;
        }
        ProductRuleMatchResult matchResult = productRuleMatcher.match(combinedText(record.productName(), record.sku()));
        if (!matchResult.matched() || !targetRule.id().equals(matchResult.ruleId())) {
            return null;
        }
        return new NormalizationRecheckRuleRecordsItem(record.id(), record.productName(), record.sku(),
                STATUS_SKIPPED, null, snapshot(record), null,
                List.of("记录为重复订单或 dedupe_status 非 unique，跳过重新归一化"));
    }

    /**
     * 查询指定编码对应的当前启用规则。
     *
     * @param ruleCode 规则编码，不允许为空
     * @return 当前启用的商品归一化规则
     */
    private ProductRule enabledRule(String ruleCode) {
        return normalizationRuleRepository.listEnabledProductRules().stream()
                .filter(rule -> ruleCode.equals(rule.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("归一化规则未启用：" + ruleCode));
    }

    /**
     * 规整重算请求参数并设置默认安全边界。
     *
     * @param command 归一化规则库统一操作命令
     * @return 内部重算请求对象
     */
    private RecheckRequest normalizeRequest(NormalizationLibraryOperationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("归一化规则历史样本重算请求不能为空");
        }
        String ruleCode = requiredText(command.ruleCode(), "ruleCode 不能为空");
        String owner = blankToNull(command.owner());
        int limit = command.limit() == null ? DEFAULT_LIMIT : command.limit();
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        limit = Math.min(limit, MAX_LIMIT);
        return new RecheckRequest(ruleCode, command.batchId(), owner,
                command.dryRun() == null || command.dryRun(), limit,
                command.onlyLegacyFallback() != null || command.onlyExcluded() != null);
    }

    /**
     * 构造任务级警告。
     *
     * @param request 内部重算请求对象
     * @return 任务级警告列表
     */
    private List<String> taskWarnings(RecheckRequest request) {
        List<String> warnings = new ArrayList<>();
        if (request.dryRun()) {
            warnings.add("dryRun=true，仅返回预览，不写入 purchase_records");
        }
        warnings.add("recheck_rule_records 会先解绑命中当前规则排除词的 include 样本，再用完整规则匹配器重新认领命中当前规则的 include 样本。");
        if (request.ignoredApplyFilters()) {
            warnings.add("recheck_rule_records 不使用 onlyLegacyFallback / onlyExcluded；本操作默认检查当前规则下的 include 样本。");
        }
        return warnings;
    }

    /**
     * 构造购买记录当前归一化统计字段快照。
     *
     * @param record 购买记录
     * @return 重算前快照
     */
    private NormalizationApplyRuleRecordSnapshot snapshot(PurchaseRecord record) {
        return new NormalizationApplyRuleRecordSnapshot(record.normalizedName(), decimal(record.quantity()),
                record.unit(), decimal(record.unitPrice()), record.decision(), record.normalizationRule());
    }

    /**
     * 判断购买记录是否为重复或非唯一记录。
     *
     * @param record 当前购买记录
     * @return true 表示不允许自动重算归一化字段
     */
    private boolean isDuplicateOrNotUnique(PurchaseRecord record) {
        return record.duplicate() || !"unique".equalsIgnoreCase(safeText(record.dedupeStatus()).trim());
    }

    /**
     * 返回文本中第一个命中的关键词。
     *
     * @param keywords 待匹配关键词列表
     * @param text     商品标题和 SKU 合并文本
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
     * 合并商品标题和 SKU 文本用于当前规则排除词匹配。
     *
     * @param productName 商品标题，允许为空
     * @param sku         SKU 文本，允许为空
     * @return 合并后的安全匹配文本
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
     * 历史样本重算请求参数。
     *
     * @param ruleCode            规则编码
     * @param batchId             导入批次筛选；为空时不按批次筛选
     * @param owner               归属人筛选；为空时不按归属人筛选
     * @param dryRun              是否只预览不写库
     * @param limit               最大候选数量
     * @param ignoredApplyFilters 请求中是否传入了本 action 不使用的 apply 过滤参数
     */
    private record RecheckRequest(String ruleCode,
                                  Long batchId,
                                  String owner,
                                  boolean dryRun,
                                  int limit,
                                  boolean ignoredApplyFilters) {
    }

    /**
     * 历史样本重算计数器。
     */
    private static class RecheckCounters {
        /**
         * 实际需要处理的记录数，不含 skipped。
         */
        private int matchedCount;
        /**
         * 从当前规则解绑的记录数。
         */
        private int resetCount;
        /**
         * 重新归一化到当前规则的记录数。
         */
        private int normalizedCount;
        /**
         * 需要人工复核的记录数。
         */
        private int reviewRequiredCount;
        /**
         * 实际更新成功的记录数。
         */
        private int updatedCount;
        /**
         * 命中规则但被跳过的记录数。
         */
        private int skippedCount;

        /**
         * 按单条处理状态累加计数。
         *
         * @param item 单条重算结果
         */
        private void accept(NormalizationRecheckRuleRecordsItem item) {
            switch (item.status()) {
                case STATUS_WOULD_RESET, STATUS_RESET -> {
                    matchedCount++;
                    resetCount++;
                    if (STATUS_RESET.equals(item.status())) {
                        updatedCount++;
                    }
                }
                case STATUS_WOULD_NORMALIZE, STATUS_NORMALIZED -> {
                    matchedCount++;
                    normalizedCount++;
                    if (STATUS_NORMALIZED.equals(item.status())) {
                        updatedCount++;
                    }
                }
                case STATUS_REVIEW_REQUIRED -> {
                    matchedCount++;
                    reviewRequiredCount++;
                }
                case STATUS_SKIPPED -> skippedCount++;
                default -> throw new IllegalStateException("未知历史样本重算状态：" + item.status());
            }
        }

        /**
         * 返回命中排除词且未跳过的记录数。
         *
         * @return 命中数量
         */
        private int matchedCount() {
            return matchedCount;
        }

        /**
         * 返回从当前规则解绑的记录数量。
         *
         * @return 解绑数量
         */
        private int resetCount() {
            return resetCount;
        }

        /**
         * 返回重新归一化到当前规则的记录数量。
         *
         * @return 重新归一化数量
         */
        private int normalizedCount() {
            return normalizedCount;
        }

        /**
         * 返回需要人工复核的记录数量。
         *
         * @return 待复核数量
         */
        private int reviewRequiredCount() {
            return reviewRequiredCount;
        }

        /**
         * 返回实际更新成功数量。
         *
         * @return 更新成功数量
         */
        private int updatedCount() {
            return updatedCount;
        }

        /**
         * 返回跳过数量。
         *
         * @return 跳过数量
         */
        private int skippedCount() {
            return skippedCount;
        }
    }
}
