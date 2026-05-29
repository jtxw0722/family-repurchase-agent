package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.DuplicateDetectionPolicy;
import com.jtxw.familyagent.domain.policy.PaymentAdjustmentPolicy;
import com.jtxw.familyagent.domain.policy.PriceDecisionPolicy;
import com.jtxw.familyagent.domain.policy.ProductNormalizer;
import com.jtxw.familyagent.domain.policy.ProductSpecParser;
import com.jtxw.familyagent.domain.policy.UnitPriceCalculator;
import com.jtxw.familyagent.infrastructure.importer.CsvPurchaseImporter;
import com.jtxw.familyagent.infrastructure.importer.ExcelPurchaseImporter;
import com.jtxw.familyagent.infrastructure.importer.OrderImportMapper;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.ImportBatchRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * @Author: jtxw
 * @Date: 2026/05/29/22:45
 * @Description: 导入规格解析和价格比较命中的集成测试。
 */
class ImportSpecIntegrationTest {
    @Test
    void shouldImportCatLitterAsKgPriceSampleAndMatchComparePrice() throws Exception {
        Fixture fixture = fixture("cat-litter-spec.sqlite");
        Path file = fixture.file("orders.csv");
        Files.writeString(file, """
                order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
                2026-05-01,taobao,jtxw,名创优品钠基矿猫砂5kg*8包,【除臭加倍】升级款自然原味10斤*8包,宠物用品,猫砂,1,件,119.3,CNY
                """, StandardCharsets.UTF_8);

        fixture.importService.importFile(file, "jtxw");

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂");
        assertThat(records).hasSize(1);
        PurchaseRecord record = records.get(0);
        assertThat(record.normalizedName()).isEqualTo("猫砂");
        assertThat(record.quantity()).isEqualTo(40D);
        assertThat(record.unit()).isEqualTo("kg");
        assertThat(record.unitPrice()).isCloseTo(2.9825D, offset(0.00001D));

        PriceDecisionResult result = fixture.priceService.comparePrice("名创优品猫砂", 119.3D, 40D, "kg");

        assertThat(result.baseline().sampleSize()).isGreaterThan(0);
        assertThat(result.evidence().excludedReasons())
                .noneMatch(reason -> reason.contains("单位不一致") && reason.contains("13"));
    }

    private Fixture fixture(String dbName) throws Exception {
        Path dir = Path.of("target", "import-spec-integration-test");
        Files.createDirectories(dir);
        Path db = dir.resolve(dbName);
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer databaseInitializer = new DatabaseInitializer(jdbcTemplate);
        ImportBatchRepository importBatchRepository = new ImportBatchRepository(jdbcTemplate);
        PurchaseRecordRepository purchaseRecordRepository = new PurchaseRecordRepository(jdbcTemplate);
        ReviewItemRepository reviewItemRepository = new ReviewItemRepository(jdbcTemplate);
        ProductSpecParser productSpecParser = new ProductSpecParser();
        OrderImportMapper orderImportMapper = new OrderImportMapper(productSpecParser);
        ProductNormalizer productNormalizer = new ProductNormalizer();
        ImportApplicationService importService = new ImportApplicationService(
                databaseInitializer,
                new CsvPurchaseImporter(orderImportMapper),
                new ExcelPurchaseImporter(orderImportMapper),
                new DuplicateDetectionPolicy(),
                new PaymentAdjustmentPolicy(),
                productNormalizer,
                new UnitPriceCalculator(),
                importBatchRepository,
                purchaseRecordRepository,
                reviewItemRepository
        );
        PriceAnalysisApplicationService priceService = new PriceAnalysisApplicationService(
                databaseInitializer,
                productNormalizer,
                purchaseRecordRepository,
                new PriceDecisionPolicy()
        );
        return new Fixture(importService, priceService, purchaseRecordRepository, dir);
    }

    private record Fixture(ImportApplicationService importService,
                           PriceAnalysisApplicationService priceService,
                           PurchaseRecordRepository purchaseRecordRepository,
                           Path dir) {
        Path file(String name) {
            return dir.resolve(name);
        }
    }
}
