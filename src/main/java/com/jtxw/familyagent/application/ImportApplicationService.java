package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.ImportFileCommand;
import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.ImportResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import com.jtxw.familyagent.domain.policy.DuplicateDetectionPolicy;
import com.jtxw.familyagent.domain.policy.PaymentAdjustmentPolicy;
import com.jtxw.familyagent.domain.policy.OwnerNormalizer;
import com.jtxw.familyagent.domain.policy.LearningProductNameNormalizer;
import com.jtxw.familyagent.domain.policy.ProductNameNormalizationResult;
import com.jtxw.familyagent.domain.policy.PurchaseTimeNormalizer;
import com.jtxw.familyagent.domain.policy.QuantityUnitParseResult;
import com.jtxw.familyagent.domain.policy.QuantityUnitParser;
import com.jtxw.familyagent.domain.policy.UnitPriceCalculator;
import com.jtxw.familyagent.infrastructure.importer.CsvPurchaseImporter;
import com.jtxw.familyagent.infrastructure.importer.ExcelPurchaseImporter;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.ImportBatchRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:27:12
 * @Description: 导入应用服务，编排 CSV 解析、记录标准化、入库和异常复核创建。
 */
@Service
public class ImportApplicationService {
    private final DatabaseInitializer databaseInitializer;
    private final CsvPurchaseImporter csvPurchaseImporter;
    private final ExcelPurchaseImporter excelPurchaseImporter;
    private final DuplicateDetectionPolicy duplicateDetectionPolicy;
    private final PaymentAdjustmentPolicy paymentAdjustmentPolicy;
    private final OwnerNormalizer ownerNormalizer;
    private final PurchaseTimeNormalizer purchaseTimeNormalizer;
    private final LearningProductNameNormalizer productNameNormalizer;
    private final QuantityUnitParser quantityUnitParser;
    private final UnitPriceCalculator unitPriceCalculator;
    private final ImportBatchRepository importBatchRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final NormalizationProperties normalizationProperties;

    @Autowired
    public ImportApplicationService(DatabaseInitializer databaseInitializer,
                                    CsvPurchaseImporter csvPurchaseImporter,
                                    ExcelPurchaseImporter excelPurchaseImporter,
                                    DuplicateDetectionPolicy duplicateDetectionPolicy,
                                    PaymentAdjustmentPolicy paymentAdjustmentPolicy,
                                    OwnerNormalizer ownerNormalizer,
                                    PurchaseTimeNormalizer purchaseTimeNormalizer,
                                    LearningProductNameNormalizer productNameNormalizer,
                                    QuantityUnitParser quantityUnitParser,
                                    UnitPriceCalculator unitPriceCalculator,
                                    ImportBatchRepository importBatchRepository,
                                    PurchaseRecordRepository purchaseRecordRepository,
                                    ReviewItemRepository reviewItemRepository,
                                    NormalizationProperties normalizationProperties) {
        this.databaseInitializer = databaseInitializer;
        this.csvPurchaseImporter = csvPurchaseImporter;
        this.excelPurchaseImporter = excelPurchaseImporter;
        this.duplicateDetectionPolicy = duplicateDetectionPolicy;
        this.paymentAdjustmentPolicy = paymentAdjustmentPolicy;
        this.ownerNormalizer = ownerNormalizer;
        this.purchaseTimeNormalizer = purchaseTimeNormalizer;
        this.productNameNormalizer = productNameNormalizer;
        this.quantityUnitParser = quantityUnitParser;
        this.unitPriceCalculator = unitPriceCalculator;
        this.importBatchRepository = importBatchRepository;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.normalizationProperties = normalizationProperties;
    }

    public ImportApplicationService(DatabaseInitializer databaseInitializer,
                                    CsvPurchaseImporter csvPurchaseImporter,
                                    ExcelPurchaseImporter excelPurchaseImporter,
                                    DuplicateDetectionPolicy duplicateDetectionPolicy,
                                    PaymentAdjustmentPolicy paymentAdjustmentPolicy,
                                    OwnerNormalizer ownerNormalizer,
                                    PurchaseTimeNormalizer purchaseTimeNormalizer,
                                    LearningProductNameNormalizer productNameNormalizer,
                                    QuantityUnitParser quantityUnitParser,
                                    UnitPriceCalculator unitPriceCalculator,
                                    ImportBatchRepository importBatchRepository,
                                    PurchaseRecordRepository purchaseRecordRepository,
                                    ReviewItemRepository reviewItemRepository) {
        this(databaseInitializer, csvPurchaseImporter, excelPurchaseImporter, duplicateDetectionPolicy,
                paymentAdjustmentPolicy, ownerNormalizer, purchaseTimeNormalizer, productNameNormalizer,
                quantityUnitParser, unitPriceCalculator, importBatchRepository, purchaseRecordRepository,
                reviewItemRepository, legacyReviewProperties());
    }

    /**
     * 导入订单文件并写入本地数据库。
     *
     * <p>导入流程包括：读取订单文件、商品名称归一化、单位价格计算、重复订单检测、
     * 订单明细入库，并将金额折算、实付金额为 0 或疑似重复的记录加入待复核列表。</p>
     *
     * @param file 本地订单文件路径
     * @return 导入结果
     */
    public ImportResult importCsv(Path file) {
        return importFile(new ImportFileCommand(file, null));
    }

    /**
     * 导入订单文件并写入本地数据库。
     *
     * @param file          本地订单文件路径
     * @param ownerOverride 导入时指定的订单归属人，为空时由导入器从文件字段或文件名识别
     * @return 导入结果
     */
    public ImportResult importCsv(Path file, String ownerOverride) {
        return importFile(new ImportFileCommand(file, ownerOverride));
    }

    /**
     * 导入订单文件并写入本地数据库。
     *
     * @param file          本地订单文件路径
     * @param ownerOverride 导入时指定的订单归属人，为空时由导入器从文件字段或文件名识别
     * @return 导入结果
     */
    public ImportResult importFile(Path file, String ownerOverride) {
        return importFile(new ImportFileCommand(file, ownerOverride));
    }

    /**
     * 导入订单文件并写入本地数据库。
     *
     * <p>导入流程包括：读取订单文件、商品名称归一化、单位价格计算、重复订单检测、
     * 订单明细入库，并将金额折算、实付金额为 0 或疑似重复的记录加入待复核列表。</p>
     *
     * @param command 文件导入命令
     * @return 导入结果
     */
    public ImportResult importFile(ImportFileCommand command) {
        Path file = command.file();
        String ownerOverride = command.ownerOverride();
        databaseInitializer.initialize();
        List<RawPurchaseRecord> rawRecords = importRawRecords(file, ownerOverride);
        long batchId = importBatchRepository.create(file.toString());
        // 当前批次内的订单指纹，用于识别同一个文件中的重复行
        Set<String> currentBatchFingerprints = new HashSet<>();
        // 待复核统计
        int reviewCount = 0;
        // 成功入库统计
        int importedCount = 0;
        // 重复统计
        int duplicateCount = 0;
        for (RawPurchaseRecord raw : rawRecords) {
            String normalizedOrderTime = purchaseTimeNormalizer.normalizeImportedOrderTime(raw.orderTime());
            String normalizedOwner = ownerNormalizer.normalize(raw.owner());
            // 后端导入链路内完成商品归一化，MCP Server 只负责把 import_file 请求转发到这里。
            ProductNameNormalizationResult nameResult = productNameNormalizer.normalize(raw.productName(), raw.sku());
            String normalizedName = nameResult.normalizedName();
            // 负向别名是人工确认过的误判样本：自动排除，但不再生成归一化复核噪音。
            boolean negativeAliasExcluded = isProductNegativeAlias(nameResult);
            PaymentAdjustmentPolicy.PaymentAdjustmentResult amountResult = paymentAdjustmentPolicy.adjust(raw);
            Double totalAmount = amountResult.totalAmount();
            // 规格解析使用归一化后的标准品类和目标单位；高置信结果会覆盖原始“件”等粗粒度单位。
            QuantityUnitParseResult quantityResult = quantityUnitParser.parse(
                    nameResult.normalizedName(),
                    nameResult.targetUnit(),
                    raw.productName(),
                    raw.sku(),
                    totalAmount,
                    raw.quantity(),
                    raw.unit()
            );
            Double resolvedQuantity = quantityResult.quantity() == null ? raw.quantity() : quantityResult.quantity();
            String resolvedUnit = quantityResult.unit() == null || quantityResult.unit().isBlank()
                    ? raw.unit()
                    : quantityResult.unit();
            Double unitPrice = quantityResult.unitPrice();
            if (unitPrice == null && totalAmount != null && resolvedQuantity != null && resolvedQuantity > 0) {
                unitPrice = unitPriceCalculator.calculate(totalAmount, resolvedQuantity);
            }
            // 低置信规格仍保留购买记录，便于人工复核，但默认排除出正式价格基准线
            boolean normalizationReviewRequired = nameResult.needReview() || quantityResult.needReview();
            // 候选记录先按正常订单构造，用于生成去重指纹和查询历史重复
            PurchaseRecord candidate = new PurchaseRecord(
                    null, batchId, normalizedOrderTime, raw.platform(), normalizedOwner, raw.productName(), normalizedName,
                    raw.sku(), raw.category(), raw.subCategory(), resolvedQuantity, resolvedUnit, totalAmount,
                    raw.productAmount(), raw.paidAmount(), raw.shippingFee(), amountResult.amountSource(),
                    unitPrice, raw.currency(), "include", false, "unique", file.toString(), ClockUtils.nowText()
            );
            // 同时检查历史数据库和当前批次，避免重复导入影响价格统计和价格报告
            boolean duplicate = duplicateDetectionPolicy.isDuplicate(candidate, currentBatchFingerprints,
                    purchaseRecordRepository.existsDuplicate(candidate));
            // 疑似重复订单默认排除统计，后续可通过人工复核恢复纳入
            PurchaseRecord record = new PurchaseRecord(
                    null, batchId, normalizedOrderTime, raw.platform(), normalizedOwner, raw.productName(), normalizedName,
                    raw.sku(), raw.category(), raw.subCategory(), resolvedQuantity, resolvedUnit, totalAmount,
                    raw.productAmount(), raw.paidAmount(), raw.shippingFee(), amountResult.amountSource(),
                    unitPrice, raw.currency(), duplicate || normalizationReviewRequired || negativeAliasExcluded ? "exclude" : "include", duplicate,
                    duplicate ? "duplicate" : "unique", file.toString(), null, null, null,
                    nameResult.matchedRule(), ClockUtils.nowText()
            );
            long recordId = purchaseRecordRepository.save(record);
            importedCount++;
            if (duplicate) {
                // 重复记录需要人工确认，避免误伤真实的二次购买
                reviewItemRepository.create(recordId, "DUPLICATE_ORDER",
                        "疑似重复订单，已默认排除统计；如确认不是重复购买，可人工复核为 include。");
                reviewCount++;
                duplicateCount++;
            }
            if (shouldCreateProductNameReview(nameResult)) {
                reviewItemRepository.create(recordId, "PRODUCT_NAME_NORMALIZATION_REVIEW",
                        "商品归一化置信度较低，matchedRule=" + nameResult.matchedRule()
                                + "，confidence=" + nameResult.confidence());
                reviewCount++;
            }
            if (quantityResult.needReview()) {
                // 规格数量不明确时创建复核项，并因 decision=exclude 不进入 purchase_records 的正式统计查询。
                reviewItemRepository.create(recordId, "QUANTITY_UNIT_PARSE_REVIEW",
                        "规格数量解析置信度较低，evidence=" + quantityResult.parseEvidence()
                                + "，confidence=" + quantityResult.confidence());
                reviewCount++;
            }
            if (amountResult.reviewRequired()) {
                // 金额折算会影响单价和价格统计，必须保留人工确认入口
                reviewItemRepository.create(recordId, amountResult.reviewReasonCode(), amountResult.reviewReasonMessage());
                reviewCount++;
            } else if (raw.specReviewRequired()) {
                reviewItemRepository.create(recordId, raw.specReviewReasonCode(), raw.specReviewReasonMessage());
                reviewCount++;
            } else if (totalAmount != null && totalAmount == 0D) {
                if (!isTrustedZeroPayment(raw)) {
                    // 无明确赠品或试用标识的 0 元记录仍需人工确认，避免售后补发、组合支付等场景误入库
                    reviewItemRepository.create(recordId, "ZERO_PAYMENT", "实付金额为 0，需确认是否赠品、试用、售后补发或购物金抵扣。");
                    reviewCount++;
                }
            }
        }
        importBatchRepository.complete(batchId, rawRecords.size(), importedCount, reviewCount);
        return new ImportResult(batchId, rawRecords.size(), importedCount, reviewCount,
                "导入完成：共 " + rawRecords.size() + " 条，成功 " + importedCount + " 条，疑似重复 "
                        + duplicateCount + " 条，待复核 " + reviewCount + " 条。");
    }

    private List<RawPurchaseRecord> importRawRecords(Path file, String ownerOverride) {
        String filename = file.getFileName().toString().toLowerCase();
        if (filename.endsWith(".csv")) {
            return csvPurchaseImporter.importFile(file, ownerOverride);
        }
        if (filename.endsWith(".xlsx")) {
            return excelPurchaseImporter.importFile(file, ownerOverride);
        }
        throw new IllegalArgumentException("不支持的订单文件类型，仅支持 .csv 和 .xlsx：" + file);
    }

    private boolean isTrustedZeroPayment(RawPurchaseRecord raw) {
        String text = String.join(" ",
                safeText(raw.productName()),
                safeText(raw.sku()),
                safeText(raw.category()),
                safeText(raw.subCategory()));
        return text.contains("赠品") || text.contains("试用");
    }

    private boolean isProductNegativeAlias(ProductNameNormalizationResult nameResult) {
        return "product_negative_alias".equals(nameResult.matchedRule());
    }

    private boolean shouldCreateProductNameReview(ProductNameNormalizationResult nameResult) {
        if (!nameResult.needReview()) {
            return false;
        }
        // legacy_fallback 是 LLM Advisor 的唯一候选来源，新模式下先静默 exclude，避免导入时制造大量逐条复核噪音。
        if ("legacy_fallback".equals(nameResult.matchedRule())) {
            return normalizationProperties.immediateFallbackReview();
        }
        return true;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private static NormalizationProperties legacyReviewProperties() {
        NormalizationProperties properties = new NormalizationProperties();
        properties.setFallbackReviewMode("immediate_review");
        return properties;
    }
}
