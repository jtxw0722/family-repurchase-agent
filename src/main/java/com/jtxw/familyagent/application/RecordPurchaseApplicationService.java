package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.RecordPurchaseCommand;
import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.RecordPurchaseResult;
import com.jtxw.familyagent.domain.policy.*;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.ImportBatchRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:27:12
 * @Description: 手动购买记录录入应用服务，负责校验、归一化、单价计算、去重、入库和复核创建。
 */
@Service
public class RecordPurchaseApplicationService {
    private static final String SOURCE_PREFIX = "manual:record_purchase";
    private static final DateTimeFormatter PURCHASE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int OUT_OF_RANGE_SAMPLE_THRESHOLD = 3;
    private static final double LOW_PRICE_FACTOR = 0.8D;
    private static final double HIGH_PRICE_FACTOR = 1.2D;
    /**
     * 统计决策：纳入价格基准
     */
    private static final String DECISION_INCLUDE = "include";
    /**
     * 统计决策：排除出价格基准
     */
    private static final String DECISION_EXCLUDE = "exclude";
    /**
     * 去重状态：唯一记录
     */
    private static final String DEDUPE_STATUS_UNIQUE = "unique";
    /**
     * 去重状态：疑似重复记录
     */
    private static final String DEDUPE_STATUS_DUPLICATE = "duplicate";
    /**
     * 购买记录金额来源：手动录入
     */
    private static final String AMOUNT_SOURCE_MANUAL_RECORD = "manual_record";
    /**
     * 默认币种
     */
    private static final String DEFAULT_CURRENCY = "CNY";
    /**
     * 复核原因码：商品名称归一化置信度较低
     */
    private static final String REVIEW_REASON_PRODUCT_NAME_NORMALIZATION = "PRODUCT_NAME_NORMALIZATION_REVIEW";
    /**
     * 复核原因码：请求单位与目标单位不一致且无法解析
     */
    private static final String REVIEW_REASON_UNIT_MISMATCH_UNPARSED = "UNIT_MISMATCH_UNPARSED";
    /**
     * 复核原因码：疑似重复订单
     */
    private static final String REVIEW_REASON_DUPLICATE_ORDER = "DUPLICATE_ORDER";
    /**
     * 复核原因码：购买时间晚于当前时间
     */
    private static final String REVIEW_REASON_FUTURE_PURCHASE_TIME = "FUTURE_PURCHASE_TIME";
    /**
     * 复核原因码：当前单价明显超出历史价格区间
     */
    private static final String REVIEW_REASON_PRICE_OUT_OF_BASELINE_RANGE = "PRICE_OUT_OF_BASELINE_RANGE";
    /**
     * 负向别名规则标识，表示人工确认过的误判样本
     */
    private static final String NORMALIZATION_RULE_PRODUCT_NEGATIVE_ALIAS = "product_negative_alias";
    /**
     * 旧归一化兜底规则标识
     */
    private static final String NORMALIZATION_RULE_LEGACY_FALLBACK = "legacy_fallback";
    /**
     * 默认平台：手动录入
     */
    private static final String PLATFORM_MANUAL = "manual";
    /**
     * 平台归一化目标值：淘宝
     */
    private static final String PLATFORM_TAOBAO = "taobao";
    /**
     * 平台归一化目标值：京东
     */
    private static final String PLATFORM_JD = "jd";
    /**
     * 平台归一化目标值：拼多多
     */
    private static final String PLATFORM_PDD = "pdd";
    /**
     * 平台归一化目标值：天猫
     */
    private static final String PLATFORM_TMALL = "tmall";
    /**
     * 平台归一化目标值：线下
     */
    private static final String PLATFORM_OFFLINE = "offline";
    /**
     * 中文平台别名：淘宝
     */
    private static final String PLATFORM_ALIAS_TAOBAO = "淘宝";
    /**
     * 中文平台别名：淘宝网
     */
    private static final String PLATFORM_ALIAS_TAOBAO_WEB = "淘宝网";
    /**
     * 中文平台别名：京东
     */
    private static final String PLATFORM_ALIAS_JD = "京东";
    /**
     * 中文平台别名：京东自营
     */
    private static final String PLATFORM_ALIAS_JD_SELF_OPERATED = "京东自营";
    /**
     * 中文平台别名：拼多多
     */
    private static final String PLATFORM_ALIAS_PDD = "拼多多";
    /**
     * 中文平台别名：天猫
     */
    private static final String PLATFORM_ALIAS_TMALL = "天猫";
    /**
     * 中文平台别名：线下
     */
    private static final String PLATFORM_ALIAS_OFFLINE = "线下";
    /**
     * 中文平台别名：超市
     */
    private static final String PLATFORM_ALIAS_SUPERMARKET = "超市";
    /**
     * 中文平台别名：便利店
     */
    private static final String PLATFORM_ALIAS_CONVENIENCE_STORE = "便利店";
    /**
     * 旧模式兜底复核模式：导入时立即创建复核项
     */
    private static final String FALLBACK_REVIEW_MODE_IMMEDIATE_REVIEW = "immediate_review";
    /**
     * 用户未提供 SKU 时的缺省占位值
     */
    private static final String DEFAULT_SKU_PLACEHOLDER = "暂无";

    private final DatabaseInitializer databaseInitializer;
    private final LearningProductNameNormalizer productNameNormalizer;
    private final QuantityUnitParser quantityUnitParser;
    private final DuplicateDetectionPolicy duplicateDetectionPolicy;
    private final OwnerNormalizer ownerNormalizer;
    private final PurchaseTimeNormalizer purchaseTimeNormalizer;
    private final ImportBatchRepository importBatchRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final NormalizationProperties normalizationProperties;

    /**
     * 创建手动购买记录录入应用服务，使用指定的归一化配置。
     *
     * @param databaseInitializer      数据库初始化组件
     * @param productNameNormalizer    商品名称归一化器
     * @param quantityUnitParser       规格数量解析器
     * @param duplicateDetectionPolicy 重复检测策略
     * @param ownerNormalizer          归属人归一化器
     * @param purchaseTimeNormalizer   购买时间归一化器
     * @param importBatchRepository    导入批次仓储
     * @param purchaseRecordRepository 购买记录仓储
     * @param reviewItemRepository     复核项仓储
     * @param normalizationProperties  归一化配置
     */
    @Autowired
    public RecordPurchaseApplicationService(DatabaseInitializer databaseInitializer,
                                            LearningProductNameNormalizer productNameNormalizer,
                                            QuantityUnitParser quantityUnitParser,
                                            DuplicateDetectionPolicy duplicateDetectionPolicy,
                                            OwnerNormalizer ownerNormalizer,
                                            PurchaseTimeNormalizer purchaseTimeNormalizer,
                                            ImportBatchRepository importBatchRepository,
                                            PurchaseRecordRepository purchaseRecordRepository,
                                            ReviewItemRepository reviewItemRepository,
                                            NormalizationProperties normalizationProperties) {
        this.databaseInitializer = databaseInitializer;
        this.productNameNormalizer = productNameNormalizer;
        this.quantityUnitParser = quantityUnitParser;
        this.duplicateDetectionPolicy = duplicateDetectionPolicy;
        this.ownerNormalizer = ownerNormalizer;
        this.purchaseTimeNormalizer = purchaseTimeNormalizer;
        this.importBatchRepository = importBatchRepository;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.normalizationProperties = normalizationProperties;
    }

    /**
     * 创建手动购买记录录入应用服务，使用旧模式兜底归一化配置。
     *
     * <p>该构造器用于兼容旧测试或未注入 NormalizationProperties 的场景，
     * 内部使用 {@link #legacyReviewProperties()} 生成默认配置。</p>
     *
     * @param databaseInitializer      数据库初始化组件
     * @param productNameNormalizer    商品名称归一化器
     * @param quantityUnitParser       规格数量解析器
     * @param duplicateDetectionPolicy 重复检测策略
     * @param ownerNormalizer          归属人归一化器
     * @param purchaseTimeNormalizer   购买时间归一化器
     * @param importBatchRepository    导入批次仓储
     * @param purchaseRecordRepository 购买记录仓储
     * @param reviewItemRepository     复核项仓储
     */
    public RecordPurchaseApplicationService(DatabaseInitializer databaseInitializer,
                                            LearningProductNameNormalizer productNameNormalizer,
                                            QuantityUnitParser quantityUnitParser,
                                            DuplicateDetectionPolicy duplicateDetectionPolicy,
                                            OwnerNormalizer ownerNormalizer,
                                            PurchaseTimeNormalizer purchaseTimeNormalizer,
                                            ImportBatchRepository importBatchRepository,
                                            PurchaseRecordRepository purchaseRecordRepository,
                                            ReviewItemRepository reviewItemRepository) {
        this(databaseInitializer, productNameNormalizer, quantityUnitParser, duplicateDetectionPolicy,
                ownerNormalizer, purchaseTimeNormalizer, importBatchRepository, purchaseRecordRepository,
                reviewItemRepository, legacyReviewProperties());
    }

    /**
     * 录入手动或自然语言抽取后的结构化购买记录。
     *
     * <p>该方法是 record-purchase 用例的应用层入口，负责完成命令校验、数据库初始化、
     * 导入批次创建、逐条购买记录预处理、记录保存、复核项创建和结果汇总。</p>
     *
     * <p>当 dryRun 为 true 时，仅执行校验、归一化、单价计算、去重判断和复核原因收集，
     * 不创建导入批次、不写入购买记录、不创建复核项。</p>
     *
     * @param command 手动购买记录录入命令，不能为空，且 dryRun 和 records 必须有效
     * @return 手动购买记录录入结果，包括是否 dryRun、成功保存数量、复核数量和逐条处理结果
     */
    public RecordPurchaseResult record(RecordPurchaseCommand command) {
        validate(command);
        boolean dryRun = command.dryRun();
        databaseInitializer.initialize();

        String sourceFile = SOURCE_PREFIX + ":" + ClockUtils.nowText();
        Long batchId = dryRun ? null : importBatchRepository.create(sourceFile);
        Set<String> currentBatchFingerprints = new HashSet<>();
        List<RecordPurchaseResult.RecordResult> results = new ArrayList<>();
        int savedCount = 0;
        int reviewCount = 0;

        for (RecordPurchaseCommand.Item input : command.records()) {
            PreparedManualRecord prepared = prepare(input, batchId, sourceFile, currentBatchFingerprints, dryRun);
            Long recordId = null;
            if (!dryRun) {
                recordId = purchaseRecordRepository.save(prepared.record());
                savedCount++;
                for (ReviewReason reason : prepared.reviewReasons()) {
                    reviewItemRepository.create(recordId, reason.code(), reason.message());
                    reviewCount++;
                }
            } else {
                reviewCount += prepared.reviewReasons().size();
            }
            results.add(new RecordPurchaseResult.RecordResult(
                    recordId,
                    input.productName(),
                    prepared.record().normalizedName(),
                    prepared.record().totalAmount(),
                    prepared.record().quantity(),
                    prepared.record().unit(),
                    prepared.record().unitPrice(),
                    prepared.record().decision(),
                    !prepared.reviewReasons().isEmpty(),
                    prepared.reviewReasons().stream().map(ReviewReason::message).toList()
            ));
        }

        if (!dryRun) {
            importBatchRepository.complete(batchId, command.records().size(), savedCount, reviewCount);
        }
        return new RecordPurchaseResult(dryRun, savedCount, reviewCount, results);
    }

    /**
     * 预处理单条手动购买记录。
     *
     * <p>该方法负责将应用层命令中的单条输入转换为可保存的购买记录候选对象，
     * 并在转换过程中完成商品名称归一化、规格兜底、单位换算、单价计算、批内/历史去重、
     * 未来日期识别、价格区间异常识别和复核原因收集。</p>
     *
     * <p>该方法只返回准备结果，不直接写入数据库，也不直接创建复核项。
     * 是否保存记录和创建复核项由 {@link #record(RecordPurchaseCommand)} 统一控制。</p>
     *
     * @param input                    单条手动购买记录录入命令明细
     * @param batchId                  当前导入批次 ID；dryRun 场景下为空
     * @param sourceFile               本次手动录入生成的来源标识
     * @param currentBatchFingerprints 当前批次内已处理记录的去重指纹集合
     * @param dryRun                   是否只预览不写入数据库
     * @return 预处理后的购买记录和对应复核原因
     */
    private PreparedManualRecord prepare(RecordPurchaseCommand.Item input,
                                         Long batchId,
                                         String sourceFile,
                                         Set<String> currentBatchFingerprints,
                                         boolean dryRun) {
        String productName = input.productName().trim();
        String sku = resolveSku(input.sku());
        ProductNameNormalizationResult nameResult = productNameNormalizer.normalize(productName, sku);
        // 负向别名是人工确认过的误判样本：自动排除，但不再创建商品归一化复核项。
        boolean negativeAliasExcluded = isProductNegativeAlias(nameResult);

        double resolvedQuantity = input.quantity();
        String resolvedUnit = input.unit().trim();
        double unitPrice = input.price() / resolvedQuantity;
        List<ReviewReason> reviewReasons = new ArrayList<>();

        String targetUnit = nameResult.targetUnit();
        if (shouldCreateProductNameReview(nameResult)) {
            reviewReasons.add(new ReviewReason(REVIEW_REASON_PRODUCT_NAME_NORMALIZATION,
                    "商品归一化置信度较低，matchedRule=" + nameResult.matchedRule()));
        }
        if (targetUnit != null && !targetUnit.isBlank() && !sameUnit(resolvedUnit, targetUnit)) {
            QuantityUnitParseResult quantityResult = quantityUnitParser.parse(
                    nameResult.normalizedName(),
                    targetUnit,
                    productName,
                    sku,
                    input.price(),
                    input.quantity(),
                    resolvedUnit
            );
            if (!quantityResult.needReview()
                    && quantityResult.quantity() != null
                    && quantityResult.quantity() > 0D
                    && sameUnit(quantityResult.unit(), targetUnit)) {
                resolvedQuantity = quantityResult.quantity();
                resolvedUnit = quantityResult.unit();
                unitPrice = quantityResult.unitPrice();
            } else {
                reviewReasons.add(new ReviewReason(REVIEW_REASON_UNIT_MISMATCH_UNPARSED,
                        "请求单位 " + resolvedUnit + " 与目标单位 " + targetUnit + " 不一致，且无法从 SKU/商品名解析为目标单位。"));
            }
        }

        PurchaseRecord candidate = new PurchaseRecord(
                null, batchId, purchaseTimeNormalizer.normalizeManualPurchaseDate(input.purchaseDate()), resolvePlatform(input.platform()),
                ownerNormalizer.normalize(input.owner()), productName, nameResult.normalizedName(), sku,
                "", "", resolvedQuantity, resolvedUnit, input.price(), input.price(), input.price(), null,
                AMOUNT_SOURCE_MANUAL_RECORD, unitPrice, DEFAULT_CURRENCY, DECISION_INCLUDE, false, DEDUPE_STATUS_UNIQUE, sourceFile,
                input.shopName(), input.note(), input.sourceText(), ClockUtils.nowText()
        );
        boolean duplicate = duplicateDetectionPolicy.isDuplicate(candidate, currentBatchFingerprints,
                !dryRun && purchaseRecordRepository.existsDuplicate(candidate));
        if (duplicate) {
            reviewReasons.add(new ReviewReason(REVIEW_REASON_DUPLICATE_ORDER,
                    "疑似重复手动录入记录，已默认排除统计；如确认不是重复购买，可人工复核为 include。"));
        }
        if (isFuturePurchaseTime(candidate.orderTime())) {
            reviewReasons.add(new ReviewReason(REVIEW_REASON_FUTURE_PURCHASE_TIME,
                    "购买时间晚于当前时间，疑似自然语言日期抽取错误，需要人工确认"));
        }
        if (reviewReasons.isEmpty() && !Boolean.TRUE.equals(input.confirmOutOfRange())) {
            detectPriceOutOfBaselineRange(candidate, reviewReasons);
        }
        String decision = reviewReasons.isEmpty() && !nameResult.needReview() && !negativeAliasExcluded ? DECISION_INCLUDE : DECISION_EXCLUDE;
        PurchaseRecord record = new PurchaseRecord(
                null, batchId, candidate.orderTime(), candidate.platform(), candidate.owner(), candidate.productName(),
                candidate.normalizedName(), candidate.sku(), candidate.category(), candidate.subCategory(),
                candidate.quantity(), candidate.unit(), candidate.totalAmount(), candidate.productAmount(),
                candidate.paidAmount(), candidate.shippingFee(), candidate.amountSource(), candidate.unitPrice(),
                candidate.currency(), decision, duplicate, duplicate ? DEDUPE_STATUS_DUPLICATE : DEDUPE_STATUS_UNIQUE,
                candidate.sourceFile(), candidate.shopName(), candidate.note(), candidate.sourceText(), nameResult.matchedRule(),
                candidate.createdAt()
        );
        return new PreparedManualRecord(record, reviewReasons);
    }

    /**
     * 判断购买时间是否晚于当天结束时间。
     *
     * @param orderTime 格式为 yyyy-MM-dd HH:mm:ss 的购买时间字符串
     * @return 如果购买时间晚于当天 23:59:59 则返回 true
     */
    private boolean isFuturePurchaseTime(String orderTime) {
        LocalDateTime purchaseTime = LocalDateTime.parse(orderTime, PURCHASE_TIME_FORMAT);
        LocalDateTime todayEnd = LocalDate.now().atTime(23, 59, 59);
        return purchaseTime.isAfter(todayEnd);
    }

    /**
     * 检测当前单价是否明显超出历史价格区间，并将复核原因加入列表。
     *
     * <p>该方法基于历史价格区间（最低价 × 0.8、最高价 × 1.2）判断当前单价是否异常，
     * 命中时向 reviewReasons 列表追加 PRICE_OUT_OF_BASELINE_RANGE 复核原因。
     * 该方法只收集复核原因，不直接写入数据库。</p>
     *
     * @param candidate     待检测的购买记录候选对象
     * @param reviewReasons 复核原因列表，检测结果会追加到此列表中
     */
    private void detectPriceOutOfBaselineRange(PurchaseRecord candidate, List<ReviewReason> reviewReasons) {
        if (candidate.unitPrice() == null || candidate.unit() == null || candidate.unit().isBlank()) {
            return;
        }
        PurchaseRecordRepository.PriceRangeStats stats = purchaseRecordRepository.priceRangeStats(
                candidate.normalizedName(), candidate.unit());
        if (stats == null
                || stats.sampleSize() < OUT_OF_RANGE_SAMPLE_THRESHOLD
                || stats.historicalMin() == null
                || stats.historicalMax() == null) {
            return;
        }
        double unitPrice = candidate.unitPrice();
        if (unitPrice < stats.historicalMin() * LOW_PRICE_FACTOR) {
            reviewReasons.add(new ReviewReason(REVIEW_REASON_PRICE_OUT_OF_BASELINE_RANGE,
                    String.format(Locale.ROOT,
                            "当前单价 %.6f 元/%s 明显低于历史最低 %.6f 元/%s，疑似价格、数量或单位抽取错误，需要用户确认。",
                            unitPrice, candidate.unit(), stats.historicalMin(), candidate.unit())));
        } else if (unitPrice > stats.historicalMax() * HIGH_PRICE_FACTOR) {
            reviewReasons.add(new ReviewReason(REVIEW_REASON_PRICE_OUT_OF_BASELINE_RANGE,
                    String.format(Locale.ROOT,
                            "当前单价 %.6f 元/%s 明显高于历史最高 %.6f 元/%s，疑似规格、单位或商品归一化错误，需要用户确认。",
                            unitPrice, candidate.unit(), stats.historicalMax(), candidate.unit())));
        }
    }

    /**
     * 判断商品归一化结果是否命中负向别名规则。
     *
     * <p>负向别名是人工确认过的误判样本，命中时应自动排除，但不再创建商品归一化复核项。</p>
     *
     * @param nameResult 商品名称归一化结果
     * @return 命中 product_negative_alias 规则时返回 true
     */
    private boolean isProductNegativeAlias(ProductNameNormalizationResult nameResult) {
        return NORMALIZATION_RULE_PRODUCT_NEGATIVE_ALIAS.equals(nameResult.matchedRule());
    }

    /**
     * 判断当前归一化结果是否需要创建商品名称归一化复核项。
     *
     * <p>legacy_fallback 规则在 llm_suggestion/silent_exclude 模式下不立即创建逐条复核，
     * 避免导入时制造大量复核噪音；其余低置信结果均需要创建复核项。</p>
     *
     * @param nameResult 商品名称归一化结果
     * @return 需要创建复核项时返回 true
     */
    private boolean shouldCreateProductNameReview(ProductNameNormalizationResult nameResult) {
        if (!nameResult.needReview()) {
            return false;
        }
        // legacy_fallback 在 llm_suggestion/silent_exclude 模式下不立即创建逐条归一化复核。
        if (NORMALIZATION_RULE_LEGACY_FALLBACK.equals(nameResult.matchedRule())) {
            return normalizationProperties.immediateFallbackReview();
        }
        return true;
    }

    /**
     * 校验手动购买记录录入命令。
     *
     * <p>该方法只做应用服务入口层面的基础校验，确保命令对象、dryRun 标记和 records 列表存在。
     * 单条记录中的商品名称、价格、数量、单位等字段校验由 REST 请求 DTO 的 Jakarta Validation
     * 以及后续业务处理逻辑共同承担。</p>
     *
     * @param command 手动购买记录录入命令
     * @throws IllegalArgumentException 当命令为空、dryRun 为空或 records 为空时抛出
     */
    private void validate(RecordPurchaseCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (command.dryRun() == null) {
            throw new IllegalArgumentException("dryRun 必填");
        }
        if (command.records() == null || command.records().isEmpty()) {
            throw new IllegalArgumentException("records 必填且不能为空");
        }
    }

    /**
     * 将常见中文/英文平台名称归一为内部平台标识。
     *
     * <p>支持淘宝/淘宝网/taobao、京东/京东自营/jd、拼多多/pdd、天猫/tmall、
     * 线下/超市/便利店/offline 等常见别名。未知英文平台转小写，未知中文平台保留原文，
     * 未提供平台时返回 manual。</p>
     *
     * @param platform 用户输入的平台名称，允许为 null 或空白
     * @return 归一化后的平台标识
     */
    private String resolvePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return PLATFORM_MANUAL;
        }
        String normalized = platform.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (List.of(PLATFORM_ALIAS_TAOBAO, PLATFORM_ALIAS_TAOBAO_WEB).contains(normalized) || PLATFORM_TAOBAO.equals(lower)) {
            return PLATFORM_TAOBAO;
        }
        if (List.of(PLATFORM_ALIAS_JD, PLATFORM_ALIAS_JD_SELF_OPERATED).contains(normalized) || PLATFORM_JD.equals(lower)) {
            return PLATFORM_JD;
        }
        if (PLATFORM_ALIAS_PDD.equals(normalized) || PLATFORM_PDD.equals(lower)) {
            return PLATFORM_PDD;
        }
        if (PLATFORM_ALIAS_TMALL.equals(normalized) || PLATFORM_TMALL.equals(lower)) {
            return PLATFORM_TMALL;
        }
        if (List.of(PLATFORM_ALIAS_OFFLINE, PLATFORM_ALIAS_SUPERMARKET, PLATFORM_ALIAS_CONVENIENCE_STORE).contains(normalized) || PLATFORM_OFFLINE.equals(lower)) {
            return PLATFORM_OFFLINE;
        }
        if (normalized.chars().allMatch(ch -> ch < 128)) {
            return lower;
        }
        return normalized;
    }

    /**
     * 判断两个单位字符串是否语义相同（忽略大小写和首尾空白）。
     *
     * @param left  单位字符串
     * @param right 单位字符串
     * @return 归一化后相等则返回 true
     */
    private boolean sameUnit(String left, String right) {
        return normalizeUnit(left).equals(normalizeUnit(right));
    }

    /**
     * 归一化单位字符串：trim 并转小写，null 视为空字符串。
     *
     * @param unit 单位字符串，允许为 null
     * @return 归一化后的单位字符串
     */
    private String normalizeUnit(String unit) {
        return unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 解析 SKU 值，用户未提供时返回默认占位值。
     *
     * <p>SKU 为空或空白时使用 {@link #DEFAULT_SKU_PLACEHOLDER} 作为缺省值，
     * 避免商品名称归一化和 aliasKey 生成缺少规格上下文。</p>
     *
     * @param value 用户输入的 SKU 值，允许为 null 或空白
     * @return 非空的 SKU 字符串
     */
    private String resolveSku(String value) {
        return value == null || value.isBlank() ? DEFAULT_SKU_PLACEHOLDER : value.trim();
    }

    /**
     * 手动购买记录预处理结果，包含可保存的购买记录和对应的复核原因列表。
     *
     * @param record        预处理后的购买记录
     * @param reviewReasons 本次预处理收集的复核原因
     */
    private record PreparedManualRecord(PurchaseRecord record, List<ReviewReason> reviewReasons) {
    }

    /**
     * 复核原因，包含原因码和中文提示信息。
     *
     * @param code    复核原因码，例如 DUPLICATE_ORDER、PRODUCT_NAME_NORMALIZATION_REVIEW
     * @param message 中文复核提示信息
     */
    private record ReviewReason(String code, String message) {
    }

    /**
     * 创建旧模式兜底归一化配置。
     *
     * <p>该方法用于兼容旧测试或未注入 NormalizationProperties 的构造场景，
     * 默认使用 immediate_review 模式，确保 legacy_fallback 商品在导入时立即创建复核项。</p>
     *
     * @return 包含兜底配置的 NormalizationProperties 实例
     */
    private static NormalizationProperties legacyReviewProperties() {
        NormalizationProperties properties = new NormalizationProperties();
        properties.setFallbackReviewMode(FALLBACK_REVIEW_MODE_IMMEDIATE_REVIEW);
        return properties;
    }
}
