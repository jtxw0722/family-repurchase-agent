package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.RecordPurchaseRequest;
import com.jtxw.familyagent.domain.model.RecordPurchaseResult;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import com.jtxw.familyagent.domain.policy.DuplicateDetectionPolicy;
import com.jtxw.familyagent.domain.policy.NormalizationRule;
import com.jtxw.familyagent.domain.policy.OwnerNormalizer;
import com.jtxw.familyagent.domain.policy.PriceDecisionThresholds;
import com.jtxw.familyagent.domain.policy.ProductNameNormalizer;
import com.jtxw.familyagent.domain.policy.ProductNormalizer;
import com.jtxw.familyagent.domain.policy.PurchaseTimeNormalizer;
import com.jtxw.familyagent.domain.policy.QuantityUnitParser;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.ImportBatchRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * @Author: jtxw
 * @Date: 2026/06/04
 * @Description: 手动购买记录录入应用服务集成测试。
 */
class RecordPurchaseApplicationServiceTest {
    @Test
    void dryRunShouldNotWriteDatabase() throws Exception {
        Fixture fixture = fixture("dry-run.sqlite");

        RecordPurchaseResult result = fixture.service.record(request(true, catLitterRecord()));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.savedCount()).isZero();
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).recordId()).isNull();
        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂")).isEmpty();
        assertThat(fixture.reviewItemRepository.listPendingDetails()).isEmpty();
    }

    @Test
    void shouldRecordCatLitterAsIncludedPriceSample() throws Exception {
        Fixture fixture = fixture("cat-litter.sqlite");

        RecordPurchaseResult result = fixture.service.record(request(false, catLitterRecord()));

        assertThat(result.savedCount()).isEqualTo(1);
        assertThat(result.reviewCount()).isZero();
        RecordPurchaseResult.RecordResult recordResult = result.records().get(0);
        assertThat(recordResult.normalizedName()).isEqualTo("猫砂");
        assertThat(recordResult.decision()).isEqualTo("include");
        assertThat(recordResult.unitPrice()).isCloseTo(4.579167D, offset(0.000001D));

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).unitPrice()).isCloseTo(4.579167D, offset(0.000001D));
    }

    @Test
    void shouldPersistManualRecordSourceFields() throws Exception {
        Fixture fixture = fixture("source-fields.sqlite");

        fixture.service.record(request(false, catLitterRecord()));

        PurchaseRecord record = fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂").get(0);
        assertThat(record.shopName()).isEqualTo("京东自营");
        assertThat(record.note()).isEqualTo("手动录入");
        assertThat(record.sourceText()).isEqualTo("昨天在京东买了猫砂，109.9 元，6kg*4 包，京东自营。");
    }

    @Test
    void shouldTrustExplicitQuantityAndUnitForLaundryBeads() throws Exception {
        Fixture fixture = fixture("laundry-beads.sqlite");
        RecordPurchaseRequest.Record record = new RecordPurchaseRequest.Record(
                "洗衣凝珠", 45D, 30D, "颗", "MANUAL", "2026-06-04",
                "jtxw", null, "暂无", null, null
        );

        RecordPurchaseResult result = fixture.service.record(request(false, record));

        assertThat(result.savedCount()).isEqualTo(1);
        assertThat(result.reviewCount()).isZero();
        assertThat(result.records().get(0).normalizedName()).isEqualTo("洗衣凝珠");
        assertThat(result.records().get(0).quantity()).isEqualTo(30D);
        assertThat(result.records().get(0).unit()).isEqualTo("颗");
        assertThat(result.records().get(0).unitPrice()).isCloseTo(1.5D, offset(0.000001D));
        assertThat(fixture.reviewItemRepository.listPendingDetails()).isEmpty();
    }

    @Test
    void shouldExcludeAndCreateReviewWhenUnitMismatchCannotBeParsed() throws Exception {
        Fixture fixture = fixture("unit-mismatch.sqlite");
        RecordPurchaseRequest.Record record = new RecordPurchaseRequest.Record(
                "猫砂", 109.9D, 1D, "件", "JD", "2026-06-04",
                "jtxw", null, "暂无", null, null
        );

        RecordPurchaseResult result = fixture.service.record(request(false, record));

        assertThat(result.savedCount()).isEqualTo(1);
        assertThat(result.reviewCount()).isEqualTo(1);
        assertThat(result.records().get(0).decision()).isEqualTo("exclude");
        assertThat(result.records().get(0).reviewRequired()).isTrue();
        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂")).isEmpty();
        List<ReviewItemDetail> reviewItems = fixture.reviewItemRepository.listPendingDetails();
        assertThat(reviewItems).hasSize(1);
        assertThat(reviewItems.get(0).reasonCode()).isEqualTo("UNIT_MISMATCH_UNPARSED");
    }

    @Test
    void shouldMarkSecondSameManualRecordAsDuplicate() throws Exception {
        Fixture fixture = fixture("duplicate.sqlite");
        fixture.service.record(request(false, catLitterRecord()));

        RecordPurchaseResult second = fixture.service.record(request(false, catLitterRecord()));

        assertThat(second.savedCount()).isEqualTo(1);
        assertThat(second.reviewCount()).isEqualTo(1);
        assertThat(second.records().get(0).decision()).isEqualTo("exclude");
        assertThat(second.records().get(0).reviewRequired()).isTrue();
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains("DUPLICATE_ORDER");
    }

    @Test
    void shouldNormalizeManualRecordPlatformValues() throws Exception {
        assertStoredPlatform("platform-null.sqlite", null, "manual");
        assertStoredPlatform("platform-blank.sqlite", "   ", "manual");
        assertStoredPlatform("platform-taobao-cn.sqlite", "淘宝", "taobao");
        assertStoredPlatform("platform-taobao-en.sqlite", "TAOBAO", "taobao");
        assertStoredPlatform("platform-jd-cn.sqlite", "京东自营", "jd");
        assertStoredPlatform("platform-jd-en.sqlite", "jd", "jd");
        assertStoredPlatform("platform-pdd-cn.sqlite", "拼多多", "pdd");
        assertStoredPlatform("platform-custom-cn.sqlite", "山姆", "山姆");
    }

    @Test
    void shouldNormalizeManualRecordOwnerValues() throws Exception {
        assertStoredOwner("owner-null.sqlite", null, "jtxw");
        assertStoredOwner("owner-jtxw-uppercase.sqlite", "JTXW", "jtxw");
        assertStoredOwner("owner-lj-uppercase.sqlite", "LJ", "lj");
        assertStoredOwner("owner-custom-en.sqlite", "Alice", "alice");
        assertStoredOwner("owner-custom-cn.sqlite", "家人", "家人");
    }

    @Test
    void shouldDefaultBlankSkuToUnavailableText() throws Exception {
        Fixture nullSkuFixture = fixture("null-sku.sqlite");
        nullSkuFixture.service.record(request(false, catLitterRecordWith("JD", "2026-06-04", null)));
        assertThat(nullSkuFixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂").get(0).sku()).isEqualTo("暂无");

        Fixture blankSkuFixture = fixture("blank-sku.sqlite");
        blankSkuFixture.service.record(request(false, catLitterRecordWith("JD", "2026-06-04", "   ")));
        assertThat(blankSkuFixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂").get(0).sku()).isEqualTo("暂无");
    }

    @Test
    void shouldNormalizePurchaseDateToDateTimeFormat() throws Exception {
        assertStoredOrderTime("date-only.sqlite", "2019-11-11", "2019-11-11 00:00:00");
        assertStoredOrderTime("date-space-time.sqlite", "2019-11-11 00:12:11", "2019-11-11 00:12:11");
        assertStoredOrderTime("date-t-time.sqlite", "2019-11-11T00:12:11", "2019-11-11 00:12:11");
        assertStoredOrderTime("date-slash-time.sqlite", "2019/11/11 00:12:11", "2019-11-11 00:12:11");
    }

    @Test
    void shouldRejectInvalidPurchaseDate() throws Exception {
        Fixture fixture = fixture("invalid-date.sqlite");

        assertThatThrownBy(() -> fixture.service.record(request(false,
                catLitterRecordWith("JD", "2021-02-30 13:45:20", "6kg*4包"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purchaseDate 格式错误");
    }

    private void assertStoredPlatform(String dbName, String inputPlatform, String expectedPlatform) throws Exception {
        Fixture fixture = fixture(dbName);
        fixture.service.record(request(false, catLitterRecordWith(inputPlatform, "2026-06-04", "6kg*4包")));

        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂").get(0).platform())
                .isEqualTo(expectedPlatform);
    }

    private void assertStoredOwner(String dbName, String inputOwner, String expectedOwner) throws Exception {
        Fixture fixture = fixture(dbName);
        fixture.service.record(request(false, catLitterRecordWith("JD", "2026-06-04", "6kg*4包", inputOwner)));

        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂").get(0).owner())
                .isEqualTo(expectedOwner);
    }

    private void assertStoredOrderTime(String dbName, String inputDate, String expectedOrderTime) throws Exception {
        Fixture fixture = fixture(dbName);
        fixture.service.record(request(false, catLitterRecordWith("JD", inputDate, "6kg*4包")));

        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂").get(0).orderTime())
                .isEqualTo(expectedOrderTime);
    }

    private RecordPurchaseRequest request(boolean dryRun, RecordPurchaseRequest.Record... records) {
        return new RecordPurchaseRequest(dryRun, List.of(records));
    }

    private RecordPurchaseRequest.Record catLitterRecord() {
        return catLitterRecordWith("JD", "2026-06-04", "6kg*4包");
    }

    private RecordPurchaseRequest.Record catLitterRecordWith(String platform, String purchaseDate, String sku) {
        return catLitterRecordWith(platform, purchaseDate, sku, "jtxw");
    }

    private RecordPurchaseRequest.Record catLitterRecordWith(String platform, String purchaseDate, String sku, String owner) {
        return new RecordPurchaseRequest.Record(
                "猫砂", 109.9D, 24D, "kg", platform, purchaseDate,
                owner, "京东自营", sku, "手动录入",
                "昨天在京东买了猫砂，109.9 元，6kg*4 包，京东自营。"
        );
    }

    private Fixture fixture(String dbName) throws Exception {
        Path dir = Path.of("target", "record-purchase-service-test");
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
        ProductNameNormalizer productNameNormalizer = new ProductNameNormalizer(
                new ProductNormalizer(),
                List.of(new NormalizationRule("test_laundry_beads", "洗衣凝珠", "颗",
                        List.of("洗衣凝珠", "凝珠", "洗衣珠"), 100))
        );
        RecordPurchaseApplicationService service = new RecordPurchaseApplicationService(
                databaseInitializer,
                productNameNormalizer,
                new QuantityUnitParser(),
                new DuplicateDetectionPolicy(),
                new OwnerNormalizer(),
                new PurchaseTimeNormalizer(),
                importBatchRepository,
                purchaseRecordRepository,
                reviewItemRepository
        );
        return new Fixture(service, purchaseRecordRepository, reviewItemRepository);
    }

    private record Fixture(RecordPurchaseApplicationService service,
                           PurchaseRecordRepository purchaseRecordRepository,
                           ReviewItemRepository reviewItemRepository) {
    }
}
