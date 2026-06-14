package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.ReviewApplyResult;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import com.jtxw.familyagent.domain.policy.*;
import com.jtxw.familyagent.infrastructure.importer.CsvPurchaseImporter;
import com.jtxw.familyagent.infrastructure.importer.ExcelPurchaseImporter;
import com.jtxw.familyagent.infrastructure.importer.OrderImportMapper;
import com.jtxw.familyagent.infrastructure.persistence.*;
import com.jtxw.familyagent.application.command.RecordPurchaseCommand;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/05
 * @Description: 商品归一化学习闭环集成测试。
 */
class NormalizationLearningIntegrationTest {
    @Test
    void confirmNormalizationShouldWriteAliasAndUpdatePurchaseRecord() throws Exception {
        Fixture fixture = fixture("confirm-normalization.sqlite");
        fixture.recordService().record(command(false, bodyWashRecord("2026-05-01")));
        ReviewItemDetail review = fixture.reviewItemRepository().listPendingDetails().get(0);

        ReviewApplyResult result = fixture.reviewService().confirmNormalization(
                review.id(), "沐浴露", "L", true, "确认舒肤佳沐浴露归一为沐浴露");

        assertThat(result.action()).isEqualTo("confirm_normalization");
        assertThat(result.decision()).isEqualTo("include");
        PurchaseRecord record = fixture.purchaseRecordRepository().findById(review.recordId()).orElseThrow();
        assertThat(record.normalizedName()).isEqualTo("沐浴露");
        assertThat(record.decision()).isEqualTo("include");
        assertThat(fixture.productAliasRepository()
                .findByAliasKey(fixture.cleaner().aliasKey(record.productName(), record.sku())))
                .isPresent()
                .get()
                .extracting(ProductAliasRepository.ProductAlias::normalizedName)
                .isEqualTo("沐浴露");
    }

    @Test
    void rejectNormalizationShouldWriteNegativeAlias() throws Exception {
        Fixture fixture = fixture("reject-normalization.sqlite");
        fixture.recordService().record(command(false, bodyWashRecord("2026-05-01")));
        ReviewItemDetail review = fixture.reviewItemRepository().listPendingDetails().get(0);

        ReviewApplyResult result = fixture.reviewService().rejectNormalization(review.id(), "沐浴露",
                "人工确认不是沐浴露");

        assertThat(result.action()).isEqualTo("reject_normalization");
        assertThat(result.decision()).isEqualTo("exclude");
        PurchaseRecord record = fixture.purchaseRecordRepository().findById(review.recordId()).orElseThrow();
        assertThat(fixture.productNegativeAliasRepository()
                .findByAliasKey(fixture.cleaner().aliasKey(record.productName(), record.sku())))
                .isPresent()
                .get()
                .extracting(ProductNegativeAliasRepository.ProductNegativeAlias::rejectedNormalizedName)
                .isEqualTo("沐浴露");
    }

    @Test
    void negativeAliasShouldAutoExcludeWithoutNormalizationReviewOnNextImport() throws Exception {
        Fixture fixture = fixture("negative-alias-next-import.sqlite");
        fixture.recordService().record(command(false, bodyWashRecord("2026-05-01")));
        ReviewItemDetail review = fixture.reviewItemRepository().listPendingDetails().get(0);
        fixture.reviewService().rejectNormalization(review.id(), "沐浴露", "人工确认不是沐浴露");

        Path file = fixture.dir().resolve("negative-orders.csv");
        Files.writeString(file, """
                order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
                2026-05-02 00:00:00,jd,jtxw,舒肤佳沐浴露清香型720ml,家庭装,日用品,洗浴用品,0.72,L,39.9,CNY
                """, StandardCharsets.UTF_8);
        fixture.importService().importFile(file, null);

        assertThat(fixture.reviewItemRepository().listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .doesNotContain("PRODUCT_NAME_NORMALIZATION_REVIEW");
        List<String> decisions = fixture.jdbcTemplate().queryForList("""
                SELECT decision FROM purchase_records
                WHERE product_name = ? AND order_time = ?
                """, String.class, "舒肤佳沐浴露清香型720ml", "2026-05-02 00:00:00");
        assertThat(decisions).contains("exclude");
    }

    @Test
    void confirmNormalizationShouldNotIncludeWhenOtherPendingReviewExists() throws Exception {
        Fixture fixture = fixture("confirm-cannot-bypass-other-pending.sqlite");
        fixture.recordService().record(command(false, bodyWashRecord("2026-05-01")));
        ReviewItemDetail review = fixture.reviewItemRepository().listPendingDetails().get(0);
        fixture.reviewItemRepository().create(review.recordId(), "ZERO_PAYMENT", "测试用额外待复核项");

        assertThatThrownBy(() -> fixture.reviewService().confirmNormalization(
                review.id(), "沐浴露", "L", true, "尝试绕过其他复核"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仍存在其他待处理或已排除的复核风险");
    }

    @Test
    void confirmNormalizationShouldNotIncludeWhenOtherReviewWasExcluded() throws Exception {
        Fixture fixture = fixture("confirm-cannot-bypass-excluded-review.sqlite");
        fixture.recordService().record(command(false, bodyWashRecord("2026-05-01")));
        ReviewItemDetail normalizationReview = fixture.reviewItemRepository().listPendingDetails().get(0);
        fixture.reviewItemRepository().create(normalizationReview.recordId(), "ZERO_PAYMENT", "测试用已排除风险");
        ReviewItemDetail extraReview = fixture.reviewItemRepository().listPendingDetails().stream()
                .filter(item -> "ZERO_PAYMENT".equals(item.reasonCode()))
                .findFirst()
                .orElseThrow();

        fixture.reviewService().apply(extraReview.id(), "exclude", "确认排除零元风险");

        assertThatThrownBy(() -> fixture.reviewService().confirmNormalization(
                normalizationReview.id(), "沐浴露", "L", true, "不能覆盖已排除风险"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已排除的复核风险");
        PurchaseRecord record = fixture.purchaseRecordRepository().findById(normalizationReview.recordId()).orElseThrow();
        assertThat(record.decision()).isEqualTo("exclude");
    }

    @Test
    void confirmNormalizationShouldNotIncludeWhenTargetUnitDiffersFromRecordUnit() throws Exception {
        Fixture fixture = fixture("confirm-target-unit-mismatch.sqlite");
        fixture.recordService().record(command(false, bodyWashRecord("2026-05-01")));
        ReviewItemDetail review = fixture.reviewItemRepository().listPendingDetails().get(0);

        assertThatThrownBy(() -> fixture.reviewService().confirmNormalization(
                review.id(), "沐浴露", "ml", true, "单位不一致"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("targetUnit 与购买记录当前 unit 不一致");
    }

    @Test
    void confirmedAliasShouldAvoidNormalizationReviewOnNextImport() throws Exception {
        Fixture fixture = fixture("confirmed-alias-next-import.sqlite");
        fixture.recordService().record(command(false, bodyWashRecord("2026-05-01")));
        ReviewItemDetail review = fixture.reviewItemRepository().listPendingDetails().get(0);
        fixture.reviewService().confirmNormalization(review.id(), "沐浴露", "L", false, "确认别名");

        Path file = fixture.dir().resolve("orders.csv");
        Files.writeString(file, """
                order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
                2026-05-02 00:00:00,jd,jtxw,舒肤佳沐浴露清香型720ml,家庭装,日用品,洗浴用品,0.72,L,39.9,CNY
                """, StandardCharsets.UTF_8);
        fixture.importService().importFile(file, null);

        assertThat(fixture.purchaseRecordRepository().listPriceHistoryRecords("沐浴露")).hasSize(1);
        assertThat(fixture.reviewItemRepository().listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .doesNotContain("PRODUCT_NAME_NORMALIZATION_REVIEW");
    }

    private RecordPurchaseCommand command(boolean dryRun, RecordPurchaseCommand.Item... records) {
        return new RecordPurchaseCommand(dryRun, List.of(records));
    }

    private RecordPurchaseCommand.Item bodyWashRecord(String purchaseDate) {
        return new RecordPurchaseCommand.Item(
                "舒肤佳沐浴露清香型720ml", 39.9D, 0.72D, "L", "JD", purchaseDate,
                "jtxw", "京东自营", "家庭装", "手动录入", "买了舒肤佳沐浴露。", false
        );
    }

    private Fixture fixture(String dbName) throws Exception {
        Path dir = Path.of("target", "normalization-learning-integration-test");
        Files.createDirectories(dir);
        Path db = dir.resolve(dbName);
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer databaseInitializer = new DatabaseInitializer(jdbcTemplate);
        databaseInitializer.initialize();
        ProductTitleCleaner cleaner = new ProductTitleCleaner();
        ProductAliasRepository productAliasRepository = new ProductAliasRepository(jdbcTemplate);
        ProductNegativeAliasRepository productNegativeAliasRepository = new ProductNegativeAliasRepository(jdbcTemplate);
        PurchaseRecordRepository purchaseRecordRepository = new PurchaseRecordRepository(jdbcTemplate);
        ReviewItemRepository reviewItemRepository = new ReviewItemRepository(jdbcTemplate);
        ImportBatchRepository importBatchRepository = new ImportBatchRepository(jdbcTemplate);
        ProductRuleMatcher productRuleMatcher = new ProductRuleMatcher(List::of);
        ProductNormalizer productNormalizer = new ProductNormalizer(productRuleMatcher);
        ProductNameNormalizer delegate = new ProductNameNormalizer(productNormalizer, List.of());
        LearningProductNameNormalizer learningNormalizer = new LearningProductNameNormalizer(
                cleaner, productAliasRepository, productNegativeAliasRepository, delegate);
        ProductSpecParser productSpecParser = new ProductSpecParser(
                productNormalizer, TestProductRuleProviders.defaultUnitSpecParsers());
        OrderImportMapper orderImportMapper = new OrderImportMapper(
                productSpecParser, productRuleMatcher, new OwnerNormalizer());
        ImportApplicationService importService = new ImportApplicationService(
                databaseInitializer,
                new CsvPurchaseImporter(orderImportMapper),
                new ExcelPurchaseImporter(orderImportMapper),
                new DuplicateDetectionPolicy(),
                new PaymentAdjustmentPolicy(),
                new OwnerNormalizer(),
                new PurchaseTimeNormalizer(),
                learningNormalizer,
                new QuantityUnitParser(),
                new UnitPriceCalculator(),
                importBatchRepository,
                purchaseRecordRepository,
                reviewItemRepository
        );
        RecordPurchaseApplicationService recordService = new RecordPurchaseApplicationService(
                databaseInitializer,
                learningNormalizer,
                new QuantityUnitParser(),
                new DuplicateDetectionPolicy(),
                new OwnerNormalizer(),
                new PurchaseTimeNormalizer(),
                importBatchRepository,
                purchaseRecordRepository,
                reviewItemRepository
        );
        ReviewApplicationService reviewService = new ReviewApplicationService(
                databaseInitializer,
                purchaseRecordRepository,
                reviewItemRepository,
                cleaner,
                productAliasRepository,
                productNegativeAliasRepository
        );
        return new Fixture(dir, jdbcTemplate, cleaner, productAliasRepository, productNegativeAliasRepository,
                purchaseRecordRepository, reviewItemRepository, importService, recordService, reviewService);
    }

    private record Fixture(Path dir,
                           JdbcTemplate jdbcTemplate,
                           ProductTitleCleaner cleaner,
                           ProductAliasRepository productAliasRepository,
                           ProductNegativeAliasRepository productNegativeAliasRepository,
                           PurchaseRecordRepository purchaseRecordRepository,
                           ReviewItemRepository reviewItemRepository,
                           ImportApplicationService importService,
                           RecordPurchaseApplicationService recordService,
                           ReviewApplicationService reviewService) {
    }
}
