package com.jtxw.familyagent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorResult;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorRequest;
import com.jtxw.familyagent.domain.model.NormalizationAnalyzeProgress;
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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
     * 非正向商品类型；若与 NORMALIZE 同时出现，说明 LLM 输出自相矛盾，只能进入人工复核。
     */
    private static final Set<String> NON_POSITIVE_PRODUCT_TYPES = Set.of(
            "DURABLE", "NON_REPURCHASE", "COUPON_OR_DEPOSIT", "UNKNOWN");
    /**
     * reasonCode 明确表达需要复核的集合，只用于安全降级，不用于反推商品分类。
     */
    private static final Set<String> REVIEW_REQUIRED_REASON_CODES = Set.of(
            "FOOD_REVIEW", "COLOR_COSMETIC_REVIEW", "UNKNOWN_REVIEW",
            "REAL_PRODUCT_WITH_DEPOSIT", "UNIT_UNSAFE", "CAT_SOUP_AMBIGUOUS");
    /**
     * 模型解释中表达不确定或需要人工确认的短语。
     */
    private static final List<String> REVIEW_TEXT_KEYWORDS = List.of(
            "需要人工复核", "人工复核", "需复核", "需确认", "需审核", "不确定", "模糊", "待确认");
    /**
     * 模型误把 unitFamily 写入 targetUnit 时会出现的枚举值。
     */
    private static final Set<String> UNIT_FAMILY_VALUES = Set.of("PIECE", "WEIGHT", "VOLUME", "COUNT", "UNKNOWN");
    /**
     * 宠物食品不适合直接批量确认的件数或包装单位，规格差异会明显污染价格基准。
     */
    private static final Set<String> PET_FOOD_REVIEW_UNITS = Set.of(
            "罐", "包", "盒", "杯", "袋", "个", "件", "片", "条", "对");
    /**
     * LLM 输出 targetUnit 时不应优先使用的包装单位；如果标题或 SKU 已有规格值，应转人工复核。
     */
    private static final Set<String> PACKAGE_TARGET_UNITS = Set.of("瓶", "盒", "包", "罐", "袋", "件", "个");
    /**
     * 普通食品关键词；第一阶段不自动进入本地价格基准，避免个人场景差异导致误确认。
     */
    private static final List<String> ORDINARY_FOOD_KEYWORDS = List.of(
            "面包", "吐司", "糕点", "月饼", "蛋糕", "饼干", "零食", "点心", "伴手礼", "礼盒");
    /**
     * 试吃、尝鲜和赠品类关键词；真实商品命中时只允许人工复核。
     */
    private static final List<String> SAMPLE_OR_GIFT_KEYWORDS = List.of(
            "试吃", "试用", "尝鲜", "U先", "U选", "小样", "体验装", "会员试用", "赠品", "赠1", "加赠", "抢先加赠");
    /**
     * 宠物食品标准名称集合，用于判断重量/体积 targetUnit 是否需要商品标题或 SKU 中有规格证据。
     */
    private static final Set<String> PET_FOOD_NORMALIZED_NAMES = Set.of(
            "猫主食罐", "猫罐头", "猫零食", "猫条", "猫汤包", "猫粮", "主食冻干", "猫冻干", "猫咪零食");
    /**
     * 宠物食品关键词，用于避免普通食品安全降级误伤猫零食等宠物消耗品。
     */
    private static final List<String> PET_FOOD_KEYWORDS = List.of(
            "猫主食罐", "主食罐", "猫罐头", "湿粮", "餐盒", "猫条", "猫汤包", "猫零食", "猫咪零食",
            "猫粮", "主食冻干", "猫冻干", "冻干");
    /**
     * 汤类、补水类猫食品语义容易在主食罐、零食罐和补水零食之间摇摆，不能直接归入猫主食罐价格基准。
     */
    private static final List<String> CAT_FOOD_SOUP_AMBIGUOUS_KEYWORDS = List.of(
            "汤包", "汤罐", "补水汤", "奶昔", "嘘嘘汤", "猫汤", "鲜鸡汤", "补水");
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
     * SKU 或商品名中的体积规格正则，用于宠物食品 targetUnit 证据校验。
     */
    private static final Pattern VOLUME_UNIT_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:ml|ML|Ml|mL|L|l|毫升|升)");
    /**
     * SKU 或商品名中的数量包装单位正则，用于 targetUnit 兜底推断。
     */
    private static final Pattern COUNT_UNIT_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(罐|包|盒|杯)");
    /**
     * LLM debug dump 文件名时间戳格式。
     */
    private static final DateTimeFormatter DEBUG_FILE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

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
        return analyzeBatch(batchId, limit, forceReanalyze, includeKeywords, excludeKeywords, onlyFailed, null);
    }

    /**
     * 按关键词筛选后分析指定导入批次内的 legacy_fallback 商品，并通过监听器回传异步任务进度。
     *
     * <p>该重载只增加进度通知能力，不改变 normalization_suggestions 的生成、失败重试、
     * batch-apply 或 review_items 写入规则；监听器异常会被记录并忽略，避免进度写库失败中断主分析流程。</p>
     *
     * @param batchId          导入批次 ID
     * @param limit            最大分析候选数，小于等于 0 时默认 100
     * @param forceReanalyze   是否忽略同批次已有成功状态 suggestion 后重新分析
     * @param includeKeywords  包含关键词，命中商品名或 SKU 任一字段才进入候选；为空时不过滤
     * @param excludeKeywords  排除关键词，命中商品名或 SKU 任一字段时排除；为空时不过滤
     * @param onlyFailed       是否只重试已有 failed suggestion 对应的候选
     * @param progressListener 分析进度监听器，允许为空；为空时不回传进度
     * @return 批次分析统计结果
     */
    public NormalizationAnalyzeResult analyzeBatch(long batchId,
                                                   int limit,
                                                   boolean forceReanalyze,
                                                   List<String> includeKeywords,
                                                   List<String> excludeKeywords,
                                                   boolean onlyFailed,
                                                   NormalizationAnalyzeProgressListener progressListener) {
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
        notifyProgress(progressListener, new NormalizationAnalyzeProgress(batchId, candidates.size(),
                analyzedCount, autoExcludedCount, pendingBatchApprovalCount, pendingReviewCount, failedCount,
                0, totalBatches));

        for (int start = 0; start < limitedCandidates.size(); start += batchSize) {
            List<Candidate> batchCandidates = limitedCandidates.subList(start, Math.min(start + batchSize, limitedCandidates.size()));
            int batchIndex = start / batchSize + 1;
            long batchStartNanos = System.nanoTime();
            List<NormalizationAdvisorRequest> advisorRequests = batchCandidates.stream()
                    .map(Candidate::toAdvisorRequest)
                    .toList();
            NormalizationLlmAdvisor.LlmRequestMetrics requestMetrics = requestMetrics(advisorRequests);
            NormalizationProperties.Llm llm = normalizationProperties.getLlm();
            LOGGER.info("Normalization LLM batch start: batchId={}, batchIndex={}/{}, batchSize={}, model={}, "
                            + "baseUrlHost={}, timeoutSeconds={}, promptChars={}, requestBytes={}, aliasKeys={}",
                    batchId, batchIndex, totalBatches, batchCandidates.size(), llm.getModel(),
                    baseUrlHost(llm.getBaseUrl()), llm.getRequestTimeoutSeconds(),
                    requestMetrics.promptChars(), requestMetrics.requestBytes(), aliasKeySummary(batchCandidates));
            List<NormalizationAdvisorResult> advisorResults;
            NormalizationLlmAdvisor.LlmBatchObservation observation;
            try {
                NormalizationLlmAdvisor.LlmBatchAnalysis analysis = llmAdvisor.analyzeBatchWithObservation(advisorRequests);
                advisorResults = analysis.results();
                observation = analysis.observation();
            } catch (Exception e) {
                advisorResults = batchCandidates.stream()
                        .map(candidate -> failedResult(candidate, classifyException(e)))
                        .toList();
                observation = failedObservation(e, requestMetrics, batchStartNanos);
            }
            int batchFailedCount = 0;
            long saveStartNanos = System.nanoTime();
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
            long saveElapsedMs = elapsedMs(saveStartNanos);
            long elapsedMs = (System.nanoTime() - batchStartNanos) / 1_000_000;
            String batchStatus = batchFailedCount == 0 ? "success"
                    : batchFailedCount == batchCandidates.size() ? "failed" : "partial_failed";
            if (observation.errorType() != null && !observation.errorType().isBlank()) {
                LOGGER.info("Normalization LLM batch failed: batchId={}, batchIndex={}/{}, elapsedMs={}, "
                                + "errorType={}, message={}, httpStatus={}, contentType={}",
                        batchId, batchIndex, totalBatches, elapsedMs, observation.errorType(),
                        abbreviate(observation.errorMessage(), 200), observation.httpStatus(), observation.contentType());
            }
            LOGGER.info("Normalization LLM batch end: batchId={}, batchIndex={}/{}, elapsedMs={}, status={}, "
                            + "httpStatus={}, contentType={}, responseBytes={}, extractedContentChars={}, "
                            + "parsedItems={}, failedCount={}, saveElapsedMs={}, requestBuildElapsedMs={}, "
                            + "llmHttpElapsedMs={}, extractElapsedMs={}, parseElapsedMs={}, totalElapsedMs={}",
                    batchId, batchIndex, totalBatches, elapsedMs, batchStatus, observation.httpStatus(),
                    observation.contentType(), observation.responseBytes(), observation.extractedContentChars(),
                    observation.parsedItems(), batchFailedCount, saveElapsedMs, observation.requestBuildElapsedMs(),
                    observation.llmHttpElapsedMs(), observation.extractElapsedMs(), observation.parseElapsedMs(),
                    observation.totalElapsedMs());
            writeDebugDump(batchId, batchIndex, batchCandidates.size(), requestMetrics, observation,
                    elapsedMs, saveElapsedMs);
            notifyProgress(progressListener, new NormalizationAnalyzeProgress(batchId, candidates.size(),
                    analyzedCount, autoExcludedCount, pendingBatchApprovalCount, pendingReviewCount, failedCount,
                    batchIndex, totalBatches));
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
        String message = errorMessage(e);
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
        if (e instanceof IOException || lowerMessage.contains("i/o") || lowerMessage.contains("io error")) {
            return "io_error：" + sanitizeError(message);
        }
        return "unknown_error：" + sanitizeError(message);
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

    private NormalizationLlmAdvisor.LlmRequestMetrics requestMetrics(List<NormalizationAdvisorRequest> requests) {
        try {
            return llmAdvisor.requestMetrics(requests);
        } catch (Exception e) {
            LOGGER.warn("Normalization LLM request metrics build failed: errorType={}, message={}",
                    errorType(classifyException(e)), abbreviate(sanitizeError(errorMessage(e)), 200));
            return new NormalizationLlmAdvisor.LlmRequestMetrics(0, 0, null);
        }
    }

    private NormalizationLlmAdvisor.LlmBatchObservation failedObservation(Exception e,
                                                                         NormalizationLlmAdvisor.LlmRequestMetrics requestMetrics,
                                                                         long batchStartNanos) {
        return new NormalizationLlmAdvisor.LlmBatchObservation(
                requestMetrics.promptChars(),
                requestMetrics.requestBytes(),
                requestMetrics.requestBody(),
                0L,
                0L,
                0L,
                0L,
                elapsedMs(batchStartNanos),
                0,
                "",
                0,
                0,
                0,
                errorType(classifyException(e)),
                sanitizeError(errorMessage(e)),
                null,
                null
        );
    }

    private void writeDebugDump(long batchId,
                                int batchIndex,
                                int batchSize,
                                NormalizationLlmAdvisor.LlmRequestMetrics requestMetrics,
                                NormalizationLlmAdvisor.LlmBatchObservation observation,
                                long elapsedMs,
                                long saveElapsedMs) {
        NormalizationProperties.Llm llm = normalizationProperties.getLlm();
        if (!llm.isDebugLogEnabled()) {
            return;
        }
        try {
            Path debugDir = Path.of(llm.getDebugLogDir());
            Files.createDirectories(debugDir);
            String timestamp = LocalDateTime.now().format(DEBUG_FILE_TIMESTAMP_FORMAT);
            Path debugFile = debugDir.resolve("normalization-batch-" + batchId + "-" + batchIndex
                    + "-" + timestamp + ".json");
            Map<String, Object> dump = new LinkedHashMap<>();
            dump.put("batchId", batchId);
            dump.put("batchIndex", batchIndex);
            dump.put("batchSize", batchSize);
            dump.put("model", llm.getModel());
            dump.put("baseUrlHost", baseUrlHost(llm.getBaseUrl()));
            dump.put("timeoutSeconds", llm.getRequestTimeoutSeconds());
            dump.put("promptChars", requestMetrics.promptChars());
            dump.put("requestBytes", requestMetrics.requestBytes());
            dump.put("startedAt", ClockUtils.nowText());
            dump.put("finishedAt", ClockUtils.nowText());
            dump.put("elapsedMs", elapsedMs);
            dump.put("httpStatus", observation.httpStatus());
            dump.put("contentType", observation.contentType());
            dump.put("responseBytes", observation.responseBytes());
            dump.put("extractedContentChars", observation.extractedContentChars());
            dump.put("parsedItems", observation.parsedItems());
            dump.put("errorType", observation.errorType());
            dump.put("errorMessage", observation.errorMessage());
            dump.put("requestBuildElapsedMs", observation.requestBuildElapsedMs());
            dump.put("llmHttpElapsedMs", observation.llmHttpElapsedMs());
            dump.put("extractElapsedMs", observation.extractElapsedMs());
            dump.put("parseElapsedMs", observation.parseElapsedMs());
            dump.put("saveElapsedMs", saveElapsedMs);
            dump.put("totalElapsedMs", observation.totalElapsedMs() > 0 ? observation.totalElapsedMs() : elapsedMs);
            dump.put("request", debugRequest(llm, observation));
            dump.put("response", debugResponse(llm, observation));
            dump.put("extractedContent", llm.isDebugLogFullResponse()
                    ? truncatedText(observation.extractedContent(), llm.getDebugMaxResponseChars()).text()
                    : null);
            Files.writeString(debugFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(dump));
        } catch (Exception e) {
            LOGGER.warn("Normalization LLM debug dump write failed: errorType={}, message={}",
                    errorType(classifyException(e)), abbreviate(sanitizeError(errorMessage(e)), 200));
        }
    }

    private Map<String, Object> debugRequest(NormalizationProperties.Llm llm,
                                             NormalizationLlmAdvisor.LlmBatchObservation observation) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("body", llm.isDebugLogFullPrompt() ? observation.requestBody() : null);
        return request;
    }

    private Map<String, Object> debugResponse(NormalizationProperties.Llm llm,
                                              NormalizationLlmAdvisor.LlmBatchObservation observation) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (!llm.isDebugLogFullResponse()) {
            response.put("body", null);
            response.put("truncated", false);
            return response;
        }
        TruncatedText truncatedResponse = truncatedText(observation.responseBody(), llm.getDebugMaxResponseChars());
        response.put("body", truncatedResponse.text());
        response.put("truncated", truncatedResponse.truncated());
        return response;
    }

    private TruncatedText truncatedText(String text, int maxChars) {
        if (text == null) {
            return new TruncatedText(null, false);
        }
        int safeMaxChars = Math.max(0, maxChars);
        if (text.length() <= safeMaxChars) {
            return new TruncatedText(text, false);
        }
        return new TruncatedText(text.substring(0, safeMaxChars), true);
    }

    private String baseUrlHost(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 向异步任务监听器发送当前分析进度。
     *
     * <p>进度写库属于旁路观测能力，不能影响商品归一化建议生成；
     * 因此监听器为空或监听器自身抛错时，本方法只跳过或记录告警。</p>
     *
     * @param progressListener 进度监听器，允许为空
     * @param progress         当前分析进度快照，不允许为空
     */
    private void notifyProgress(NormalizationAnalyzeProgressListener progressListener,
                                NormalizationAnalyzeProgress progress) {
        if (progressListener == null) {
            return;
        }
        try {
            progressListener.onProgress(progress);
        } catch (Exception e) {
            LOGGER.warn("Normalization analysis progress listener failed: errorType={}, message={}",
                    errorType(classifyException(e)), abbreviate(sanitizeError(errorMessage(e)), 200));
        }
    }

    private record TruncatedText(String text, boolean truncated) {
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
            reason = appendReason(reason, "真实商品含预售付定需复核");
        }
        SafetyGuardDecision guardDecision = applySafetyGuards(result, canonicalNormalizedName,
                targetUnitResult.targetUnit(), action, productType, reviewRequired, reason);
        action = guardDecision.action();
        productType = guardDecision.productType();
        reviewRequired = guardDecision.reviewRequired();
        reason = guardDecision.reason();
        String status = status(new NormalizationAdvisorResult(result.rawProductName(), result.sku(), action,
                result.suggestedNormalizedName(), result.rejectedNormalizedName(), productType, targetUnitResult.targetUnit(),
                result.unitFamily(), result.confidence(), reviewRequired, reason, result.evidence(), result.failed()));
        if (guardDecision.forcePendingReview() && !STATUS_FAILED.equals(status)) {
            status = STATUS_PENDING_REVIEW;
        }
        if (STATUS_PENDING_BATCH_APPROVAL.equals(status) && !targetUnitResult.batchApprovalSafe()) {
            status = STATUS_PENDING_REVIEW;
            reviewRequired = true;
            reason = appendReason(reason, "单位需复核");
        }
        if (!result.failed() && containsAnyText(reason, REVIEW_TEXT_KEYWORDS)) {
            status = STATUS_PENDING_REVIEW;
            reviewRequired = true;
            action = ACTION_REVIEW;
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

    /**
     * 对 LLM 输出做最终安全降级，避免自相矛盾或不适合批量确认的建议直接进入价格基准。
     *
     * @param result                  LLM 原始结构化建议
     * @param canonicalNormalizedName 归并后的系统标准品类名称
     * @param canonicalTargetUnit     清洗后的目标单位
     * @param action                  当前动作，可能已被预售定金规则调整
     * @param productType             当前商品类型，可能已被预售定金规则调整
     * @param reviewRequired          当前是否要求人工复核
     * @param reason                  当前 reason 文本
     * @return 安全降级后的动作、类型、复核标记和原因
     */
    private SafetyGuardDecision applySafetyGuards(NormalizationAdvisorResult result,
                                                  String canonicalNormalizedName,
                                                  String canonicalTargetUnit,
                                                  String action,
                                                  String productType,
                                                  boolean reviewRequired,
                                                  String reason) {
        // decision 贯穿所有 safety guard，统一承载最终是否需要人工复核和短 reason。
        SafetyGuardDecision decision = new SafetyGuardDecision(action, productType, reviewRequired, reason, false);
        if (result.failed()) {
            return decision;
        }
        decision = downgradeNormalizeTypeConflict(decision);
        decision = downgradeReviewReasonCode(result, decision);
        decision = downgradeReviewExplanation(result, decision);
        decision = downgradeInvalidTargetUnit(result, decision);
        decision = downgradeOrdinaryFood(result, canonicalNormalizedName, decision);
        decision = downgradeSampleOrGift(result, decision);
        decision = downgradeCatMainCanAmbiguousSoup(result, canonicalNormalizedName, decision);
        decision = downgradePackageUnitWhenSpecExists(result, canonicalTargetUnit, decision);
        decision = downgradePetFoodCountUnit(result, canonicalNormalizedName, canonicalTargetUnit, decision);
        return downgradePetFoodWithoutSpecEvidence(result, canonicalNormalizedName, canonicalTargetUnit, decision);
    }

    /**
     * 当 LLM 同时输出 NORMALIZE 和非正向商品类型时降级复核。
     *
     * @param decision 当前安全决策
     * @return 若命中类型动作冲突，则返回人工复核决策；否则返回原决策
     */
    private SafetyGuardDecision downgradeNormalizeTypeConflict(SafetyGuardDecision decision) {
        if (ACTION_NORMALIZE.equals(decision.action()) && NON_POSITIVE_PRODUCT_TYPES.contains(decision.productType())) {
            return decision.review("类型动作冲突");
        }
        return decision;
    }

    /**
     * 当 reasonCode 明确表达需要人工复核时降级复核。
     *
     * @param result   LLM 原始结构化建议
     * @param decision 当前安全决策
     * @return 若命中复核 reasonCode，则返回人工复核决策；否则返回原决策
     */
    private SafetyGuardDecision downgradeReviewReasonCode(NormalizationAdvisorResult result,
                                                          SafetyGuardDecision decision) {
        String reasonCode = safeText(result.reasonCode()).toUpperCase();
        if (!REVIEW_REQUIRED_REASON_CODES.contains(reasonCode)) {
            return decision;
        }
        if (ACTION_EXCLUDE.equals(decision.action()) && "COUPON_OR_DEPOSIT".equals(decision.productType())) {
            return decision.forceReview("reasonCode 需复核");
        }
        return decision.review("reasonCode 需复核");
    }

    /**
     * 当模型解释中已经表达不确定或需要复核时降级复核。
     *
     * @param result   LLM 原始结构化建议
     * @param decision 当前安全决策
     * @return 若解释文本命中复核语义，则返回人工复核决策；否则返回原决策
     */
    private SafetyGuardDecision downgradeReviewExplanation(NormalizationAdvisorResult result,
                                                           SafetyGuardDecision decision) {
        if (decision.reviewRequired()) {
            return decision;
        }
        String explanation = safeText(result.shortReason()) + " " + safeText(result.reason());
        if (containsAnyText(explanation, REVIEW_TEXT_KEYWORDS)) {
            return decision.review("模型解释需复核");
        }
        return decision;
    }

    /**
     * 当 LLM 把 unitFamily 枚举误写入 targetUnit 时降级复核。
     *
     * @param result   LLM 原始结构化建议
     * @param decision 当前安全决策
     * @return 若 targetUnit 是单位族枚举值，则返回人工复核决策；否则返回原决策
     */
    private SafetyGuardDecision downgradeInvalidTargetUnit(NormalizationAdvisorResult result,
                                                           SafetyGuardDecision decision) {
        String targetUnit = safeText(result.targetUnit()).toUpperCase();
        if (!targetUnit.isBlank() && UNIT_FAMILY_VALUES.contains(targetUnit)) {
            return decision.review("单位字段非法");
        }
        return decision;
    }

    /**
     * 普通食品场景先进入人工复核，避免把低频或礼品类食品误纳入长期复购价格基准。
     *
     * @param result                  LLM 原始结构化建议
     * @param canonicalNormalizedName 归并后的系统标准品类名称
     * @param decision                当前安全决策
     * @return 若命中普通食品风险，则返回人工复核决策；否则返回原决策
     */
    private SafetyGuardDecision downgradeOrdinaryFood(NormalizationAdvisorResult result,
                                                      String canonicalNormalizedName,
                                                      SafetyGuardDecision decision) {
        String text = safeText(result.rawProductName()) + " " + safeText(result.sku()) + " "
                + safeText(canonicalNormalizedName);
        if (isPetFood(canonicalNormalizedName, text)) {
            return decision;
        }
        if ("FOOD_REVIEW".equals(safeText(result.reasonCode()).toUpperCase())
                || containsAnyText(text, ORDINARY_FOOD_KEYWORDS)) {
            return decision.review("食品需复核");
        }
        return decision;
    }

    /**
     * 试吃、试用、赠品类真实商品只允许人工复核，纯券或纯权益仍保留自动排除。
     *
     * @param result   LLM 原始结构化建议
     * @param decision 当前安全决策
     * @return 若命中真实商品样品或赠品风险，则返回人工复核决策；否则返回原决策
     */
    private SafetyGuardDecision downgradeSampleOrGift(NormalizationAdvisorResult result,
                                                      SafetyGuardDecision decision) {
        String text = safeText(result.rawProductName()) + " " + safeText(result.sku());
        if (!containsAnyText(text, SAMPLE_OR_GIFT_KEYWORDS)) {
            return decision;
        }
        if (ACTION_EXCLUDE.equals(decision.action()) && "COUPON_OR_DEPOSIT".equals(decision.productType())) {
            return decision;
        }
        return decision.review("试吃样品需复核");
    }

    /**
     * 猫主食罐归一化命中汤类、补水类模糊词时降级复核。
     *
     * @param result                  LLM 原始结构化建议
     * @param canonicalNormalizedName 归并后的系统标准品类名称
     * @param decision                当前安全决策
     * @return 若猫主食罐命中汤类模糊词，则返回人工复核决策；否则返回原决策
     */
    private SafetyGuardDecision downgradeCatMainCanAmbiguousSoup(NormalizationAdvisorResult result,
                                                                 String canonicalNormalizedName,
                                                                 SafetyGuardDecision decision) {
        if (!"猫主食罐".equals(safeText(canonicalNormalizedName))) {
            return decision;
        }
        String text = safeText(result.rawProductName()) + " " + safeText(result.sku());
        if (containsAnyText(text, CAT_FOOD_SOUP_AMBIGUOUS_KEYWORDS)) {
            return decision.review("汤类猫食品需复核");
        }
        return decision;
    }

    /**
     * 标题或 SKU 已有明确规格值但 targetUnit 仍是包装单位时降级复核。
     *
     * @param result              LLM 原始结构化建议
     * @param canonicalTargetUnit 清洗后的目标单位
     * @param decision            当前安全决策
     * @return 若规格证据和目标单位不一致，则返回人工复核决策；否则返回原决策
     */
    private SafetyGuardDecision downgradePackageUnitWhenSpecExists(NormalizationAdvisorResult result,
                                                                   String canonicalTargetUnit,
                                                                   SafetyGuardDecision decision) {
        String rawText = safeText(result.rawProductName()) + " " + safeText(result.sku());
        if (!WEIGHT_UNIT_PATTERN.matcher(rawText).find() && !VOLUME_UNIT_PATTERN.matcher(rawText).find()) {
            return decision;
        }
        if (PACKAGE_TARGET_UNITS.contains(purePackageTargetUnit(canonicalTargetUnit))) {
            return decision.review("规格单位不一致需复核");
        }
        return decision;
    }

    /**
     * 宠物食品使用重量或体积单位时，必须能在标题或 SKU 中找到规格证据。
     *
     * @param result                  LLM 原始结构化建议
     * @param canonicalNormalizedName 归并后的系统标准品类名称
     * @param canonicalTargetUnit     清洗后的目标单位
     * @param decision                当前安全决策
     * @return 若缺少规格证据，则返回人工复核决策；否则返回原决策
     */
    private SafetyGuardDecision downgradePetFoodWithoutSpecEvidence(NormalizationAdvisorResult result,
                                                                    String canonicalNormalizedName,
                                                                    String canonicalTargetUnit,
                                                                    SafetyGuardDecision decision) {
        String rawText = safeText(result.rawProductName()) + " " + safeText(result.sku());
        if (!isPetFood(canonicalNormalizedName, rawText)) {
            return decision;
        }
        if (!isWeightOrVolumeUnit(canonicalTargetUnit)) {
            return decision;
        }
        if (WEIGHT_UNIT_PATTERN.matcher(rawText).find() || VOLUME_UNIT_PATTERN.matcher(rawText).find()) {
            return decision;
        }
        return decision.review("缺少规格证据");
    }

    /**
     * 宠物食品使用罐、袋、盒、条等件数或包装单位时降级复核。
     *
     * @param result                  LLM 原始结构化建议
     * @param canonicalNormalizedName 归并后的系统标准品类名称
     * @param canonicalTargetUnit     清洗后的目标单位
     * @param decision                当前安全决策
     * @return 若宠物食品目标单位是包装或件数单位，则返回人工复核决策；否则返回原决策
     */
    private SafetyGuardDecision downgradePetFoodCountUnit(NormalizationAdvisorResult result,
                                                          String canonicalNormalizedName,
                                                          String canonicalTargetUnit,
                                                          SafetyGuardDecision decision) {
        String rawText = safeText(result.rawProductName()) + " " + safeText(result.sku());
        if (!isPetFood(canonicalNormalizedName, rawText)) {
            return decision;
        }
        if (PET_FOOD_REVIEW_UNITS.contains(purePetFoodReviewUnit(canonicalTargetUnit))) {
            return decision.review("宠物食品单位需复核");
        }
        return decision;
    }

    /**
     * 判断当前建议是否属于宠物食品语义。
     *
     * @param canonicalNormalizedName 归并后的系统标准品类名称
     * @param text                    商品名、SKU 或其他上下文文本
     * @return 命中宠物食品标准名或关键词时返回 true
     */
    private boolean isPetFood(String canonicalNormalizedName, String text) {
        return PET_FOOD_NORMALIZED_NAMES.contains(safeText(canonicalNormalizedName))
                || containsAnyText(text, PET_FOOD_KEYWORDS);
    }

    /**
     * 判断 targetUnit 是否是重量或体积基准单位。
     *
     * @param targetUnit 清洗后的目标单位
     * @return g、kg、ml、L 返回 true
     */
    private boolean isWeightOrVolumeUnit(String targetUnit) {
        return List.of("g", "kg", "ml", "L").contains(safeText(targetUnit));
    }

    /**
     * 从宠物食品 targetUnit 中提取需要人工复核的包装或件数单位。
     *
     * @param targetUnit 清洗后的目标单位
     * @return 命中的包装或件数单位；未命中时返回原单位
     */
    private String purePetFoodReviewUnit(String targetUnit) {
        String unit = safeText(targetUnit);
        for (String reviewUnit : PET_FOOD_REVIEW_UNITS) {
            if (unit.contains(reviewUnit)) {
                return reviewUnit;
            }
        }
        return unit;
    }

    /**
     * 从 targetUnit 中提取包装单位，用于规格证据与目标单位一致性校验。
     *
     * @param targetUnit 清洗后的目标单位
     * @return 命中的包装单位；未命中时返回原单位
     */
    private String purePackageTargetUnit(String targetUnit) {
        String unit = safeText(targetUnit);
        for (String packageUnit : PACKAGE_TARGET_UNITS) {
            if (unit.contains(packageUnit)) {
                return packageUnit;
            }
        }
        return unit;
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

    /**
     * 安全降级过程中的中间决策。
     *
     * @param action             最终建议动作
     * @param productType        最终商品类型
     * @param reviewRequired     是否需要人工复核
     * @param reason             最终 reason 文本
     * @param forcePendingReview 是否强制落为 pending_review
     */
    private record SafetyGuardDecision(String action,
                                       String productType,
                                       boolean reviewRequired,
                                       String reason,
                                       boolean forcePendingReview) {
        private SafetyGuardDecision review(String extraReason) {
            return new SafetyGuardDecision(ACTION_REVIEW, productType, true, appendGuardReason(extraReason), true);
        }

        private SafetyGuardDecision forceReview(String extraReason) {
            return new SafetyGuardDecision(action, productType, true, appendGuardReason(extraReason), true);
        }

        private String appendGuardReason(String extraReason) {
            String baseReason = reason == null || reason.isBlank() ? "LLM 安全校验" : reason;
            if (extraReason == null || extraReason.isBlank() || baseReason.contains(extraReason)) {
                return baseReason;
            }
            return baseReason + "；" + extraReason;
        }
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
