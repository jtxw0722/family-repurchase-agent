package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.PriceBaselineResult;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.*;
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

    private PriceDecisionPolicy newPriceDecisionPolicy() {
        return new PriceDecisionPolicy(new PriceDecisionThresholds(0.92D, 1.12D));
    }

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

    @Test
    void shouldImportTissueDrawCountAndQueryPriceBaseline() throws Exception {
        Fixture fixture = fixture("tissue-baseline.sqlite");
        Path file = fixture.file("tissue-orders.csv");
        Files.writeString(file, """
            order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
            2026-04-01,taobao,jtxw,维达超韧抽纸 3层130抽×24包（195×133mm）,3层130抽×24包,日用品,纸巾,1,件,39,CNY
            2026-05-01,taobao,jtxw,某品牌原生木浆抽纸 100抽*20包,100抽*20包,日用品,纸巾,1,件,30,CNY
            """, StandardCharsets.UTF_8);

        fixture.importService.importFile(file, "jtxw");

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listPriceHistoryRecords("纸巾");
        assertThat(records).hasSize(2);
        assertThat(records)
                .extracting(PurchaseRecord::unit)
                .containsOnly("抽");

        PriceBaselineResult result = fixture.priceService.getPriceBaseline("纸巾", null);

        assertThat(result.productName()).isEqualTo("纸巾");
        assertThat(result.normalizedName()).isEqualTo("纸巾");
        assertThat(result.baseline().unit()).isEqualTo("抽");
        assertThat(result.baseline().sampleSize()).isEqualTo(2);
        assertThat(result.baseline().historicalMin()).isNotNull();
        assertThat(result.baseline().historicalMedian()).isNotNull();
        assertThat(result.evidence().source()).isEqualTo("local_purchase_history");
        assertThat(result.evidence().sourceRecords())
                .extracting(PriceDecisionResult.SourceRecord::role)
                .contains("historical_min", "median_sample", "latest");
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
                newPriceDecisionPolicy()
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
