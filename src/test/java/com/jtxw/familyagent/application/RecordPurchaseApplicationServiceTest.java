package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.RecordPurchaseCommand;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.RecordPurchaseResult;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import com.jtxw.familyagent.domain.policy.*;
import com.jtxw.familyagent.infrastructure.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * @Author: jtxw
 * @Date: 2026/06/04
 * @Description: 手动购买记录录入应用服务集成测试。
 */
class RecordPurchaseApplicationServiceTest {
    @Test
    void dryRunShouldNotWriteDatabase() throws Exception {
        Fixture fixture = fixture("dry-run.sqlite");

        RecordPurchaseResult result = fixture.service.record(command(true, catLitterRecord()));

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

        RecordPurchaseResult result = fixture.service.record(command(false, catLitterRecord()));

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

        fixture.service.record(command(false, catLitterRecord()));

        PurchaseRecord record = fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂").get(0);
        assertThat(record.shopName()).isEqualTo("京东自营");
        assertThat(record.note()).isEqualTo("手动录入");
        assertThat(record.sourceText()).isEqualTo("昨天在京东买了猫砂，109.9 元，6kg*4 包，京东自营。");
    }

    @Test
    void shouldTrustExplicitQuantityAndUnitForLaundryBeads() throws Exception {
        Fixture fixture = fixture("laundry-beads.sqlite");
        RecordPurchaseCommand.Item record = new RecordPurchaseCommand.Item(
                "洗衣凝珠", 45D, 30D, "颗", "MANUAL", "2026-06-04",
                "jtxw", null, "暂无", null, null, false
        );

        RecordPurchaseResult result = fixture.service.record(command(false, record));

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
        RecordPurchaseCommand.Item record = new RecordPurchaseCommand.Item(
                "猫砂", 109.9D, 1D, "件", "JD", "2026-06-04",
                "jtxw", null, "暂无", null, null, false
        );

        RecordPurchaseResult result = fixture.service.record(command(false, record));

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
        fixture.service.record(command(false, catLitterRecord()));

        RecordPurchaseResult second = fixture.service.record(command(false, catLitterRecord()));

        assertThat(second.savedCount()).isEqualTo(1);
        assertThat(second.reviewCount()).isEqualTo(1);
        assertThat(second.records().get(0).decision()).isEqualTo("exclude");
        assertThat(second.records().get(0).reviewRequired()).isTrue();
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains("DUPLICATE_ORDER");
    }

    @Test
    void shouldExcludeAndCreateReviewWhenPurchaseDateIsFuture() throws Exception {
        Fixture fixture = fixture("future-date.sqlite");
        String futureDate = LocalDate.now().plusDays(1).toString();

        RecordPurchaseResult result = fixture.service.record(command(false,
                catLitterRecordWith("JD", futureDate, "6kg*4包")));

        assertThat(result.savedCount()).isEqualTo(1);
        assertThat(result.reviewCount()).isEqualTo(1);
        assertThat(result.records().get(0).decision()).isEqualTo("exclude");
        assertThat(result.records().get(0).reviewRequired()).isTrue();
        assertThat(result.records().get(0).reviewReasons())
                .contains("购买时间晚于当前时间，疑似自然语言日期抽取错误，需要人工确认");
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains("FUTURE_PURCHASE_TIME");
    }

    @Test
    void dryRunShouldReportFuturePurchaseDateReviewWithoutWritingDatabase() throws Exception {
        Fixture fixture = fixture("future-date-dry-run.sqlite");
        String futureDate = LocalDate.now().plusDays(1).toString();

        RecordPurchaseResult result = fixture.service.record(command(true,
                catLitterRecordWith("JD", futureDate, "6kg*4包")));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.savedCount()).isZero();
        assertThat(result.reviewCount()).isEqualTo(1);
        assertThat(result.records().get(0).recordId()).isNull();
        assertThat(result.records().get(0).decision()).isEqualTo("exclude");
        assertThat(result.records().get(0).reviewRequired()).isTrue();
        assertThat(result.records().get(0).reviewReasons())
                .contains("购买时间晚于当前时间，疑似自然语言日期抽取错误，需要人工确认");
        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂")).isEmpty();
        assertThat(fixture.reviewItemRepository.listPendingDetails()).isEmpty();
    }

    @Test
    void shouldIncludeTodayAndPastPurchaseDates() throws Exception {
        assertStoredDecision("today-date.sqlite", LocalDate.now().toString(), "include");
        assertStoredDecision("past-date.sqlite", LocalDate.now().minusDays(1).toString(), "include");
    }

    @Test
    void shouldNotBlockOutOfRangePriceWhenHistorySamplesAreInsufficient() throws Exception {
        Fixture fixture = fixture("out-of-range-insufficient-history.sqlite");
        seedCatLitterHistory(fixture, 2);

        RecordPurchaseResult result = fixture.service.record(command(false,
                catLitterRecordWithPrice("2026-05-10", 30D, 10D, false)));

        assertThat(result.records().get(0).decision()).isEqualTo("include");
        assertThat(result.records().get(0).reviewRequired()).isFalse();
        assertThat(fixture.reviewItemRepository.listPendingDetails()).isEmpty();
    }

    @Test
    void shouldExcludeAndReviewWhenUnitPriceIsLowerThanHistoricalRange() throws Exception {
        Fixture fixture = fixture("out-of-range-low.sqlite");
        seedCatLitterHistory(fixture, 3);

        RecordPurchaseResult result = fixture.service.record(command(false,
                catLitterRecordWithPrice("2026-05-10", 30D, 10D, false)));

        assertThat(result.records().get(0).decision()).isEqualTo("exclude");
        assertThat(result.records().get(0).reviewRequired()).isTrue();
        assertThat(result.records().get(0).reviewReasons().get(0)).contains("明显低于历史最低");
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains("PRICE_OUT_OF_BASELINE_RANGE");
    }

    @Test
    void shouldIgnoreDuplicateHistoryWhenCheckingPriceRange() throws Exception {
        Fixture fixture = fixture("out-of-range-ignore-duplicate-history.sqlite");
        seedCatLitterHistory(fixture, 3);
        fixture.purchaseRecordRepository.save(new PurchaseRecord(
                null, 1L, "2026-04-01 00:00:00", "jd", "jtxw", "猫砂", "猫砂",
                "10kg", "", "", 10D, "kg", 10D, 10D, 10D, null,
                "manual_record", 1D, "CNY", "include", true, "unique",
                "test.csv", null, null, null, "2026-04-01 00:00:00"
        ));

        RecordPurchaseResult result = fixture.service.record(command(false,
                catLitterRecordWithPrice("2026-05-10", 30D, 10D, false)));

        assertThat(result.records().get(0).decision()).isEqualTo("exclude");
        assertThat(result.records().get(0).reviewRequired()).isTrue();
        assertThat(result.records().get(0).reviewReasons().get(0)).contains("历史最低 4.000000");
    }

    @Test
    void shouldExcludeAndReviewWhenUnitPriceIsHigherThanHistoricalRange() throws Exception {
        Fixture fixture = fixture("out-of-range-high.sqlite");
        seedCatLitterHistory(fixture, 3);

        RecordPurchaseResult result = fixture.service.record(command(false,
                catLitterRecordWithPrice("2026-05-10", 80D, 10D, false)));

        assertThat(result.records().get(0).decision()).isEqualTo("exclude");
        assertThat(result.records().get(0).reviewRequired()).isTrue();
        assertThat(result.records().get(0).reviewReasons().get(0)).contains("明显高于历史最高");
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains("PRICE_OUT_OF_BASELINE_RANGE");
    }

    @Test
    void dryRunShouldReportOutOfRangePriceWithoutWritingDatabase() throws Exception {
        Fixture fixture = fixture("out-of-range-dry-run.sqlite");
        seedCatLitterHistory(fixture, 3);

        RecordPurchaseResult result = fixture.service.record(command(true,
                catLitterRecordWithPrice("2026-05-10", 30D, 10D, false)));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.savedCount()).isZero();
        assertThat(result.reviewCount()).isEqualTo(1);
        assertThat(result.records().get(0).recordId()).isNull();
        assertThat(result.records().get(0).decision()).isEqualTo("exclude");
        assertThat(result.records().get(0).reviewRequired()).isTrue();
        assertThat(result.records().get(0).reviewReasons().get(0)).contains("明显低于历史最低");
        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂")).hasSize(3);
        assertThat(fixture.reviewItemRepository.listPendingDetails()).isEmpty();
    }

    @Test
    void shouldIncludeOutOfRangePriceWhenConfirmed() throws Exception {
        Fixture fixture = fixture("out-of-range-confirmed.sqlite");
        seedCatLitterHistory(fixture, 3);

        RecordPurchaseResult result = fixture.service.record(command(false,
                catLitterRecordWithPrice("2026-05-10", 30D, 10D, true)));

        assertThat(result.records().get(0).decision()).isEqualTo("include");
        assertThat(result.records().get(0).reviewRequired()).isFalse();
        assertThat(fixture.reviewItemRepository.listPendingDetails()).isEmpty();
        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂")).hasSize(4);
    }

    @Test
    void confirmOutOfRangeShouldNotBypassFuturePurchaseTimeRisk() throws Exception {
        Fixture fixture = fixture("confirm-does-not-bypass-future.sqlite");
        String futureDate = LocalDate.now().plusDays(1).toString();

        RecordPurchaseResult result = fixture.service.record(command(false,
                catLitterRecordWithPrice(futureDate, 30D, 10D, true)));

        assertThat(result.records().get(0).decision()).isEqualTo("exclude");
        assertThat(result.records().get(0).reviewRequired()).isTrue();
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains("FUTURE_PURCHASE_TIME");
    }

    @Test
    void confirmOutOfRangeShouldNotBypassDuplicateRisk() throws Exception {
        Fixture fixture = fixture("confirm-does-not-bypass-duplicate.sqlite");
        fixture.service.record(command(false,
                catLitterRecordWithPrice("2026-05-10", 55D, 10D, false)));

        RecordPurchaseResult result = fixture.service.record(command(false,
                catLitterRecordWithPrice("2026-05-10", 55D, 10D, true)));

        assertThat(result.records().get(0).decision()).isEqualTo("exclude");
        assertThat(result.records().get(0).reviewRequired()).isTrue();
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains("DUPLICATE_ORDER");
    }

    @Test
    void confirmOutOfRangeShouldNotBypassUnitMismatchRisk() throws Exception {
        Fixture fixture = fixture("confirm-does-not-bypass-unit-mismatch.sqlite");
        RecordPurchaseCommand.Item record = new RecordPurchaseCommand.Item(
                "猫砂", 109.9D, 1D, "件", "JD", "2026-05-10",
                "jtxw", null, "暂无", null, null, true
        );

        RecordPurchaseResult result = fixture.service.record(command(false, record));

        assertThat(result.records().get(0).decision()).isEqualTo("exclude");
        assertThat(result.records().get(0).reviewRequired()).isTrue();
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains("UNIT_MISMATCH_UNPARSED");
    }

    @Test
    void shouldIncludePriceWithinHistoricalRange() throws Exception {
        Fixture fixture = fixture("within-range.sqlite");
        seedCatLitterHistory(fixture, 3);

        RecordPurchaseResult result = fixture.service.record(command(false,
                catLitterRecordWithPrice("2026-05-10", 55D, 10D, false)));

        assertThat(result.records().get(0).decision()).isEqualTo("include");
        assertThat(result.records().get(0).reviewRequired()).isFalse();
        assertThat(fixture.reviewItemRepository.listPendingDetails()).isEmpty();
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
        nullSkuFixture.service.record(command(false, catLitterRecordWith("JD", "2026-06-04", null)));
        assertThat(nullSkuFixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂").get(0).sku()).isEqualTo("暂无");

        Fixture blankSkuFixture = fixture("blank-sku.sqlite");
        blankSkuFixture.service.record(command(false, catLitterRecordWith("JD", "2026-06-04", "   ")));
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

        assertThatThrownBy(() -> fixture.service.record(command(false,
                catLitterRecordWith("JD", "2021-02-30 13:45:20", "6kg*4包"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purchaseDate 格式错误");
    }

    private void assertStoredPlatform(String dbName, String inputPlatform, String expectedPlatform) throws Exception {
        Fixture fixture = fixture(dbName);
        fixture.service.record(command(false, catLitterRecordWith(inputPlatform, "2026-06-04", "6kg*4包")));

        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂").get(0).platform())
                .isEqualTo(expectedPlatform);
    }

    private void assertStoredOwner(String dbName, String inputOwner, String expectedOwner) throws Exception {
        Fixture fixture = fixture(dbName);
        fixture.service.record(command(false, catLitterRecordWith("JD", "2026-06-04", "6kg*4包", inputOwner)));

        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂").get(0).owner())
                .isEqualTo(expectedOwner);
    }

    private void assertStoredOrderTime(String dbName, String inputDate, String expectedOrderTime) throws Exception {
        Fixture fixture = fixture(dbName);
        fixture.service.record(command(false, catLitterRecordWith("JD", inputDate, "6kg*4包")));

        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂").get(0).orderTime())
                .isEqualTo(expectedOrderTime);
    }

    private void assertStoredDecision(String dbName, String inputDate, String expectedDecision) throws Exception {
        Fixture fixture = fixture(dbName);
        RecordPurchaseResult result = fixture.service.record(command(false,
                catLitterRecordWith("JD", inputDate, "6kg*4包")));

        assertThat(result.records().get(0).decision()).isEqualTo(expectedDecision);
        assertThat(result.records().get(0).reviewRequired()).isFalse();
        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂")).hasSize(1);
    }

    private RecordPurchaseCommand command(boolean dryRun, RecordPurchaseCommand.Item... records) {
        return new RecordPurchaseCommand(dryRun, List.of(records));
    }

    private RecordPurchaseCommand.Item catLitterRecord() {
        return catLitterRecordWith("JD", "2026-06-04", "6kg*4包");
    }

    private RecordPurchaseCommand.Item catLitterRecordWith(String platform, String purchaseDate, String sku) {
        return catLitterRecordWith(platform, purchaseDate, sku, "jtxw");
    }

    private RecordPurchaseCommand.Item catLitterRecordWith(String platform, String purchaseDate, String sku, String owner) {
        return new RecordPurchaseCommand.Item(
                "猫砂", 109.9D, 24D, "kg", platform, purchaseDate,
                owner, "京东自营", sku, "手动录入",
                "昨天在京东买了猫砂，109.9 元，6kg*4 包，京东自营。", false
        );
    }

    private RecordPurchaseCommand.Item catLitterRecordWithPrice(String purchaseDate,
                                                                  double price,
                                                                  double quantity,
                                                                  boolean confirmOutOfRange) {
        return new RecordPurchaseCommand.Item(
                "猫砂", price, quantity, "kg", "JD", purchaseDate,
                "jtxw", "京东自营", quantity + "kg", "手动录入",
                "历史区间防御测试。", confirmOutOfRange
        );
    }

    private void seedCatLitterHistory(Fixture fixture, int sampleCount) {
        double[] prices = {40D, 50D, 60D};
        for (int i = 0; i < sampleCount; i++) {
            fixture.service.record(command(false,
                    catLitterRecordWithPrice("2026-05-0" + (i + 1), prices[i], 10D, false)));
        }
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
        databaseInitializer.initialize();
        ImportBatchRepository importBatchRepository = new ImportBatchRepository(jdbcTemplate);
        ProductAliasRepository productAliasRepository = new ProductAliasRepository(jdbcTemplate);
        ProductNegativeAliasRepository productNegativeAliasRepository = new ProductNegativeAliasRepository(jdbcTemplate);
        PurchaseRecordRepository purchaseRecordRepository = new PurchaseRecordRepository(jdbcTemplate);
        ReviewItemRepository reviewItemRepository = new ReviewItemRepository(jdbcTemplate);
        ProductNameNormalizer productNameNormalizer = new ProductNameNormalizer(
                new ProductNormalizer(),
                List.of(new NormalizationRule("test_laundry_beads", "洗衣凝珠", "颗",
                        List.of("洗衣凝珠", "凝珠", "洗衣珠"), 100))
        );
        LearningProductNameNormalizer learningProductNameNormalizer = new LearningProductNameNormalizer(
                new ProductTitleCleaner(),
                productAliasRepository,
                productNegativeAliasRepository,
                productNameNormalizer
        );
        RecordPurchaseApplicationService service = new RecordPurchaseApplicationService(
                databaseInitializer,
                learningProductNameNormalizer,
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
