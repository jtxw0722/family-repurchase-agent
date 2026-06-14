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
    void confirmNormalizationShouldUpdatePurchaseRecordWithoutRulePersistence() throws Exception {
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
        assertThat(result.message()).contains("长期规则请通过 normalization_rules / normalization_rule_keywords 维护");
    }

    @Test
    void rejectNormalizationShouldUpdateDecisionWithoutRulePersistence() throws Exception {
        Fixture fixture = fixture("reject-normalization.sqlite");
        fixture.recordService().record(command(false, bodyWashRecord("2026-05-01")));
        ReviewItemDetail review = fixture.reviewItemRepository().listPendingDetails().get(0);

        ReviewApplyResult result = fixture.reviewService().rejectNormalization(review.id(), "沐浴露",
                "人工确认不是沐浴露");

        assertThat(result.action()).isEqualTo("reject_normalization");
        assertThat(result.decision()).isEqualTo("exclude");
        PurchaseRecord record = fixture.purchaseRecordRepository().findById(review.recordId()).orElseThrow();
        assertThat(record.decision()).isEqualTo("exclude");
        assertThat(result.message()).contains("长期规则请通过 normalization_rules / normalization_rule_keywords 维护");
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
        PurchaseRecordRepository purchaseRecordRepository = new PurchaseRecordRepository(jdbcTemplate);
        ReviewItemRepository reviewItemRepository = new ReviewItemRepository(jdbcTemplate);
        ImportBatchRepository importBatchRepository = new ImportBatchRepository(jdbcTemplate);
        ProductRuleMatcher productRuleMatcher = new ProductRuleMatcher(List::of);
        ProductNormalizer productNormalizer = new ProductNormalizer(productRuleMatcher);
        ProductNameNormalizer delegate = new ProductNameNormalizer(productNormalizer, List.of());
        LearningProductNameNormalizer learningNormalizer = new LearningProductNameNormalizer(delegate);
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
                reviewItemRepository
        );
        return new Fixture(dir, jdbcTemplate,
                purchaseRecordRepository, reviewItemRepository, importService, recordService, reviewService);
    }

    private record Fixture(Path dir,
                           JdbcTemplate jdbcTemplate,
                           PurchaseRecordRepository purchaseRecordRepository,
                           ReviewItemRepository reviewItemRepository,
                           ImportApplicationService importService,
                           RecordPurchaseApplicationService recordService,
                           ReviewApplicationService reviewService) {
    }
}
