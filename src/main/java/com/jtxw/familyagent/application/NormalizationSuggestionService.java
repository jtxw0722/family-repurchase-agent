package com.jtxw.familyagent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorResult;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorRequest;
import com.jtxw.familyagent.domain.model.NormalizationAnalyzeResult;
import com.jtxw.familyagent.domain.model.NormalizationBatchApplyResult;
import com.jtxw.familyagent.domain.model.NormalizationRagContext;
import com.jtxw.familyagent.domain.model.NormalizationSuggestion;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.ProductTitleCleaner;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationSuggestionRepository;
import com.jtxw.familyagent.infrastructure.persistence.ProductAliasRepository;
import com.jtxw.familyagent.infrastructure.persistence.ProductNegativeAliasRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 20:11:36
 * @Description: 商品归一化 LLM 建议应用服务，负责批次分析、建议审计和批量应用。
 */
@Service
public class NormalizationSuggestionService {
    /**
     * 日志组件，只记录批量大小、耗时和 aliasKey 摘要，不记录 Prompt、API Key 或商品全文。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(NormalizationSuggestionService.class);
    /**
     * 旧归一化兜底规则标识，只分析该规则留下的候选商品。
     */
    private static final String LEGACY_FALLBACK_RULE = "legacy_fallback";
    /**
     * 需要人工复核商品名称归一化时写入 review_items 的原因码。
     */
    private static final String NORMALIZATION_REVIEW_REASON = "PRODUCT_NAME_NORMALIZATION_REVIEW";
    /**
     * 高置信排除建议状态，表示无需人工复核即可排除。
     */
    private static final String STATUS_AUTO_EXCLUDED = "auto_excluded";
    /**
     * 高置信归一化建议状态，等待批量确认写入别名。
     */
    private static final String STATUS_PENDING_BATCH_APPROVAL = "pending_batch_approval";
    /**
     * 低置信或色号强相关建议状态，需要人工复核。
     */
    private static final String STATUS_PENDING_REVIEW = "pending_review";
    /**
     * 已应用归一化建议状态。
     */
    private static final String STATUS_APPROVED = "approved";
    /**
     * LLM 调用、解析或批次处理失败状态。
     */
    private static final String STATUS_FAILED = "failed";
    /**
     * LLM 建议归一化动作。
     */
    private static final String ACTION_NORMALIZE = "NORMALIZE";
    /**
     * LLM 建议排除动作。
     */
    private static final String ACTION_EXCLUDE = "EXCLUDE";
    /**
     * LLM 建议人工复核动作。
     */
    private static final String ACTION_REVIEW = "REVIEW";
    /**
     * 可进入复购价格基准的长期消耗品类型。
     */
    private static final String PRODUCT_TYPE_REPURCHASE_CONSUMABLE = "REPURCHASE_CONSUMABLE";
    /**
     * 预售、付定和定金类交易词；真实商品命中这些词时必须人工复核。
     */
    private static final List<String> PRESALE_DEPOSIT_KEYWORDS = List.of("预售", "预定", "定金", "付定", "锁定");
    /**
     * 已知明确复购品标准名称，用于防止 COUPON_OR_DEPOSIT 覆盖商品归一化结果。
     */
    private static final Set<String> REPURCHASE_NORMALIZED_NAMES = Set.of("猫主食罐", "猫条", "猫零食", "猫粮",
            "猫汤包", "美瞳", "精华液");
    /**
     * 真实商品本体关键词，用于识别“真实商品 + 预售/付定/定金”的人工复核场景。
     */
    private static final List<String> REPURCHASE_PRODUCT_KEYWORDS = List.of("猫主食罐", "主食罐", "猫罐头", "湿粮",
            "餐盒", "一餐一杯", "猫条", "猫汤包", "咕噜酱", "补水零食", "猫零食", "猫咪零食", "猫粮",
            "全价猫粮", "主粮", "干粮", "美瞳", "隐形眼镜", "日抛", "月抛", "精华液", "精华");
    /**
     * SKU 或商品名中的重量规格正则，用于 targetUnit 兜底推断。
     */
    private static final Pattern WEIGHT_UNIT_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:kg|KG|Kg|g|G|克|千克)");
    /**
     * SKU 或商品名中的数量包装单位正则，用于 targetUnit 兜底推断。
     */
    private static final Pattern COUNT_UNIT_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(罐|包|盒|杯)");

    /**
     * 数据库初始化器，用于确保建议表和复核表结构可用。
     */
    private final DatabaseInitializer databaseInitializer;
    /**
     * 订单记录仓储，用于读取 legacy_fallback 候选商品。
     */
    private final PurchaseRecordRepository purchaseRecordRepository;
    /**
     * 复核项仓储，用于低置信建议创建人工复核项。
     */
    private final ReviewItemRepository reviewItemRepository;
    /**
     * 商品标题清洗器，用于生成稳定 aliasKey。
     */
    private final ProductTitleCleaner productTitleCleaner;
    /**
     * 正向别名仓储，批量应用时只写入 product_aliases。
     */
    private final ProductAliasRepository productAliasRepository;
    /**
     * 负向别名仓储，用于跳过已确认排除的候选。
     */
    private final ProductNegativeAliasRepository productNegativeAliasRepository;
    /**
     * LLM 归一化建议仓储，一条候选商品对应一条建议记录。
     */
    private final NormalizationSuggestionRepository normalizationSuggestionRepository;
    /**
     * 本地轻量 RAG 上下文检索器，用于给 LLM 提供别名、负例和品类提示。
     */
    private final NormalizationRagContextRetriever ragContextRetriever;
    /**
     * LLM Advisor，负责批量生成结构化归一化建议。
     */
    private final NormalizationLlmAdvisor llmAdvisor;
    /**
     * LLM 建议名称归并器，防止自由文本直接成为最终 normalizedName。
     */
    private final SuggestedNormalizedNameCanonicalizer suggestedNormalizedNameCanonicalizer;
    /**
     * LLM 建议目标单位归并和批量确认安全校验器。
     */
    private final SuggestedTargetUnitCanonicalizer suggestedTargetUnitCanonicalizer;
    /**
     * 归一化配置，包含 LLM 开关、批量大小、阈值和兜底复核模式。
     */
    private final NormalizationProperties normalizationProperties;
    /**
     * JSON 序列化组件，用于保存 RAG evidence 快照。
     */
    private final ObjectMapper objectMapper;

    public NormalizationSuggestionService(DatabaseInitializer databaseInitializer,
                                          PurchaseRecordRepository purchaseRecordRepository,
                                          ReviewItemRepository reviewItemRepository,
                                          ProductTitleCleaner productTitleCleaner,
                                          ProductAliasRepository productAliasRepository,
                                          ProductNegativeAliasRepository productNegativeAliasRepository,
                                          NormalizationSuggestionRepository normalizationSuggestionRepository,
                                          NormalizationRagContextRetriever ragContextRetriever,
                                          NormalizationLlmAdvisor llmAdvisor,
                                          SuggestedNormalizedNameCanonicalizer suggestedNormalizedNameCanonicalizer,
                                          SuggestedTargetUnitCanonicalizer suggestedTargetUnitCanonicalizer,
                                          NormalizationProperties normalizationProperties,
                                          ObjectMapper objectMapper) {
        this.databaseInitializer = databaseInitializer;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.productTitleCleaner = productTitleCleaner;
        this.productAliasRepository = productAliasRepository;
        this.productNegativeAliasRepository = productNegativeAliasRepository;
        this.normalizationSuggestionRepository = normalizationSuggestionRepository;
        this.ragContextRetriever = ragContextRetriever;
        this.llmAdvisor = llmAdvisor;
        this.suggestedNormalizedNameCanonicalizer = suggestedNormalizedNameCanonicalizer;
        this.suggestedTargetUnitCanonicalizer = suggestedTargetUnitCanonicalizer;
        this.normalizationProperties = normalizationProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 分析指定导入批次内的 legacy_fallback 商品。
     *
     * <p>该方法只生成 normalization_suggestions 审计记录，最多为低置信或失败样本创建复核项；
     * 不会把 purchase_records 改为 include，也不会直接写 product_aliases。</p>
     *
     * @param batchId         导入批次 ID
     * @param limit           最大分析候选数，小于等于 0 时默认 100
     * @param forceReanalyze  是否忽略同批次已有 suggestion 后重新分析
     * @return 批次分析统计结果
     */
    public NormalizationAnalyzeResult analyzeBatch(long batchId, int limit, boolean forceReanalyze) {
        return analyzeBatch(batchId, limit, forceReanalyze, List.of(), List.of(), false);
    }

    /**
     * 按关键词筛选后分析指定导入批次内的 legacy_fallback 商品。
     *
     * <p>关键词筛选只用于缩小候选集合，不参与 action、productType 或 normalizedName 的判断；
     * 后续可新增 normalization-candidates 预览接口，用于在调用 LLM 前查看候选列表。</p>
     *
     * @param batchId         导入批次 ID
     * @param limit           最大分析候选数，小于等于 0 时默认 100
     * @param forceReanalyze  是否忽略同批次已有成功状态 suggestion 后重新分析
     * @param includeKeywords 包含关键词，命中商品名或 SKU 任一字段才进入候选；为空时不过滤
     * @param excludeKeywords 排除关键词，命中商品名或 SKU 任一字段时排除；为空时不过滤
     * @param onlyFailed      是否只重试已有 failed suggestion 对应的候选
     * @return 批次分析统计结果
     */
    public NormalizationAnalyzeResult analyzeBatch(long batchId,
                                                   int limit,
                                                   boolean forceReanalyze,
                                                   List<String> includeKeywords,
                                                   List<String> excludeKeywords,
                                                   boolean onlyFailed) {
        databaseInitializer.initialize();
        if (!normalizationProperties.getLlm().isEnabled()) {
            throw new IllegalStateException("LLM normalization advisor 未启用");
        }

        CandidateFilter filter = new CandidateFilter(normalizedKeywords(includeKeywords),
                normalizedKeywords(excludeKeywords), onlyFailed);
        List<Candidate> candidates = candidates(batchId, forceReanalyze, filter);
        int analyzeLimit = limit <= 0 ? 100 : limit;
        List<Candidate> limitedCandidates = candidates.stream()
                .limit(analyzeLimit)
                .toList();
        int batchSize = resolvedBatchSize();
        int analyzedCount = 0;
        int autoExcludedCount = 0;
        int pendingBatchApprovalCount = 0;
        int pendingReviewCount = 0;
        int failedCount = 0;
        Map<String, Integer> failureTypeCounts = new LinkedHashMap<>();
        int totalBatches = limitedCandidates.isEmpty() ? 0
                : (limitedCandidates.size() + batchSize - 1) / batchSize;

        for (int start = 0; start < limitedCandidates.size(); start += batchSize) {
            List<Candidate> batchCandidates = limitedCandidates.subList(start, Math.min(start + batchSize, limitedCandidates.size()));
            int batchIndex = start / batchSize + 1;
            long batchStartNanos = System.nanoTime();
            LOGGER.info("Normalization LLM batch start: batchId={}, batchIndex={}/{}, batchSize={}, aliasKeys={}",
                    batchId, batchIndex, totalBatches, batchCandidates.size(), aliasKeySummary(batchCandidates));
            List<NormalizationAdvisorResult> advisorResults;
            try {
                advisorResults = llmAdvisor.analyzeBatch(batchCandidates.stream()
                        .map(Candidate::toAdvisorRequest)
                        .toList());
            } catch (Exception e) {
                advisorResults = batchCandidates.stream()
                        .map(candidate -> failedResult(candidate, classifyException(e)))
                        .toList();
            }
            int batchFailedCount = 0;
            for (int index = 0; index < batchCandidates.size(); index++) {
                Candidate candidate = batchCandidates.get(index);
                NormalizationAdvisorResult advisorResult = advisorResults.get(index);
                PreparedSuggestion preparedSuggestion = toSuggestion(batchId, candidate.aliasKey(), advisorResult);
                String status = preparedSuggestion.status();
                NormalizationSuggestion suggestion = preparedSuggestion.suggestion();
                saveOrReplaceFailed(suggestion);
                analyzedCount++;

                if (STATUS_AUTO_EXCLUDED.equals(status)) {
                    autoExcludedCount++;
                } else if (STATUS_PENDING_BATCH_APPROVAL.equals(status)) {
                    pendingBatchApprovalCount++;
                } else if (STATUS_FAILED.equals(status)) {
                    failedCount++;
                    batchFailedCount++;
                    failureTypeCounts.merge(errorType(suggestion.reason()), 1, Integer::sum);
                    createNormalizationReview(candidate.record(), suggestion.reason());
                } else {
                    pendingReviewCount++;
                    createNormalizationReview(candidate.record(), suggestion.reason());
                }
            }
            long elapsedMs = (System.nanoTime() - batchStartNanos) / 1_000_000;
            String batchStatus = batchFailedCount == 0 ? "success"
                    : batchFailedCount == batchCandidates.size() ? "failed" : "partial_failed";
            LOGGER.info("Normalization LLM batch end: batchId={}, batchIndex={}/{}, elapsedMs={}, status={}, failedCount={}",
                    batchId, batchIndex, totalBatches, elapsedMs, batchStatus, batchFailedCount);
        }
        String message = analyzeMessage(candidates.size(), analyzedCount, failureTypeCounts);
        return new NormalizationAnalyzeResult(batchId, candidates.size(), analyzedCount, autoExcludedCount,
                pendingBatchApprovalCount, pendingReviewCount, failedCount, message);
    }

    /**
     * 查询指定批次的归一化建议。
     *
     * @param batchId 导入批次 ID
     * @return 建议列表
     */
    public List<NormalizationSuggestion> listByBatchId(long batchId) {
        databaseInitializer.initialize();
        return normalizationSuggestionRepository.listByBatchId(batchId);
    }

    /**
     * 批量确认高置信 NORMALIZE 建议并写入 product_aliases。
     *
     * <p>该操作只沉淀正向别名并更新 suggestion 状态，不修改历史 purchase_records.decision，
     * 后续同类商品会通过 product_aliases 的确定性命中进入常规导入链路。</p>
     *
     * @param batchId       导入批次 ID
     * @param action        批量动作，当前仅支持 approve_normalize
     * @param minConfidence 最低置信度阈值
     * @param onlyStatus    建议状态筛选，默认 pending_batch_approval
     * @return 批量应用结果
     */
    public NormalizationBatchApplyResult batchApply(long batchId, String action, double minConfidence, String onlyStatus) {
        databaseInitializer.initialize();
        if (!"approve_normalize".equalsIgnoreCase(action)) {
            throw new IllegalArgumentException("当前仅支持 approve_normalize");
        }
        String status = onlyStatus == null || onlyStatus.isBlank() ? STATUS_PENDING_BATCH_APPROVAL : onlyStatus.trim();
        List<NormalizationSuggestion> suggestions = normalizationSuggestionRepository.listByBatchIdAndStatus(batchId, status)
                .stream()
                .filter(suggestion -> ACTION_NORMALIZE.equals(suggestion.action()))
                .filter(suggestion -> PRODUCT_TYPE_REPURCHASE_CONSUMABLE.equals(suggestion.productType()))
                .filter(suggestion -> suggestion.confidence() >= minConfidence)
                .filter(suggestion -> suggestion.suggestedNormalizedName() != null
                        && !suggestion.suggestedNormalizedName().isBlank())
                .toList();
        int appliedCount = 0;
        for (NormalizationSuggestion suggestion : suggestions) {
            String canonicalNormalizedName = suggestedNormalizedNameCanonicalizer.canonicalize(
                    suggestion.rawProductName(), suggestion.sku(), suggestion.suggestedNormalizedName());
            SuggestedTargetUnitCanonicalizer.TargetUnitSafetyResult targetUnitResult =
                    suggestedTargetUnitCanonicalizer.canonicalize(canonicalNormalizedName, suggestion.targetUnit());
            if (!targetUnitResult.batchApprovalSafe()) {
                normalizationSuggestionRepository.updateStatusAndReason(suggestion.id(), STATUS_PENDING_REVIEW,
                        appendReason(suggestion.reason(), "targetUnit 不适合批量确认：" + targetUnitResult.unsafeReason()));
                continue;
            }
            productAliasRepository.upsert(suggestion.rawProductName(), suggestion.aliasKey(),
                    canonicalNormalizedName, targetUnitResult.targetUnit(), null);
            normalizationSuggestionRepository.updateStatus(suggestion.id(), STATUS_APPROVED);
            appliedCount++;
        }
        return new NormalizationBatchApplyResult(batchId, suggestions.size(), appliedCount,
                "批量应用完成：写入 product_aliases " + appliedCount + " 条；purchase_records.decision 未修改。");
    }

    private List<Candidate> candidates(long batchId, boolean forceReanalyze, CandidateFilter filter) {
        Map<String, PurchaseRecord> uniqueRecords = new LinkedHashMap<>();
        for (PurchaseRecord record : purchaseRecordRepository.listByBatchId(batchId)) {
            if (!isLegacyFallback(record)) {
                continue;
            }
            String aliasKey = productTitleCleaner.aliasKey(record.productName(), record.sku());
            if (aliasKey.isBlank() || hasConfirmedAlias(aliasKey)) {
                continue;
            }
            if (!matchesCandidateFilter(record, aliasKey, batchId, filter)) {
                continue;
            }
            if (!forceReanalyze && normalizationSuggestionRepository.existsNonFailedByBatchIdAndAliasKey(batchId, aliasKey)) {
                continue;
            }
            // TODO 后续若需要严格回放审计，forceReanalyze=true 时应先将旧 suggestion 标记为 replaced/superseded。
            // 当前第一版允许同一 batch + alias_key 存在多条建议，避免为收尾修复扩展状态机。
            uniqueRecords.putIfAbsent(aliasKey, record);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, PurchaseRecord> entry : uniqueRecords.entrySet()) {
            PurchaseRecord record = entry.getValue();
            NormalizationRagContext context = ragContextRetriever.retrieve(
                    record.productName(), record.sku(), record.category(), record.subCategory());
            candidates.add(new Candidate(entry.getKey(), record, context));
        }
        return candidates;
    }

    private boolean matchesCandidateFilter(PurchaseRecord record,
                                           String aliasKey,
                                           long batchId,
                                           CandidateFilter filter) {
        if (filter == null) {
            return true;
        }
        if (filter.onlyFailed()
                && !normalizationSuggestionRepository.existsFailedByBatchIdAndAliasKey(batchId, aliasKey)) {
            return false;
        }
        String searchableText = (safeText(record.productName()) + " " + safeText(record.sku())).toLowerCase();
        if (!filter.includeKeywords().isEmpty() && !containsAnyKeyword(searchableText, filter.includeKeywords())) {
            return false;
        }
        return filter.excludeKeywords().isEmpty() || !containsAnyKeyword(searchableText, filter.excludeKeywords());
    }

    private List<String> normalizedKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        return keywords.stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .map(keyword -> keyword.trim().toLowerCase())
                .distinct()
                .toList();
    }

    private boolean containsAnyKeyword(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int resolvedBatchSize() {
        int configuredBatchSize = normalizationProperties.getLlm().getBatchSize();
        return Math.max(1, Math.min(configuredBatchSize, 20));
    }

    private boolean isLegacyFallback(PurchaseRecord record) {
        if (LEGACY_FALLBACK_RULE.equals(record.normalizationRule())) {
            return true;
        }
        // 兼容旧库中没有 normalization_rule 的批次：legacy_fallback 当前表现为原名归一化且默认 exclude。
        return record.normalizationRule() == null
                && "exclude".equals(record.decision())
                && safeText(record.normalizedName()).equals(safeText(record.productName()));
    }

    private boolean hasConfirmedAlias(String aliasKey) {
        return productAliasRepository.findByAliasKey(aliasKey).isPresent()
                || productNegativeAliasRepository.findByAliasKey(aliasKey).isPresent();
    }

    private void saveOrReplaceFailed(NormalizationSuggestion suggestion) {
        int replacedCount = normalizationSuggestionRepository.replaceLatestFailed(suggestion);
        if (replacedCount == 0) {
            normalizationSuggestionRepository.save(suggestion);
        }
    }

    private NormalizationAdvisorResult failedResult(Candidate candidate, String reason) {
        PurchaseRecord record = candidate.record();
        return new NormalizationAdvisorResult(record.productName(), record.sku(), "REVIEW", null, null,
                "UNKNOWN", null, "UNKNOWN", 0.5D, true, reason, List.of(reason), true);
    }

    private String classifyException(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lowerMessage = message.toLowerCase();
        if (lowerMessage.contains("read timed out") || lowerMessage.contains("timed out")
                || lowerMessage.contains("timeout")) {
            return "timeout_error：" + sanitizeError(message);
        }
        if (lowerMessage.contains("http 响应异常") || lowerMessage.contains("http")) {
            return "http_error：" + sanitizeError(message);
        }
        if (lowerMessage.contains("json")) {
            return "json_parse_error：" + sanitizeError(message);
        }
        if (lowerMessage.contains("空响应")) {
            return "empty_response：" + sanitizeError(message);
        }
        return "llm_error：" + sanitizeError(message);
    }

    private String sanitizeError(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer ***");
    }

    private String errorType(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown_error";
        }
        int splitIndex = reason.indexOf('：');
        if (splitIndex <= 0) {
            return "unknown_error";
        }
        return reason.substring(0, splitIndex);
    }

    private String analyzeMessage(int candidateCount, int analyzedCount, Map<String, Integer> failureTypeCounts) {
        String message = "归一化建议分析完成：候选 " + candidateCount + " 条，分析 " + analyzedCount + " 条。";
        if (failureTypeCounts.isEmpty()) {
            return message;
        }
        List<String> details = failureTypeCounts.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue() + " 条")
                .toList();
        return message + " 失败类型：" + String.join("，", details) + "。";
    }

    private String aliasKeySummary(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        List<String> summaries = candidates.stream()
                .limit(5)
                .map(candidate -> abbreviate(candidate.aliasKey(), 12))
                .toList();
        int remainingCount = candidates.size() - summaries.size();
        if (remainingCount <= 0) {
            return String.join(",", summaries);
        }
        return String.join(",", summaries) + ",+" + remainingCount;
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    private String status(NormalizationAdvisorResult result) {
        if (result.failed()) {
            return STATUS_FAILED;
        }
        NormalizationProperties.Llm llm = normalizationProperties.getLlm();
        if (ACTION_EXCLUDE.equals(result.action())
                && "COUPON_OR_DEPOSIT".equals(result.productType())
                && !result.reviewRequired()) {
            return STATUS_AUTO_EXCLUDED;
        }
        if (ACTION_EXCLUDE.equals(result.action())
                && List.of("NON_REPURCHASE", "DURABLE", "COUPON_OR_DEPOSIT").contains(result.productType())
                && result.confidence() >= llm.getExcludeConfidenceThreshold()
                && !result.reviewRequired()) {
            return STATUS_AUTO_EXCLUDED;
        }
        if (ACTION_NORMALIZE.equals(result.action())
                && PRODUCT_TYPE_REPURCHASE_CONSUMABLE.equals(result.productType())
                && result.confidence() >= llm.getNormalizeConfidenceThreshold()) {
            return STATUS_PENDING_BATCH_APPROVAL;
        }
        return STATUS_PENDING_REVIEW;
    }

    private PreparedSuggestion toSuggestion(long batchId,
                                            String aliasKey,
                                            NormalizationAdvisorResult result) {
        String canonicalNormalizedName = suggestedNormalizedNameCanonicalizer.canonicalize(
                result.rawProductName(), result.sku(), result.suggestedNormalizedName());
        SuggestedTargetUnitCanonicalizer.TargetUnitSafetyResult targetUnitResult =
                suggestedTargetUnitCanonicalizer.canonicalize(canonicalNormalizedName, result.targetUnit());
        String action = result.action();
        String productType = result.productType();
        String reason = result.reason();
        boolean reviewRequired = result.reviewRequired();
        if (!result.failed() && requiresPresaleDepositReview(result.rawProductName(), result.sku(), canonicalNormalizedName)) {
            action = ACTION_REVIEW;
            productType = PRODUCT_TYPE_REPURCHASE_CONSUMABLE;
            reviewRequired = true;
            reason = appendReason(reason, "商品本体是复购品，但标题含预售/付定/定金，需人工确认是否为定金订单，不能静默进入价格基准");
        }
        String status = status(new NormalizationAdvisorResult(result.rawProductName(), result.sku(), action,
                result.suggestedNormalizedName(), result.rejectedNormalizedName(), productType, result.targetUnit(),
                result.unitFamily(), result.confidence(), reviewRequired, reason, result.evidence(), result.failed()));
        if (STATUS_PENDING_BATCH_APPROVAL.equals(status) && !targetUnitResult.batchApprovalSafe()) {
            status = STATUS_PENDING_REVIEW;
            reviewRequired = true;
            reason = appendReason(reason, targetUnitResult.unsafeReason());
        }
        NormalizationSuggestion suggestion = new NormalizationSuggestion(
                null,
                batchId,
                result.rawProductName(),
                result.sku(),
                aliasKey,
                action,
                canonicalNormalizedName,
                result.rejectedNormalizedName(),
                productType,
                targetUnitResult.targetUnit(),
                result.unitFamily(),
                result.confidence(),
                reviewRequired,
                reason,
                evidenceJson(result.evidence()),
                normalizationProperties.getLlm().getProvider(),
                normalizationProperties.getLlm().getModel(),
                normalizationProperties.getLlm().getPromptVersion(),
                status,
                ClockUtils.nowText(),
                null
        );
        return new PreparedSuggestion(suggestion, status);
    }

    private boolean requiresPresaleDepositReview(String productName, String sku, String canonicalNormalizedName) {
        String rawText = safeText(productName) + " " + safeText(sku);
        if (!containsAnyText(rawText, PRESALE_DEPOSIT_KEYWORDS)) {
            return false;
        }
        if (REPURCHASE_NORMALIZED_NAMES.contains(safeText(canonicalNormalizedName))) {
            return true;
        }
        return containsAnyText(rawText, REPURCHASE_PRODUCT_KEYWORDS);
    }

    private String appendReason(String originalReason, String extraReason) {
        String baseReason = originalReason == null || originalReason.isBlank() ? "targetUnit 安全校验" : originalReason;
        if (extraReason == null || extraReason.isBlank() || baseReason.contains(extraReason)) {
            return baseReason;
        }
        return baseReason + "；" + extraReason;
    }

    private void createNormalizationReview(PurchaseRecord record, String reason) {
        if (record.id() == null || reviewItemRepository.existsPendingByRecordIdAndReasonCode(record.id(), NORMALIZATION_REVIEW_REASON)) {
            return;
        }
        // 只有低置信、NEW_CATEGORY、REVIEW 或失败样本回到人工复核，避免高置信排除品制造噪音。
        reviewItemRepository.create(record.id(), NORMALIZATION_REVIEW_REASON,
                "LLM 归一化建议需要人工复核：" + reason);
    }

    private String evidenceJson(List<String> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence == null ? List.of() : evidence);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean containsAnyText(String text, List<String> keywords) {
        String safeText = safeText(text);
        for (String keyword : keywords) {
            if (safeText.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private record Candidate(String aliasKey, PurchaseRecord record, NormalizationRagContext context) {
        private NormalizationAdvisorRequest toAdvisorRequest() {
            return new NormalizationAdvisorRequest(
                    record.productName(),
                    record.sku(),
                    record.category(),
                    record.subCategory(),
                    context
            );
        }
    }

    private record CandidateFilter(List<String> includeKeywords,
                                   List<String> excludeKeywords,
                                   boolean onlyFailed) {
    }

    private record PreparedSuggestion(NormalizationSuggestion suggestion, String status) {
    }
}
