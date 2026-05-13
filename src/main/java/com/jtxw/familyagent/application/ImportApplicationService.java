package com.jtxw.familyagent.application;

import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.ImportResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import com.jtxw.familyagent.domain.policy.ProductNormalizer;
import com.jtxw.familyagent.domain.policy.UnitPriceCalculator;
import com.jtxw.familyagent.infrastructure.importer.CsvPurchaseImporter;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.ImportBatchRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/12/16:42
 * @Description: 导入应用服务，编排 CSV 解析、记录标准化、入库和异常复核创建。
 */
@Service
public class ImportApplicationService {
    private final DatabaseInitializer databaseInitializer;
    private final CsvPurchaseImporter csvPurchaseImporter;
    private final ProductNormalizer productNormalizer;
    private final UnitPriceCalculator unitPriceCalculator;
    private final ImportBatchRepository importBatchRepository;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ReviewItemRepository reviewItemRepository;

    public ImportApplicationService(DatabaseInitializer databaseInitializer,
                                    CsvPurchaseImporter csvPurchaseImporter,
                                    ProductNormalizer productNormalizer,
                                    UnitPriceCalculator unitPriceCalculator,
                                    ImportBatchRepository importBatchRepository,
                                    PurchaseRecordRepository purchaseRecordRepository,
                                    ReviewItemRepository reviewItemRepository) {
        this.databaseInitializer = databaseInitializer;
        this.csvPurchaseImporter = csvPurchaseImporter;
        this.productNormalizer = productNormalizer;
        this.unitPriceCalculator = unitPriceCalculator;
        this.importBatchRepository = importBatchRepository;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
    }

    public ImportResult importCsv(Path file) {
        databaseInitializer.initialize();
        long batchId = importBatchRepository.create(file.toString());
        List<RawPurchaseRecord> rawRecords = csvPurchaseImporter.importFile(file);
        int reviewCount = 0;
        int importedCount = 0;
        for (RawPurchaseRecord raw : rawRecords) {
            String normalizedName = productNormalizer.normalize(raw.productName());
            Double unitPrice = null;
            if (raw.totalAmount() != null && raw.quantity() != null && raw.quantity() > 0) {
                unitPrice = unitPriceCalculator.calculate(raw.totalAmount(), raw.quantity());
            }
            PurchaseRecord record = new PurchaseRecord(
                    null, batchId, raw.orderTime(), raw.platform(), raw.owner(), raw.productName(), normalizedName,
                    raw.sku(), raw.category(), raw.subCategory(), raw.quantity(), raw.unit(), raw.totalAmount(),
                    unitPrice, raw.currency(), "include", false, "unique", file.toString(), ClockUtils.nowText()
            );
            long recordId = purchaseRecordRepository.save(record);
            importedCount++;
            if (raw.totalAmount() != null && raw.totalAmount() == 0D) {
                reviewItemRepository.create(recordId, "ZERO_PAYMENT", "实付金额为 0，需确认是否赠品、试用、售后补发或购物金抵扣。 ");
                reviewCount++;
            }
        }
        importBatchRepository.complete(batchId, rawRecords.size(), importedCount, reviewCount);
        return new ImportResult(batchId, rawRecords.size(), importedCount, reviewCount,
                "导入完成：共 " + rawRecords.size() + " 条，成功 " + importedCount + " 条，待复核 " + reviewCount + " 条。");
    }
}
