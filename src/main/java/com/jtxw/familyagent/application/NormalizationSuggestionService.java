package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.AnalyzeNormalizationCommand;
import com.jtxw.familyagent.application.command.BatchApplyNormalizationCommand;
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
     * 日志组件，只记录批量大小、耗时和候选去重 key 摘要，不记录 Prompt、API Key 或商品全文。
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
     * 高置信归一化建议状态，等待批量确认 suggestion。
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
     * 自动排除只读查询的默认最低置信度阈值，低于该阈值的 EXCLUDE 记录仍不进入展示结果。
     */
    private static final double DEFAULT_AUTO_EXCLUDED_MIN_CONFIDENCE = 0.9D;
    /**
     * 可进入复购价格基准的长期消耗品类型。
     */
    private static final String PRODUCT_TYPE_REPURCHASE_CONSUMABLE = "REPURCHASE_CONSUMABLE";
    /**
     * 耐用品类型，不进入复购消耗品价格基准。
     */
    private static final String PRODUCT_TYPE_DURABLE = "DURABLE";
    /**
     * 非复购商品类型，不进入复购消耗品价格基准。
     */
    private static final String PRODUCT_TYPE_NON_REPURCHASE = "NON_REPURCHASE";
    /**
     * 券、定金、尾款或虚拟权益类型，不进入复购消耗品价格基准。
     */
    private static final String PRODUCT_TYPE_COUPON_OR_DEPOSIT = "COUPON_OR_DEPOSIT";
    /**
     * 非正向商品类型；若与 NORMALIZE 同时出现，说明 LLM 输出自相矛盾，只能进入人工复核。
     */
    private static final Set<String> NON_POSITIVE_PRODUCT_TYPES = Set.of(
            PRODUCT_TYPE_DURABLE, PRODUCT_TYPE_NON_REPURCHASE, PRODUCT_TYPE_COUPON_OR_DEPOSIT, "UNKNOWN");
    /**
     * 已可自动排除的商品类型，后续普通人工复核降级规则不得覆盖。
     */
    private static final Set<String> AUTO_EXCLUDED_PRODUCT_TYPES = Set.of(
            PRODUCT_TYPE_DURABLE, PRODUCT_TYPE_NON_REPURCHASE, PRODUCT_TYPE_COUPON_OR_DEPOSIT);
    /**
     * 自动排除统计展示顺序，保证 typeCounts 在 REST 响应和测试中具备确定性。
     */
    private static final List<String> AUTO_EXCLUDED_PRODUCT_TYPE_ORDER = List.of(
            PRODUCT_TYPE_DURABLE, PRODUCT_TYPE_COUPON_OR_DEPOSIT, PRODUCT_TYPE_NON_REPURCHASE);
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
     * 服饰耐用品关键词；命中后不允许进入 pending_batch_approval。
     */
    private static final List<String> CLOTHING_DURABLE_KEYWORDS = List.of(
            "衣", "裤", "裙", "袜", "鞋", "靴", "拖鞋", "背心", "T恤", "短袖", "长袖", "衬衫",
            "外套", "毛衣", "开衫", "内裤", "文胸", "泳衣", "泳裤", "潜水服", "防晒衣", "防晒衬衫",
            "防晒服", "速干衣", "球衣", "运动裤", "吊带", "连衣裙", "套装", "雨衣", "雨披");
    /**
     * 防晒服饰关键词，用于区别防晒霜、防晒乳等个人护理消耗品。
     */
    private static final List<String> SUNSCREEN_DURABLE_KEYWORDS = List.of(
            "防晒衣", "防晒衬衫", "防晒服", "防晒速干衣", "防晒外套", "防晒罩衫", "潜水服", "水母服", "冲浪服");
    /**
     * 防晒个人护理消耗品关键词，命中时不按服饰耐用品处理。
     */
    private static final List<String> SUNSCREEN_CONSUMABLE_KEYWORDS = List.of(
            "防晒霜", "防晒乳", "防晒喷雾", "防晒啫喱", "防晒液");
    /**
     * 纯权益、券、定金和尾款关键词；无真实商品本体时自动排除。
     */
    private static final List<String> COUPON_DEPOSIT_RIGHT_KEYWORDS = List.of(
            "预售", "付定", "定金", "锁定", "0.01", "尾款", "加赠礼", "预定礼", "权益",
            "券", "优惠券", "代金券");
    /**
     * 酒店、住宿和旅行服务关键词。
     */
    private static final List<String> TRAVEL_SERVICE_KEYWORDS = List.of(
            "酒店", "住宿", "大床房", "高级大床房", "门票", "机票", "火车票", "旅行", "服务");
    /**
     * 咖啡类复购饮品强关键词；该类商品具备复购属性，但品类和规格差异较大，需人工确认归一化结果。
     */
    private static final List<String> COFFEE_BEVERAGE_STRONG_KEYWORDS = List.of(
            "咖啡", "咖啡粉", "咖啡液", "咖啡豆", "冻干咖啡", "挂耳咖啡", "胶囊咖啡", "即饮咖啡",
            "速溶咖啡", "黑咖啡");
    /**
     * 咖啡类复购饮品弱关键词；只有与强咖啡上下文共同出现时才允许触发咖啡复核。
     */
    private static final List<String> COFFEE_BEVERAGE_WEAK_KEYWORDS = List.of(
            "美式", "拿铁", "Americano", "Latte", "浓缩液");
    /**
     * 颜色语义中的咖啡词，清理后再做咖啡饮品判断，避免服饰颜色误触发。
     */
    private static final List<String> COFFEE_COLOR_EXCLUDE_KEYWORDS = List.of(
            "咖啡色", "深咖啡", "浅咖啡", "咖啡棕");
    /**
     * 咖啡相关耐用品关键词；命中时不能按咖啡饮品规则处理。
     */
    private static final List<String> COFFEE_DURABLE_KEYWORDS = List.of(
            "咖啡杯", "咖啡机", "咖啡壶", "磨豆机", "咖啡滤纸架", "滤纸架", "咖啡勺", "咖啡器具");
    /**
     * 建议标准名中不允许出现的包装形态或销售形态词，避免把规格层级沉淀为标准品类。
     */
    private static final List<String> UNSAFE_NORMALIZED_NAME_PACKAGING_KEYWORDS = List.of(
            "整箱", "囤货装", "家庭装", "组合装", "套装", "大包装", "小包装", "分享装", "体验装",
            "试用装", "试吃装", "尝鲜装", "旅行装", "补充装", "替换装", "加量装", "优惠装", "礼盒装");
    /**
     * 建议标准名中不允许出现的促销、交易和销售形态词。
     */
    private static final List<String> UNSAFE_NORMALIZED_NAME_PROMOTION_KEYWORDS = List.of(
            "双11", "618", "优惠", "优惠价", "秒杀", "预售", "付定", "定金", "尾款", "锁定",
            "加赠", "赠品", "买一送一", "直播间", "囤货");
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
     * 宠物商品域关键词，用于区分真实宠物商品与咖啡冻干等跨域误判。
     */
    private static final List<String> PET_PRODUCT_DOMAIN_KEYWORDS = List.of("猫", "犬", "狗", "宠物");
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
     * 可能进入价格基准或需要确认归一化粒度的复购消耗品关键词，命中时不能按高置信 DURABLE 直接自动排除。
     */
    private static final List<String> PROTECTED_REPURCHASE_CANDIDATE_KEYWORDS = List.of(
            "猫食品", "猫主食罐", "主食罐", "猫罐头", "湿粮", "猫条", "猫汤包", "猫零食", "猫咪零食",
            "猫粮", "猫砂", "纸巾", "抽纸", "卫生巾", "洗衣液", "洗衣凝珠", "防晒霜",
            "防晒乳", "防晒喷雾", "精华液", "精华", "美瞳", "隐形眼镜", "日抛", "月抛");
    /**
     * 宠物场景中的耐用品关键词，虽然包含猫砂、猫等复购候选词，但不应被复购保护规则拦截。
     */
    private static final List<String> PET_DURABLE_KEYWORDS = List.of("猫砂盆", "猫砂铲", "猫厕所", "猫窝", "猫抓板");
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
     * 建议标准名中的规格数量特征，例如 268ml、15瓶、3000抽。
     */
    private static final Pattern UNSAFE_NORMALIZED_NAME_SPEC_PATTERN = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*(?:g|kg|ml|l|瓶|罐|包|袋|盒|片|抽|件|只|条|支|枚|颗)",
            Pattern.CASE_INSENSITIVE);
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
     * 商品标题清洗器，用于生成 normalization_suggestions 候选去重 key。
     */
    private final ProductTitleCleaner productTitleCleaner;
    /**
     * LLM 归一化建议仓储，一条候选商品对应一条建议记录。
     */
    private final NormalizationSuggestionRepository normalizationSuggestionRepository;
    /**
     * 本地轻量 RAG 上下文检索器，用于给 LLM 提供规则证据和品类提示。
     */
    private final NormalizationRagContextRetriever ragContextRetriever;
    /**
     * 商品归一化建议能力，负责批量生成结构化归一化建议。
     */
    private final ProductNormalizationAdvisor productNormalizationAdvisor;
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
     * JSON 序列化组件，用于保存 LLM 建议证据快照。
     */
    private final ObjectMapper objectMapper;

    public NormalizationSuggestionService(DatabaseInitializer databaseInitializer,
                                          PurchaseRecordRepository purchaseRecordRepository,
                                          ReviewItemRepository reviewItemRepository,
                                          ProductTitleCleaner productTitleCleaner,
                                          NormalizationSuggestionRepository normalizationSuggestionRepository,
                                          NormalizationRagContextRetriever ragContextRetriever,
                                          ProductNormalizationAdvisor productNormalizationAdvisor,
                                          SuggestedNormalizedNameCanonicalizer suggestedNormalizedNameCanonicalizer,
                                          SuggestedTargetUnitCanonicalizer suggestedTargetUnitCanonicalizer,
                                          NormalizationProperties normalizationProperties,
                                          ObjectMapper objectMapper) {
        this.databaseInitializer = databaseInitializer;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.productTitleCleaner = productTitleCleaner;
        this.normalizationSuggestionRepository = normalizationSuggestionRepository;
        this.ragContextRetriever = ragContextRetriever;
        this.productNormalizationAdvisor = productNormalizationAdvisor;
        this.suggestedNormalizedNameCanonicalizer = suggestedNormalizedNameCanonicalizer;
        this.suggestedTargetUnitCanonicalizer = suggestedTargetUnitCanonicalizer;
        this.normalizationProperties = normalizationProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 分析指定导入批次内的 legacy_fallback 商品。
     *
     * <p>该方法只生成 normalization_suggestions 审计记录，最多为低置信或失败样本创建复核项；
     * 不会把 purchase_records 改为 include，也不会维护归一化规则库。</p>
     *
     * @param batchId         导入批次 ID
     * @param limit           最大分析候选数，小于等于 0 时默认 100
     * @param forceReanalyze  是否忽略同批次已有 suggestion 后重新分析
     * @return 批次分析统计结果
     */
    public NormalizationAnalyzeResult analyzeBatch(long batchId, int limit, boolean forceReanalyze) {
        return analyzeBatch(new AnalyzeNormalizationCommand(batchId, limit, forceReanalyze, List.of(), List.of(), false));
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
        return analyzeBatch(new AnalyzeNormalizationCommand(batchId, limit, forceReanalyze,
                includeKeywords, excludeKeywords, onlyFailed));
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
        return analyzeBatch(new AnalyzeNormalizationCommand(batchId, limit, forceReanalyze,
                includeKeywords, excludeKeywords, onlyFailed), progressListener);
    }

    /**
     * 分析指定导入批次内的 legacy_fallback 商品。
     *
     * <p>该方法只生成 normalization_suggestions 审计记录，最多为低置信或失败样本创建复核项；
     * 不会把 purchase_records 改为 include，也不会维护归一化规则库。</p>
     *
     * @param command 商品归一化分析命令
     * @return 批次分析统计结果
     */
    public NormalizationAnalyzeResult analyzeBatch(AnalyzeNormalizationCommand command) {
        return analyzeBatch(command, null);
    }

    /**
     * 按关键词筛选后分析指定导入批次内的 legacy_fallback 商品，并通过监听器回传异步任务进度。
     *
     * <p>该重载只增加进度通知能力，不改变 normalization_suggestions 的生成、失败重试、
     * batch-apply 或 review_items 写入规则；监听器异常会被记录并忽略，避免进度写库失败中断主分析流程。</p>
     *
     * @param command          商品归一化分析命令
     * @param progressListener 分析进度监听器，允许为空；为空时不回传进度
     * @return 批次分析统计结果
     */
    public NormalizationAnalyzeResult analyzeBatch(AnalyzeNormalizationCommand command,
                                                   NormalizationAnalyzeProgressListener progressListener) {
        databaseInitializer.initialize();
        if (!normalizationProperties.getLlm().isEnabled()) {
            throw new IllegalStateException("LLM normalization advisor 未启用");
        }

        CandidateFilter filter = new CandidateFilter(normalizedKeywords(command.includeKeywords()),
                normalizedKeywords(command.excludeKeywords()), command.onlyFailed());
        List<Candidate> candidates = candidates(command.batchId(), command.forceReanalyze(), filter);
        int analyzeLimit = command.limit() <= 0 ? 100 : command.limit();
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
        notifyProgress(progressListener, new NormalizationAnalyzeProgress(command.batchId(), candidates.size(),
                analyzedCount, autoExcludedCount, pendingBatchApprovalCount, pendingReviewCount, failedCount,
                0, totalBatches));

        for (int start = 0; start < limitedCandidates.size(); start += batchSize) {
            List<Candidate> batchCandidates = limitedCandidates.subList(start, Math.min(start + batchSize, limitedCandidates.size()));
            int batchIndex = start / batchSize + 1;
            long batchStartNanos = System.nanoTime();
            List<NormalizationAdvisorRequest> advisorRequests = batchCandidates.stream()
                    .map(Candidate::toAdvisorRequest)
                    .toList();
            NormalizationAdviceRequestMetrics requestMetrics = requestMetrics(advisorRequests);
            NormalizationProperties.Llm llm = normalizationProperties.getLlm();
            LOGGER.info("Normalization LLM batch start: batchId={}, batchIndex={}/{}, batchSize={}, model={}, "
                            + "baseUrlHost={}, timeoutSeconds={}, promptChars={}, requestBytes={}, aliasKeys={}",
                    command.batchId(), batchIndex, totalBatches, batchCandidates.size(), llm.getModel(),
                    baseUrlHost(llm.getBaseUrl()), llm.getRequestTimeoutSeconds(),
                    requestMetrics.promptChars(), requestMetrics.requestBytes(), aliasKeySummary(batchCandidates));
            List<NormalizationAdvisorResult> advisorResults;
            NormalizationAdviceObservation observation;
            try {
                NormalizationAdviceBatchAnalysis analysis =
                        productNormalizationAdvisor.analyzeBatchWithObservation(advisorRequests);
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
                PreparedSuggestion preparedSuggestion = toSuggestion(command.batchId(), candidate.aliasKey(), advisorResult);
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
                } else if (STATUS_PENDING_REVIEW.equals(status)) {
                    pendingReviewCount++;
                    createNormalizationReview(candidate.record(), suggestion.reason());
                } else {
                    pendingReviewCount++;
                    createNormalizationReview(candidate.record(),
                            appendReason(suggestion.reason(), "未知归一化建议状态：" + status));
                }
            }
            long saveElapsedMs = elapsedMs(saveStartNanos);
            long elapsedMs = (System.nanoTime() - batchStartNanos) / 1_000_000;
            String batchStatus = batchFailedCount == 0 ? "success"
                    : batchFailedCount == batchCandidates.size() ? "failed" : "partial_failed";
            if (observation.errorType() != null && !observation.errorType().isBlank()) {
                LOGGER.info("Normalization LLM batch failed: batchId={}, batchIndex={}/{}, elapsedMs={}, "
                                + "errorType={}, message={}, httpStatus={}, contentType={}",
                        command.batchId(), batchIndex, totalBatches, elapsedMs, observation.errorType(),
                        abbreviate(observation.errorMessage(), 200), observation.httpStatus(), observation.contentType());
            }
            LOGGER.info("Normalization LLM batch end: batchId={}, batchIndex={}/{}, elapsedMs={}, status={}, "
                            + "httpStatus={}, contentType={}, responseBytes={}, extractedContentChars={}, "
                            + "parsedItems={}, failedCount={}, saveElapsedMs={}, requestBuildElapsedMs={}, "
                            + "llmHttpElapsedMs={}, extractElapsedMs={}, parseElapsedMs={}, totalElapsedMs={}",
                    command.batchId(), batchIndex, totalBatches, elapsedMs, batchStatus, observation.httpStatus(),
                    observation.contentType(), observation.responseBytes(), observation.extractedContentChars(),
                    observation.parsedItems(), batchFailedCount, saveElapsedMs, observation.requestBuildElapsedMs(),
                    observation.llmHttpElapsedMs(), observation.extractElapsedMs(), observation.parseElapsedMs(),
                    observation.totalElapsedMs());
            writeDebugDump(command.batchId(), batchIndex, batchCandidates.size(), requestMetrics, observation,
                    elapsedMs, saveElapsedMs);
            notifyProgress(progressListener, new NormalizationAnalyzeProgress(command.batchId(), candidates.size(),
                    analyzedCount, autoExcludedCount, pendingBatchApprovalCount, pendingReviewCount, failedCount,
                    batchIndex, totalBatches));
        }
        String message = analyzeMessage(candidates.size(), analyzedCount, failureTypeCounts);
        return new NormalizationAnalyzeResult(command.batchId(), candidates.size(), analyzedCount, autoExcludedCount,
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
     * 查询指定批次中已自动排除且无需人工复核的高置信 EXCLUDE 建议。
     *
     * <p>该方法只读取 normalization_suggestions，用于展示 LLM 自动减少人工复核的记录明细；
     * 不维护归一化规则库，不修改 purchase_records.decision，也不更新 suggestion 状态。</p>
     *
     * @param batchId       导入批次 ID，必须大于 0
     * @param minConfidence 最低置信度阈值，允许为空；为空时使用默认值 0.9
     * @return 自动排除建议查询结果，包含总数、类型分布和明细
     */
    public AutoExcludedNormalizationSuggestionResult listAutoExcluded(long batchId, Double minConfidence) {
        if (batchId <= 0) {
            throw new IllegalArgumentException("batchId 必须大于 0");
        }
        double resolvedMinConfidence = minConfidence == null ? DEFAULT_AUTO_EXCLUDED_MIN_CONFIDENCE : minConfidence;
        if (resolvedMinConfidence < 0.0D || resolvedMinConfidence > 1.0D) {
            throw new IllegalArgumentException("minConfidence 必须在 0.0 到 1.0 之间");
        }
        databaseInitializer.initialize();
        List<NormalizationSuggestion> suggestions = normalizationSuggestionRepository
                .listAutoExcludedByBatchId(batchId, resolvedMinConfidence);
        Map<String, Long> typeCountMap = new LinkedHashMap<>();
        for (NormalizationSuggestion suggestion : suggestions) {
            typeCountMap.merge(suggestion.productType(), 1L, Long::sum);
        }
        List<AutoExcludedNormalizationSuggestionResult.TypeCount> typeCounts = AUTO_EXCLUDED_PRODUCT_TYPE_ORDER
                .stream()
                .filter(typeCountMap::containsKey)
                .map(productType -> new AutoExcludedNormalizationSuggestionResult.TypeCount(
                        productType, typeCountMap.get(productType)))
                .toList();
        List<AutoExcludedNormalizationSuggestionResult.Item> items = suggestions.stream()
                .map(this::toAutoExcludedItem)
                .toList();
        return new AutoExcludedNormalizationSuggestionResult(batchId, resolvedMinConfidence, items.size(),
                typeCounts, items);
    }

    /**
     * 批量确认高置信 NORMALIZE 建议。
     *
     * <p>该操作只更新 suggestion 状态，不修改历史 purchase_records.decision，
     * 后续规则维护应通过 normalization_rules / normalization_rule_keywords 完成。</p>
     *
     * @param batchId       导入批次 ID
     * @param action        批量动作，当前仅支持 approve_normalize
     * @param minConfidence 最低置信度阈值
     * @param onlyStatus    建议状态筛选，默认 pending_batch_approval
     * @return 批量应用结果
     */
    public NormalizationBatchApplyResult batchApply(long batchId, String action, double minConfidence, String onlyStatus) {
        return batchApply(new BatchApplyNormalizationCommand(batchId, action, minConfidence, onlyStatus));
    }

    /**
     * 批量确认高置信 NORMALIZE 建议。
     *
     * <p>该操作只更新 suggestion 状态，不修改历史 purchase_records.decision，
     * 后续规则维护应通过 normalization_rules / normalization_rule_keywords 完成。</p>
     *
     * @param command 归一化建议批量应用命令
     * @return 批量应用结果
     */
    public NormalizationBatchApplyResult batchApply(BatchApplyNormalizationCommand command) {
        databaseInitializer.initialize();
        if (!"approve_normalize".equalsIgnoreCase(command.action())) {
            throw new IllegalArgumentException("当前仅支持 approve_normalize");
        }
        String status = command.onlyStatus() == null || command.onlyStatus().isBlank()
                ? STATUS_PENDING_BATCH_APPROVAL : command.onlyStatus().trim();
        List<NormalizationSuggestion> suggestions = normalizationSuggestionRepository
                .listByBatchIdAndStatus(command.batchId(), status)
                .stream()
                .filter(suggestion -> ACTION_NORMALIZE.equals(suggestion.action()))
                .filter(suggestion -> PRODUCT_TYPE_REPURCHASE_CONSUMABLE.equals(suggestion.productType()))
                .filter(suggestion -> suggestion.confidence() >= command.minConfidence())
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
            normalizationSuggestionRepository.updateStatus(suggestion.id(), STATUS_APPROVED);
            appliedCount++;
        }
        return new NormalizationBatchApplyResult(command.batchId(), suggestions.size(), appliedCount,
                "批量应用完成：已确认 suggestions " + appliedCount
                        + " 条；未写入 alias。alias persistence has been deprecated; "
                        + "use normalization-library operations to maintain rules and keywords.");
    }

    private List<Candidate> candidates(long batchId, boolean forceReanalyze, CandidateFilter filter) {
        Map<String, PurchaseRecord> uniqueRecords = new LinkedHashMap<>();
        for (PurchaseRecord record : purchaseRecordRepository.listByBatchId(batchId)) {
            if (!isLegacyFallback(record)) {
                continue;
            }
            String aliasKey = productTitleCleaner.aliasKey(record.productName(), record.sku());
            if (aliasKey.isBlank()) {
                continue;
            }
            if (!matchesCandidateFilter(record, aliasKey, batchId, filter)) {
                continue;
            }
            if (!forceReanalyze && normalizationSuggestionRepository.existsNonFailedByBatchIdAndAliasKey(batchId, aliasKey)) {
                continue;
            }
            // TODO 后续若需要严格回放审计，forceReanalyze=true 时应先将旧 suggestion 标记为 replaced/superseded。
            // 当前第一版允许同一 batch + normalization_suggestions.alias_key 存在多条建议，避免为收尾修复扩展状态机。
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

    private NormalizationAdviceRequestMetrics requestMetrics(List<NormalizationAdvisorRequest> requests) {
        try {
            return productNormalizationAdvisor.requestMetrics(requests);
        } catch (Exception e) {
            LOGGER.warn("Normalization LLM request metrics build failed: errorType={}, message={}",
                    errorType(classifyException(e)), abbreviate(sanitizeError(errorMessage(e)), 200));
            return new NormalizationAdviceRequestMetrics(0, 0, null);
        }
    }

    private NormalizationAdviceObservation failedObservation(Exception e,
                                                             NormalizationAdviceRequestMetrics requestMetrics,
                                                             long batchStartNanos) {
        return new NormalizationAdviceObservation(
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
                                NormalizationAdviceRequestMetrics requestMetrics,
                                NormalizationAdviceObservation observation,
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
                                             NormalizationAdviceObservation observation) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("body", llm.isDebugLogFullPrompt() ? observation.requestBody() : null);
        return request;
    }

    private Map<String, Object> debugResponse(NormalizationProperties.Llm llm,
                                              NormalizationAdviceObservation observation) {
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

    private PreparedSuggestion toSuggestion(long batchId,
                                            String aliasKey,
                                            NormalizationAdvisorResult result) {
        String canonicalNormalizedName = suggestedNormalizedNameCanonicalizer.canonicalize(
                result.rawProductName(), result.sku(), result.suggestedNormalizedName());
        if (isSunscreenDurable(result.rawProductName(), result.sku()) && "防晒".equals(canonicalNormalizedName)) {
            canonicalNormalizedName = null;
        }
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
        SuggestionDecision suggestionDecision = normalizeSuggestionDecision(result, action, productType,
                reviewRequired, reason, targetUnitResult.batchApprovalSafe());
        action = suggestionDecision.action();
        productType = suggestionDecision.productType();
        reviewRequired = suggestionDecision.reviewRequired();
        reason = suggestionDecision.reason();
        String status = suggestionDecision.status();
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
        SafetyGuardDecision decision = new SafetyGuardDecision(action, productType, reviewRequired, reason);
        if (result.failed()) {
            return decision;
        }
        decision = downgradeTravelService(result, decision);
        decision = downgradeCouponDepositRight(result, canonicalNormalizedName, decision);
        decision = downgradeClothingDurable(result, decision);
        decision = downgradeSunscreenDurable(result, decision);
        decision = downgradeHighConfidenceDurable(result, decision);
        decision = downgradePetDomainMismatch(result, canonicalNormalizedName, decision);
        decision = downgradeNormalizeTypeConflict(decision);
        decision = downgradeReviewReasonCode(result, decision);
        decision = downgradeReviewExplanation(result, decision);
        decision = downgradeInvalidTargetUnit(result, decision);
        decision = downgradeCoffeeBeverage(result, decision);
        decision = downgradeUnsafeSuggestedNormalizedName(canonicalNormalizedName, decision);
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
        if (isAutoExcludedDecision(decision)) {
            return decision;
        }
        if (ACTION_NORMALIZE.equals(decision.action()) && NON_POSITIVE_PRODUCT_TYPES.contains(decision.productType())) {
            return decision.review("类型动作冲突");
        }
        return decision;
    }

    /**
     * 酒店、住宿和旅行服务不是家庭复购消耗品，直接自动排除。
     *
     * @param result   LLM 原始结构化建议
     * @param decision 当前安全决策
     * @return 命中服务类关键词时返回自动排除决策
     */
    private SafetyGuardDecision downgradeTravelService(NormalizationAdvisorResult result,
                                                       SafetyGuardDecision decision) {
        String text = rawSuggestionText(result);
        if (!containsAnyText(text, TRAVEL_SERVICE_KEYWORDS)) {
            return decision;
        }
        return decision.exclude(PRODUCT_TYPE_NON_REPURCHASE, "后处理修正：酒店/住宿/旅行服务，自动排除");
    }

    /**
     * 处理预售、付定、定金、权益券等高风险交易词。
     *
     * @param result                  LLM 原始结构化建议
     * @param canonicalNormalizedName 归并后的系统标准品类名称
     * @param decision                当前安全决策
     * @return 纯权益自动排除；真实商品含预售/付定时转人工复核
     */
    private SafetyGuardDecision downgradeCouponDepositRight(NormalizationAdvisorResult result,
                                                            String canonicalNormalizedName,
                                                            SafetyGuardDecision decision) {
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
        String text = rawSuggestionText(result);
        if (!containsAnyText(text, COUPON_DEPOSIT_RIGHT_KEYWORDS)) {
            return decision;
        }
        if (hasRepurchaseProductBody(text, canonicalNormalizedName)) {
            return decision.review("后处理修正：真实商品含预售/付定/定金，需人工确认是否为完整订单");
        }
        return decision.exclude(PRODUCT_TYPE_COUPON_OR_DEPOSIT, "后处理修正：纯权益/券/定金，自动排除");
    }

    /**
     * 服饰类商品不得自动进入批量确认。
     *
     * @param result   LLM 原始结构化建议
     * @param decision 当前安全决策
     * @return 高置信耐用品自动排除；其他冲突场景转人工复核
     */
    private SafetyGuardDecision downgradeClothingDurable(NormalizationAdvisorResult result,
                                                         SafetyGuardDecision decision) {
        String text = rawSuggestionText(result);
        if (!containsAnyText(text, CLOTHING_DURABLE_KEYWORDS)) {
            return decision;
        }
        if (isProtectedRepurchaseCandidateText(text)) {
            return decision.reviewAs(PRODUCT_TYPE_REPURCHASE_CONSUMABLE, "潜在复购消耗品需人工复核");
        }
        if (PRODUCT_TYPE_DURABLE.equals(decision.productType())
                && result.confidence() >= DEFAULT_AUTO_EXCLUDED_MIN_CONFIDENCE) {
            return decision.exclude(PRODUCT_TYPE_DURABLE, "后处理修正：高置信服饰耐用品，自动排除");
        }
        return decision.review("后处理修正：高风险自动批准过滤，转人工复核");
    }

    /**
     * 防晒服饰和防晒护理用品语义分流。
     *
     * @param result   LLM 原始结构化建议
     * @param decision 当前安全决策
     * @return 防晒衣/潜水服等耐用品自动排除或转复核
     */
    private SafetyGuardDecision downgradeSunscreenDurable(NormalizationAdvisorResult result,
                                                          SafetyGuardDecision decision) {
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
        if (!isSunscreenDurable(result.rawProductName(), result.sku())) {
            return decision;
        }
        if (PRODUCT_TYPE_DURABLE.equals(decision.productType())
                && result.confidence() >= DEFAULT_AUTO_EXCLUDED_MIN_CONFIDENCE) {
            return decision.exclude(PRODUCT_TYPE_DURABLE, "后处理修正：防晒服饰耐用品，自动排除");
        }
        return decision.review("后处理修正：防晒服饰不是防晒护理消耗品，转人工复核");
    }

    /**
     * 高置信耐用品直接排除，避免形成无意义的人工复核噪音。
     *
     * @param result   LLM 原始结构化建议
     * @param decision 当前安全决策
     * @return 高置信 DURABLE 返回自动排除决策
     */
    private SafetyGuardDecision downgradeHighConfidenceDurable(NormalizationAdvisorResult result,
                                                               SafetyGuardDecision decision) {
        if (!PRODUCT_TYPE_DURABLE.equals(decision.productType())) {
            return decision;
        }
        if (isProtectedRepurchaseCandidate(result)) {
            String reason = containsPetProductDomain(rawSuggestionText(result))
                    ? "猫食品消耗品被误判为耐用品，需人工复核"
                    : "潜在复购消耗品被误判为耐用品，需人工复核";
            return decision.reviewAs(PRODUCT_TYPE_REPURCHASE_CONSUMABLE, reason);
        }
        if (result.confidence() >= DEFAULT_AUTO_EXCLUDED_MIN_CONFIDENCE) {
            return decision.exclude(PRODUCT_TYPE_DURABLE, "后处理修正：高置信耐用品，自动排除");
        }
        return decision;
    }

    /**
     * LLM 把普通商品跨域归入宠物食品时转人工复核，并清理跨域 reason。
     *
     * @param result                  LLM 原始结构化建议
     * @param canonicalNormalizedName 归并后的系统标准品类名称
     * @param decision                当前安全决策
     * @return 跨域命中时返回人工复核决策
     */
    private SafetyGuardDecision downgradePetDomainMismatch(NormalizationAdvisorResult result,
                                                           String canonicalNormalizedName,
                                                           SafetyGuardDecision decision) {
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
        if (!PET_FOOD_NORMALIZED_NAMES.contains(safeText(canonicalNormalizedName))) {
            return decision;
        }
        if (containsPetProductDomain(rawSuggestionText(result))) {
            return decision;
        }
        return decision.withReason(sanitizeCrossDomainReason(decision.reason()))
                .review("后处理修正：标准品类与商品语义跨域，转人工复核");
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
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
        String reasonCode = safeText(result.reasonCode()).toUpperCase();
        if (!REVIEW_REQUIRED_REASON_CODES.contains(reasonCode)) {
            return decision;
        }
        if (ACTION_EXCLUDE.equals(decision.action()) && PRODUCT_TYPE_COUPON_OR_DEPOSIT.equals(decision.productType())) {
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
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
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
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
        String targetUnit = safeText(result.targetUnit()).toUpperCase();
        if (!targetUnit.isBlank() && UNIT_FAMILY_VALUES.contains(targetUnit)) {
            return decision.review("单位字段非法");
        }
        return decision;
    }

    /**
     * 咖啡类真实饮品具备复购属性，但不同形态的 normalizedName 和 targetUnit 需要人工确认。
     *
     * @param result   LLM 原始结构化建议
     * @param decision 当前安全决策
     * @return 命中咖啡饮品时返回复购消耗品人工复核决策；咖啡杯、咖啡机等耐用品不在此规则处理
     */
    private SafetyGuardDecision downgradeCoffeeBeverage(NormalizationAdvisorResult result,
                                                        SafetyGuardDecision decision) {
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
        String text = rawSuggestionText(result);
        if (!isCoffeeBeverageReviewText(text) || containsAnyText(text, COFFEE_DURABLE_KEYWORDS)) {
            return decision;
        }
        return decision.reviewAs(PRODUCT_TYPE_REPURCHASE_CONSUMABLE, "咖啡类复购品需人工确认归一化");
    }

    /**
     * 校验 LLM 建议标准名是否混入规格、包装形态或促销销售词。
     *
     * @param canonicalNormalizedName 归并后的建议标准名，允许包含品牌、系列和口味
     * @param decision                当前安全决策
     * @return 建议标准名不安全时返回人工复核决策；否则返回原决策
     */
    private SafetyGuardDecision downgradeUnsafeSuggestedNormalizedName(String canonicalNormalizedName,
                                                                       SafetyGuardDecision decision) {
        if (isAutoExcludedDecision(decision)) {
            return decision;
        }
        if (!isUnsafeSuggestedNormalizedName(canonicalNormalizedName)) {
            return decision;
        }
        return decision.review("建议标准名疑似包含规格/包装形态/促销销售词，需人工确认");
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
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
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
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
        String text = safeText(result.rawProductName()) + " " + safeText(result.sku());
        if (!containsAnyText(text, SAMPLE_OR_GIFT_KEYWORDS)) {
            return decision;
        }
        if (ACTION_EXCLUDE.equals(decision.action()) && PRODUCT_TYPE_COUPON_OR_DEPOSIT.equals(decision.productType())) {
            return decision;
        }
        return decision.review("试吃/尝鲜/会员试用，需人工确认是否纳入");
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
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
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
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
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
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
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
        if (shouldAutoExcludeWithoutReview(result, decision)) {
            return decision;
        }
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
     * 判断文本是否命中真实咖啡饮品语义。
     *
     * @param text 商品标题、SKU 和建议标准名拼接后的上下文
     * @return 命中咖啡强关键词时返回 true；单独“美式”“拿铁”“Americano”“Latte”“浓缩液”不触发
     */
    private boolean isCoffeeBeverageReviewText(String text) {
        String coffeeSignalText = removeCoffeeColorWords(text);
        boolean hasStrongCoffeeContext = containsAnyText(coffeeSignalText, COFFEE_BEVERAGE_STRONG_KEYWORDS);
        if (containsAnyText(coffeeSignalText, COFFEE_BEVERAGE_WEAK_KEYWORDS)) {
            return hasStrongCoffeeContext;
        }
        return hasStrongCoffeeContext;
    }

    /**
     * 移除服饰、彩妆和色号中的咖啡色描述，避免被“咖啡”强词误判为饮品。
     *
     * @param text 商品标题、SKU 和建议标准名拼接后的上下文
     * @return 去除咖啡色描述后的文本
     */
    private String removeCoffeeColorWords(String text) {
        String result = safeText(text);
        for (String colorKeyword : COFFEE_COLOR_EXCLUDE_KEYWORDS) {
            result = result.replace(colorKeyword, "");
        }
        return result;
    }

    /**
     * 判断当前安全决策是否已经是明确自动排除结果。
     *
     * @param decision 当前安全决策
     * @return EXCLUDE、无需复核且类型属于耐用品、非复购或券定金权益时返回 true
     */
    private boolean isAutoExcludedDecision(SafetyGuardDecision decision) {
        return isAutoExcludedDecision(decision.action(), decision.productType(), decision.reviewRequired());
    }

    /**
     * 判断给定 action、productType 和复核标记是否已经是明确自动排除结果。
     *
     * @param action         当前建议动作
     * @param productType    当前商品类型
     * @param reviewRequired 当前是否需要人工复核
     * @return EXCLUDE、无需复核且类型属于耐用品、非复购或券定金权益时返回 true
     */
    private boolean isAutoExcludedDecision(String action, String productType, boolean reviewRequired) {
        return ACTION_EXCLUDE.equals(action)
                && !reviewRequired
                && AUTO_EXCLUDED_PRODUCT_TYPES.contains(productType);
    }

    /**
     * 判断当前安全决策是否可以作为高置信自动排除结果保留。
     *
     * @param result   LLM 原始结构化建议，用于读取置信度和商品文本
     * @param decision 当前安全决策
     * @return 满足 EXCLUDE、无需复核、排除类型、高置信且未命中复购保护关键词时返回 true
     */
    private boolean shouldAutoExcludeWithoutReview(NormalizationAdvisorResult result,
                                                   SafetyGuardDecision decision) {
        return shouldAutoExcludeWithoutReview(result, decision.action(), decision.productType(),
                decision.reviewRequired());
    }

    /**
     * 判断给定动作、类型和复核标记是否可进入 auto_excluded 状态。
     *
     * @param result         LLM 原始结构化建议，用于读取置信度和商品文本
     * @param action         当前建议动作
     * @param productType    当前商品类型
     * @param reviewRequired 当前是否需要人工复核
     * @return 满足自动排除结构条件、置信度阈值且不是复购保护候选时返回 true
     */
    private boolean shouldAutoExcludeWithoutReview(NormalizationAdvisorResult result,
                                                   String action,
                                                   String productType,
                                                   boolean reviewRequired) {
        return isAutoExcludedDecision(action, productType, reviewRequired)
                && result.confidence() >= DEFAULT_AUTO_EXCLUDED_MIN_CONFIDENCE
                && !isProtectedRepurchaseCandidate(result);
    }

    /**
     * 判断商品标题或 SKU 是否命中必须保留人工判断的复购候选。
     *
     * @param result LLM 原始结构化建议
     * @return 命中猫食品、美瞳、咖啡、个人护理等复购候选语义时返回 true
     */
    private boolean isProtectedRepurchaseCandidate(NormalizationAdvisorResult result) {
        return isProtectedRepurchaseCandidateText(rawSuggestionText(result));
    }

    /**
     * 判断文本是否命中不能自动排除的复购候选关键词。
     *
     * @param text 商品标题、SKU 或建议文本
     * @return 命中复购候选关键词或真实咖啡饮品语义时返回 true
     */
    private boolean isProtectedRepurchaseCandidateText(String text) {
        if (containsAnyText(text, PET_DURABLE_KEYWORDS) || containsAnyText(text, COFFEE_DURABLE_KEYWORDS)) {
            return false;
        }
        return containsAnyText(text, PROTECTED_REPURCHASE_CANDIDATE_KEYWORDS)
                || isCoffeeBeverageReviewText(text);
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

    /**
     * 统一归一化 action、productType、reviewRequired、status 和 reason。
     *
     * <p>该方法是 normalization suggestion 入库前的最终状态机，所有前置 safety guard 的结果都必须经过这里，
     * 避免出现 action、reviewRequired 和 status 相互矛盾的建议。</p>
     *
     * @param result            LLM 原始结构化建议
     * @param action            safety guard 后的动作
     * @param productType       safety guard 后的商品类型
     * @param reviewRequired    safety guard 后的复核标记
     * @param reason            safety guard 后的原因
     * @param batchApprovalSafe targetUnit 是否允许批量确认
     * @return 归一化后的最终建议决策
     */
    private SuggestionDecision normalizeSuggestionDecision(NormalizationAdvisorResult result,
                                                           String action,
                                                           String productType,
                                                           boolean reviewRequired,
                                                           String reason,
                                                           boolean batchApprovalSafe) {
        String resolvedAction = safeText(action).isBlank() ? ACTION_REVIEW : action;
        String resolvedProductType = safeText(productType).isBlank() ? "UNKNOWN" : productType;
        String resolvedReason = sanitizeReasonPunctuation(reason);
        boolean resolvedReviewRequired = reviewRequired;
        if (result.failed()) {
            return new SuggestionDecision(ACTION_REVIEW, resolvedProductType, true,
                    appendReason(resolvedReason, "后处理修正：LLM 输出失败，转人工复核"), STATUS_FAILED);
        }
        if (!List.of(ACTION_NORMALIZE, ACTION_EXCLUDE, ACTION_REVIEW).contains(resolvedAction)) {
            resolvedAction = ACTION_REVIEW;
            resolvedReviewRequired = true;
            resolvedReason = appendReason(resolvedReason, "后处理修正：非法 action，转人工复核");
        }
        if (ACTION_NORMALIZE.equals(resolvedAction) && NON_POSITIVE_PRODUCT_TYPES.contains(resolvedProductType)) {
            resolvedAction = ACTION_REVIEW;
            resolvedReviewRequired = true;
            resolvedReason = appendReason(resolvedReason, "后处理修正：类型动作冲突，需人工复核");
        }
        if (ACTION_NORMALIZE.equals(resolvedAction) && !batchApprovalSafe) {
            resolvedAction = ACTION_REVIEW;
            resolvedReviewRequired = true;
            resolvedReason = appendReason(resolvedReason, "单位需复核");
        }
        if (!isAutoExcludedDecision(resolvedAction, resolvedProductType, resolvedReviewRequired)
                && containsPetDomainReason(resolvedReason)
                && !containsPetProductDomain(rawSuggestionText(result))) {
            resolvedAction = ACTION_REVIEW;
            resolvedReviewRequired = true;
            resolvedReason = appendReason(sanitizeCrossDomainReason(resolvedReason), "后处理修正：跨域 reason 已清理");
        }
        if (!isAutoExcludedDecision(resolvedAction, resolvedProductType, resolvedReviewRequired)
                && containsAnyText(resolvedReason, REVIEW_TEXT_KEYWORDS)) {
            resolvedAction = ACTION_REVIEW;
            resolvedReviewRequired = true;
        }
        if (ACTION_REVIEW.equals(resolvedAction)) {
            resolvedReviewRequired = true;
            return new SuggestionDecision(ACTION_REVIEW, resolvedProductType, true,
                    appendReason(resolvedReason, "后处理修正：状态与动作不一致，已按状态机归一化"),
                    STATUS_PENDING_REVIEW);
        }
        if (resolvedReviewRequired) {
            return new SuggestionDecision(ACTION_REVIEW, resolvedProductType, true,
                    appendReason(resolvedReason, "后处理修正：状态与动作不一致，已按状态机归一化"),
                    STATUS_PENDING_REVIEW);
        }
        if (shouldAutoExcludeWithoutReview(result, resolvedAction, resolvedProductType, resolvedReviewRequired)) {
            return new SuggestionDecision(ACTION_EXCLUDE, resolvedProductType, false, resolvedReason,
                    STATUS_AUTO_EXCLUDED);
        }
        if (ACTION_EXCLUDE.equals(resolvedAction)) {
            return new SuggestionDecision(ACTION_REVIEW, resolvedProductType, true,
                    appendReason(resolvedReason, "后处理修正：低置信或潜在复购候选，转人工复核"),
                    STATUS_PENDING_REVIEW);
        }
        return new SuggestionDecision(ACTION_NORMALIZE, resolvedProductType, false, resolvedReason,
                STATUS_PENDING_BATCH_APPROVAL);
    }

    /**
     * 判断标题或 SKU 是否为防晒服饰，而不是防晒霜、防晒乳等护理消耗品。
     *
     * @param productName 商品标题
     * @param sku         商品 SKU
     * @return 命中防晒服饰语义时返回 true
     */
    private boolean isSunscreenDurable(String productName, String sku) {
        String text = safeText(productName) + " " + safeText(sku);
        return containsAnyText(text, SUNSCREEN_DURABLE_KEYWORDS)
                && !containsAnyText(text, SUNSCREEN_CONSUMABLE_KEYWORDS);
    }

    /**
     * 判断预售、付定或权益文本中是否仍存在明确复购商品本体。
     *
     * @param text                    商品标题和 SKU 拼接文本
     * @param canonicalNormalizedName 归并后的标准品类名称
     * @return 有真实复购商品本体时返回 true
     */
    private boolean hasRepurchaseProductBody(String text, String canonicalNormalizedName) {
        return REPURCHASE_NORMALIZED_NAMES.contains(safeText(canonicalNormalizedName))
                || containsAnyText(text, REPURCHASE_PRODUCT_KEYWORDS);
    }

    /**
     * 拼接商品标题和 SKU，作为后处理关键词匹配文本。
     *
     * @param result LLM 原始结构化建议
     * @return 商品标题和 SKU 拼接文本
     */
    private String rawSuggestionText(NormalizationAdvisorResult result) {
        return safeText(result.rawProductName()) + " " + safeText(result.sku());
    }

    /**
     * 判断商品标题或 SKU 是否属于宠物商品语义域。
     *
     * @param text 商品标题与 SKU 拼接文本
     * @return 命中猫、犬、狗、宠物等域词时返回 true
     */
    private boolean containsPetProductDomain(String text) {
        return containsAnyText(text, PET_PRODUCT_DOMAIN_KEYWORDS);
    }

    /**
     * 判断建议标准名是否混入规格、包装形态或促销销售词。
     *
     * @param suggestedNormalizedName LLM 建议标准名
     * @return 命中规格数量、包装形态或促销销售词时返回 true
     */
    private boolean isUnsafeSuggestedNormalizedName(String suggestedNormalizedName) {
        String text = safeText(suggestedNormalizedName);
        if (text.isBlank()) {
            return false;
        }
        return UNSAFE_NORMALIZED_NAME_SPEC_PATTERN.matcher(text).find()
                || containsAnyText(text, UNSAFE_NORMALIZED_NAME_PACKAGING_KEYWORDS)
                || containsAnyText(text, UNSAFE_NORMALIZED_NAME_PROMOTION_KEYWORDS);
    }

    /**
     * 判断 reason 是否包含宠物食品域的误判描述。
     *
     * @param reason LLM 或后处理生成的原因
     * @return 包含猫食品或宠物食品表述时返回 true
     */
    private boolean containsPetDomainReason(String reason) {
        String text = safeText(reason);
        return text.contains("猫食品") || text.contains("宠物食品");
    }

    /**
     * 清理明显跨域的 reason 文案。
     *
     * @param reason 原始 reason
     * @return 清理后的 reason
     */
    private String sanitizeCrossDomainReason(String reason) {
        String sanitized = safeText(reason)
                .replace("命中猫食品消耗品关键词", "")
                .replace("宠物食品单位需复核", "")
                .replace("猫食品", "")
                .replace("宠物食品", "")
                .replace("个人护理消耗品", "");
        // 删除跨域 reason 片段后可能留下“；，”这类连续标点，避免 review item reason 自相矛盾。
        sanitized = sanitized.replaceAll("[；;]\\s*[，,]+", "；");
        sanitized = sanitized.replaceAll("[；;，,\\s]+$", "").trim();
        return sanitized.isBlank() ? "LLM 安全校验" : sanitized;
    }

    /**
     * 清理 reason 拼接过程中的连续标点残留。
     *
     * @param reason 原始 reason 文本
     * @return 将“；，”等残留标点规整后的 reason
     */
    private String sanitizeReasonPunctuation(String reason) {
        return safeText(reason)
                .replaceAll("[；;]\\s*[，,]+", "；")
                .replaceAll("[，,]+\\s*[；;]", "；")
                .trim();
    }

    private String appendReason(String originalReason, String extraReason) {
        String baseReason = sanitizeReasonPunctuation(originalReason);
        baseReason = baseReason.isBlank() ? "targetUnit 安全校验" : baseReason;
        if (extraReason == null || extraReason.isBlank() || baseReason.contains(extraReason)) {
            return baseReason;
        }
        return sanitizeReasonPunctuation(baseReason + "；" + extraReason);
    }

    /**
     * 将数据库建议实体转换为自动排除只读接口明细项。
     *
     * @param suggestion 已通过仓储条件筛选的自动排除建议
     * @return 不包含 evidenceJson 的响应明细项
     */
    private AutoExcludedNormalizationSuggestionResult.Item toAutoExcludedItem(NormalizationSuggestion suggestion) {
        return new AutoExcludedNormalizationSuggestionResult.Item(
                suggestion.id(),
                suggestion.rawProductName(),
                suggestion.sku(),
                suggestion.aliasKey(),
                suggestion.action(),
                suggestion.productType(),
                suggestion.confidence(),
                suggestion.reviewRequired(),
                suggestion.status(),
                suggestion.reason(),
                toResponseDateTimeText(suggestion.createdAt())
        );
    }

    /**
     * 将数据库中的空格分隔时间转换为 REST 响应使用的 ISO 风格时间文本。
     *
     * @param createdAt 数据库存储的创建时间文本，通常为 yyyy-MM-dd HH:mm:ss
     * @return yyyy-MM-dd'T'HH:mm:ss 风格时间文本；空值保持为空字符串
     */
    private String toResponseDateTimeText(String createdAt) {
        return safeText(createdAt).replace(' ', 'T');
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
     */
    private record SafetyGuardDecision(String action,
                                       String productType,
                                       boolean reviewRequired,
                                       String reason) {
        private SafetyGuardDecision review(String extraReason) {
            return new SafetyGuardDecision(ACTION_REVIEW, productType, true, appendGuardReason(extraReason));
        }

        private SafetyGuardDecision reviewAs(String resolvedProductType, String extraReason) {
            return new SafetyGuardDecision(ACTION_REVIEW, resolvedProductType, true, appendGuardReason(extraReason));
        }

        private SafetyGuardDecision forceReview(String extraReason) {
            return new SafetyGuardDecision(action, productType, true, appendGuardReason(extraReason));
        }

        private SafetyGuardDecision exclude(String resolvedProductType, String extraReason) {
            return new SafetyGuardDecision(ACTION_EXCLUDE, resolvedProductType, false, appendGuardReason(extraReason));
        }

        private SafetyGuardDecision withReason(String resolvedReason) {
            return new SafetyGuardDecision(action, productType, reviewRequired, resolvedReason);
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

    private record SuggestionDecision(String action,
                                      String productType,
                                      boolean reviewRequired,
                                      String reason,
                                      String status) {
    }
}
