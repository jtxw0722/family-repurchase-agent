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
            reviewReasons.add(new ReviewReason("PRODUCT_NAME_NORMALIZATION_REVIEW",
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
                reviewReasons.add(new ReviewReason("UNIT_MISMATCH_UNPARSED",
                        "请求单位 " + resolvedUnit + " 与目标单位 " + targetUnit + " 不一致，且无法从 SKU/商品名解析为目标单位。"));
            }
        }

        PurchaseRecord candidate = new PurchaseRecord(
                null, batchId, purchaseTimeNormalizer.normalizeManualPurchaseDate(input.purchaseDate()), resolvePlatform(input.platform()),
                ownerNormalizer.normalize(input.owner()), productName, nameResult.normalizedName(), sku,
                "", "", resolvedQuantity, resolvedUnit, input.price(), input.price(), input.price(), null,
                "manual_record", unitPrice, "CNY", "include", false, "unique", sourceFile,
                input.shopName(), input.note(), input.sourceText(), ClockUtils.nowText()
        );
        boolean duplicate = duplicateDetectionPolicy.isDuplicate(candidate, currentBatchFingerprints,
                !dryRun && purchaseRecordRepository.existsDuplicate(candidate));
        if (duplicate) {
            reviewReasons.add(new ReviewReason("DUPLICATE_ORDER",
                    "疑似重复手动录入记录，已默认排除统计；如确认不是重复购买，可人工复核为 include。"));
        }
        if (isFuturePurchaseTime(candidate.orderTime())) {
            reviewReasons.add(new ReviewReason("FUTURE_PURCHASE_TIME",
                    "购买时间晚于当前时间，疑似自然语言日期抽取错误，需要人工确认"));
        }
        if (reviewReasons.isEmpty() && !Boolean.TRUE.equals(input.confirmOutOfRange())) {
            detectPriceOutOfBaselineRange(candidate, reviewReasons);
        }
        String decision = reviewReasons.isEmpty() && !nameResult.needReview() && !negativeAliasExcluded ? "include" : "exclude";
        PurchaseRecord record = new PurchaseRecord(
                null, batchId, candidate.orderTime(), candidate.platform(), candidate.owner(), candidate.productName(),
                candidate.normalizedName(), candidate.sku(), candidate.category(), candidate.subCategory(),
                candidate.quantity(), candidate.unit(), candidate.totalAmount(), candidate.productAmount(),
                candidate.paidAmount(), candidate.shippingFee(), candidate.amountSource(), candidate.unitPrice(),
                candidate.currency(), decision, duplicate, duplicate ? "duplicate" : "unique",
                candidate.sourceFile(), candidate.shopName(), candidate.note(), candidate.sourceText(), nameResult.matchedRule(),
                candidate.createdAt()
        );
        return new PreparedManualRecord(record, reviewReasons);
    }

    private boolean isFuturePurchaseTime(String orderTime) {
        LocalDateTime purchaseTime = LocalDateTime.parse(orderTime, PURCHASE_TIME_FORMAT);
        LocalDateTime todayEnd = LocalDate.now().atTime(23, 59, 59);
        return purchaseTime.isAfter(todayEnd);
    }

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
            reviewReasons.add(new ReviewReason("PRICE_OUT_OF_BASELINE_RANGE",
                    String.format(Locale.ROOT,
                            "当前单价 %.6f 元/%s 明显低于历史最低 %.6f 元/%s，疑似价格、数量或单位抽取错误，需要用户确认。",
                            unitPrice, candidate.unit(), stats.historicalMin(), candidate.unit())));
        } else if (unitPrice > stats.historicalMax() * HIGH_PRICE_FACTOR) {
            reviewReasons.add(new ReviewReason("PRICE_OUT_OF_BASELINE_RANGE",
                    String.format(Locale.ROOT,
                            "当前单价 %.6f 元/%s 明显高于历史最高 %.6f 元/%s，疑似规格、单位或商品归一化错误，需要用户确认。",
                            unitPrice, candidate.unit(), stats.historicalMax(), candidate.unit())));
        }
    }

    private boolean isProductNegativeAlias(ProductNameNormalizationResult nameResult) {
        return "product_negative_alias".equals(nameResult.matchedRule());
    }

    private boolean shouldCreateProductNameReview(ProductNameNormalizationResult nameResult) {
        if (!nameResult.needReview()) {
            return false;
        }
        // legacy_fallback 在 llm_suggestion/silent_exclude 模式下不立即创建逐条归一化复核。
        if ("legacy_fallback".equals(nameResult.matchedRule())) {
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

    private String resolvePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "manual";
        }
        String normalized = platform.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (List.of("淘宝", "淘宝网").contains(normalized) || "taobao".equals(lower)) {
            return "taobao";
        }
        if (List.of("京东", "京东自营").contains(normalized) || "jd".equals(lower)) {
            return "jd";
        }
        if ("拼多多".equals(normalized) || "pdd".equals(lower)) {
            return "pdd";
        }
        if ("天猫".equals(normalized) || "tmall".equals(lower)) {
            return "tmall";
        }
        if (List.of("线下", "超市", "便利店").contains(normalized) || "offline".equals(lower)) {
            return "offline";
        }
        if (normalized.chars().allMatch(ch -> ch < 128)) {
            return lower;
        }
        return normalized;
    }

    private boolean sameUnit(String left, String right) {
        return normalizeUnit(left).equals(normalizeUnit(right));
    }

    private String normalizeUnit(String unit) {
        return unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveSku(String value) {
        return value == null || value.isBlank() ? "暂无" : value.trim();
    }

    private record PreparedManualRecord(PurchaseRecord record, List<ReviewReason> reviewReasons) {
    }

    private record ReviewReason(String code, String message) {
    }

    private static NormalizationProperties legacyReviewProperties() {
        NormalizationProperties properties = new NormalizationProperties();
        properties.setFallbackReviewMode("immediate_review");
        return properties;
    }
}
