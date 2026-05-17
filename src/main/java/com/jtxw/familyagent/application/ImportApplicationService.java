package com.jtxw.familyagent.application;

import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.ImportResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import com.jtxw.familyagent.domain.policy.DuplicateDetectionPolicy;
import com.jtxw.familyagent.domain.policy.ProductNormalizer;
import com.jtxw.familyagent.domain.policy.UnitPriceCalculator;
import com.jtxw.familyagent.infrastructure.importer.CsvPurchaseImporter;
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
    private final DuplicateDetectionPolicy duplicateDetectionPolicy;
    private final ProductNormalizer productNormalizer;
    private final UnitPriceCalculator unitPriceCalculator;
    private final ImportBatchRepository importBatchRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ReviewItemRepository reviewItemRepository;

    public ImportApplicationService(DatabaseInitializer databaseInitializer,
                                    CsvPurchaseImporter csvPurchaseImporter,
                                    DuplicateDetectionPolicy duplicateDetectionPolicy,
                                    ProductNormalizer productNormalizer,
                                    UnitPriceCalculator unitPriceCalculator,
                                    ImportBatchRepository importBatchRepository,
                                    PurchaseRecordRepository purchaseRecordRepository,
                                    ReviewItemRepository reviewItemRepository) {
        this.databaseInitializer = databaseInitializer;
        this.csvPurchaseImporter = csvPurchaseImporter;
        this.duplicateDetectionPolicy = duplicateDetectionPolicy;
        this.productNormalizer = productNormalizer;
        this.unitPriceCalculator = unitPriceCalculator;
        this.importBatchRepository = importBatchRepository;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
    }

    /**
     * 导入订单文件并写入本地数据库。
     *
     * <p>导入流程包括：读取 CSV 文件、商品名称归一化、单位价格计算、重复订单检测、
     * 订单明细入库，并将实付金额为 0 或疑似重复的记录加入待复核列表。</p>
     *
     * @param file 本地订单 CSV 文件路径
     * @return 导入结果
     */
    public ImportResult importCsv(Path file) {
        databaseInitializer.initialize();
        long batchId = importBatchRepository.create(file.toString());
        List<RawPurchaseRecord> rawRecords = csvPurchaseImporter.importFile(file);
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
            Double unitPrice = null;
            if (raw.totalAmount() != null && raw.quantity() != null && raw.quantity() > 0) {
                unitPrice = unitPriceCalculator.calculate(raw.totalAmount(), raw.quantity());
            }
            // 候选记录先按正常订单构造，用于生成去重指纹和查询历史重复
            PurchaseRecord candidate = new PurchaseRecord(
                    null, batchId, raw.orderTime(), raw.platform(), raw.owner(), raw.productName(), normalizedName,
                    raw.sku(), raw.category(), raw.subCategory(), raw.quantity(), raw.unit(), raw.totalAmount(),
                    unitPrice, raw.currency(), "include", false, "unique", file.toString(), ClockUtils.nowText()
            );
            // 同时检查历史数据库和当前批次，避免重复导入影响价格统计和月度报告
            boolean duplicate = duplicateDetectionPolicy.isDuplicate(candidate, currentBatchFingerprints,
                    purchaseRecordRepository.existsDuplicate(candidate));
            // 疑似重复订单默认排除统计，后续可通过人工复核恢复纳入
            PurchaseRecord record = new PurchaseRecord(
                    null, batchId, raw.orderTime(), raw.platform(), raw.owner(), raw.productName(), normalizedName,
                    raw.sku(), raw.category(), raw.subCategory(), raw.quantity(), raw.unit(), raw.totalAmount(),
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
            } else if (raw.totalAmount() != null && raw.totalAmount() == 0D) {
                // 0 元记录可能是赠品、售后或抵扣场景，继续沿用人工复核流程
                reviewItemRepository.create(recordId, "ZERO_PAYMENT", "实付金额为 0，需确认是否赠品、试用、售后补发或购物金抵扣。 ");
                reviewCount++;
            }
        }
        importBatchRepository.complete(batchId, rawRecords.size(), importedCount, reviewCount);
        return new ImportResult(batchId, rawRecords.size(), importedCount, reviewCount,
                "导入完成：共 " + rawRecords.size() + " 条，成功 " + importedCount + " 条，疑似重复 "
                        + duplicateCount + " 条，待复核 " + reviewCount + " 条。");
    }
}
