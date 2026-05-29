package com.jtxw.familyagent.application;

import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.ImportResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import com.jtxw.familyagent.domain.policy.DuplicateDetectionPolicy;
import com.jtxw.familyagent.domain.policy.PaymentAdjustmentPolicy;
import com.jtxw.familyagent.domain.policy.ProductNormalizer;
import com.jtxw.familyagent.domain.policy.UnitPriceCalculator;
import com.jtxw.familyagent.infrastructure.importer.CsvPurchaseImporter;
import com.jtxw.familyagent.infrastructure.importer.ExcelPurchaseImporter;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.ImportBatchRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @Author: jtxw
 * @Date: 2026/05/12/16:42
 * @Description: 导入应用服务，编排 CSV 解析、记录标准化、入库和异常复核创建。
 */
@Service
public class ImportApplicationService {
    private final DatabaseInitializer databaseInitializer;
    private final CsvPurchaseImporter csvPurchaseImporter;
    private final ExcelPurchaseImporter excelPurchaseImporter;
    private final DuplicateDetectionPolicy duplicateDetectionPolicy;
    private final PaymentAdjustmentPolicy paymentAdjustmentPolicy;
    private final ProductNormalizer productNormalizer;
    private final UnitPriceCalculator unitPriceCalculator;
    private final ImportBatchRepository importBatchRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ReviewItemRepository reviewItemRepository;

    public ImportApplicationService(DatabaseInitializer databaseInitializer,
                                    CsvPurchaseImporter csvPurchaseImporter,
                                    ExcelPurchaseImporter excelPurchaseImporter,
                                    DuplicateDetectionPolicy duplicateDetectionPolicy,
                                    PaymentAdjustmentPolicy paymentAdjustmentPolicy,
                                    ProductNormalizer productNormalizer,
                                    UnitPriceCalculator unitPriceCalculator,
                                    ImportBatchRepository importBatchRepository,
                                    PurchaseRecordRepository purchaseRecordRepository,
                                    ReviewItemRepository reviewItemRepository) {
        this.databaseInitializer = databaseInitializer;
        this.csvPurchaseImporter = csvPurchaseImporter;
        this.excelPurchaseImporter = excelPurchaseImporter;
        this.duplicateDetectionPolicy = duplicateDetectionPolicy;
        this.paymentAdjustmentPolicy = paymentAdjustmentPolicy;
        this.productNormalizer = productNormalizer;
        this.unitPriceCalculator = unitPriceCalculator;
        this.importBatchRepository = importBatchRepository;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
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
        return importFile(file, null);
    }

    /**
     * 导入订单文件并写入本地数据库。
     *
     * @param file          本地订单文件路径
     * @param ownerOverride 导入时指定的订单归属人，为空时由导入器从文件字段或文件名识别
     * @return 导入结果
     */
    public ImportResult importCsv(Path file, String ownerOverride) {
        return importFile(file, ownerOverride);
    }

    /**
     * 导入订单文件并写入本地数据库。
     *
     * @param file          本地订单文件路径
     * @param ownerOverride 导入时指定的订单归属人，为空时由导入器从文件字段或文件名识别
     * @return 导入结果
     */
    public ImportResult importFile(Path file, String ownerOverride) {
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
            String normalizedName = productNormalizer.normalize(raw.productName());
            PaymentAdjustmentPolicy.PaymentAdjustmentResult amountResult = paymentAdjustmentPolicy.adjust(raw);
            Double totalAmount = amountResult.totalAmount();
            Double unitPrice = null;
            if (totalAmount != null && raw.quantity() != null && raw.quantity() > 0) {
                unitPrice = unitPriceCalculator.calculate(totalAmount, raw.quantity());
            }
            // 候选记录先按正常订单构造，用于生成去重指纹和查询历史重复
            PurchaseRecord candidate = new PurchaseRecord(
                    null, batchId, raw.orderTime(), raw.platform(), raw.owner(), raw.productName(), normalizedName,
                    raw.sku(), raw.category(), raw.subCategory(), raw.quantity(), raw.unit(), totalAmount,
                    raw.productAmount(), raw.paidAmount(), raw.shippingFee(), amountResult.amountSource(),
                    unitPrice, raw.currency(), "include", false, "unique", file.toString(), ClockUtils.nowText()
            );
            // 同时检查历史数据库和当前批次，避免重复导入影响价格统计和价格报告
            boolean duplicate = duplicateDetectionPolicy.isDuplicate(candidate, currentBatchFingerprints,
                    purchaseRecordRepository.existsDuplicate(candidate));
            // 疑似重复订单默认排除统计，后续可通过人工复核恢复纳入
            PurchaseRecord record = new PurchaseRecord(
                    null, batchId, raw.orderTime(), raw.platform(), raw.owner(), raw.productName(), normalizedName,
                    raw.sku(), raw.category(), raw.subCategory(), raw.quantity(), raw.unit(), totalAmount,
                    raw.productAmount(), raw.paidAmount(), raw.shippingFee(), amountResult.amountSource(),
                    unitPrice, raw.currency(), duplicate ? "exclude" : "include", duplicate,
                    duplicate ? "duplicate" : "unique", file.toString(), ClockUtils.nowText()
            );
            long recordId = purchaseRecordRepository.save(record);
            importedCount++;
            if (duplicate) {
                // 重复记录需要人工确认，避免误伤真实的二次购买
                reviewItemRepository.create(recordId, "DUPLICATE_ORDER",
                        "疑似重复订单，已默认排除统计；如确认不是重复购买，可人工复核为 include。");
                reviewCount++;
                duplicateCount++;
            } else if (amountResult.reviewRequired()) {
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

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
