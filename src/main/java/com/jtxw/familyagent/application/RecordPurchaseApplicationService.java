package com.jtxw.familyagent.application;

import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.RecordPurchaseRequest;
import com.jtxw.familyagent.domain.model.RecordPurchaseResult;
import com.jtxw.familyagent.domain.policy.DuplicateDetectionPolicy;
import com.jtxw.familyagent.domain.policy.OwnerNormalizer;
import com.jtxw.familyagent.domain.policy.LearningProductNameNormalizer;
import com.jtxw.familyagent.domain.policy.ProductNameNormalizationResult;
import com.jtxw.familyagent.domain.policy.PurchaseTimeNormalizer;
import com.jtxw.familyagent.domain.policy.QuantityUnitParseResult;
import com.jtxw.familyagent.domain.policy.QuantityUnitParser;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    public RecordPurchaseResult record(RecordPurchaseRequest request) {
        validate(request);
        boolean dryRun = request.dryRun();
        databaseInitializer.initialize();

        String sourceFile = SOURCE_PREFIX + ":" + ClockUtils.nowText();
        Long batchId = dryRun ? null : importBatchRepository.create(sourceFile);
        Set<String> currentBatchFingerprints = new HashSet<>();
        List<RecordPurchaseResult.RecordResult> results = new ArrayList<>();
        int savedCount = 0;
        int reviewCount = 0;

        for (RecordPurchaseRequest.Record input : request.records()) {
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

        if (!dryRun && batchId != null) {
            importBatchRepository.complete(batchId, request.records().size(), savedCount, reviewCount);
        }
        return new RecordPurchaseResult(dryRun, savedCount, reviewCount, results);
    }

    private PreparedManualRecord prepare(RecordPurchaseRequest.Record input,
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

    private void validate(RecordPurchaseRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        if (request.dryRun() == null) {
            throw new IllegalArgumentException("dryRun 必填");
        }
        if (request.records() == null || request.records().isEmpty()) {
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
