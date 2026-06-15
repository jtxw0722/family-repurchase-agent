package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.NormalizationLibraryOperationCommand;
import com.jtxw.familyagent.domain.model.NormalizationApplyRuleToRecordsItem;
import com.jtxw.familyagent.domain.model.NormalizationApplyRuleToRecordsResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import com.jtxw.familyagent.domain.policy.ProductRuleMatcher;
import com.jtxw.familyagent.domain.policy.QuantityUnitParser;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationRuleRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 06:31:45
 * @Description: 归一化规则历史记录回填服务测试，覆盖 dry-run、显式写入、跳过和人工复核边界
 */
class NormalizationRuleApplyServiceTest {
    /**
     * 被测归一化规则历史记录回填服务。
     */
    private NormalizationRuleApplyService service;
    /**
     * 购买记录仓储，用于准备候选记录并校验回填后的数据库状态。
     */
    private PurchaseRecordRepository purchaseRecordRepository;
    /**
     * 复核项仓储，用于校验无法安全回填时的人工复核记录。
     */
    private ReviewItemRepository reviewItemRepository;
    /**
     * 归一化规则仓储，用于创建测试规则和关键词。
     */
    private NormalizationRuleRepository normalizationRuleRepository;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Path.of("target", "normalization-rule-apply-service-test");
        Files.createDirectories(dir);
        Path db = dir.resolve("normalization-rule-apply.sqlite");
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer databaseInitializer = new DatabaseInitializer(jdbcTemplate);
        databaseInitializer.initialize();
        normalizationRuleRepository = new NormalizationRuleRepository(jdbcTemplate);
        purchaseRecordRepository = new PurchaseRecordRepository(jdbcTemplate);
        reviewItemRepository = new ReviewItemRepository(jdbcTemplate);
        service = new NormalizationRuleApplyService(
                databaseInitializer,
                normalizationRuleRepository,
                purchaseRecordRepository,
                reviewItemRepository,
                new ProductRuleMatcher(normalizationRuleRepository::listEnabledProductRules),
                new QuantityUnitParser()
        );
    }

    @Test
    void shouldPreviewWithoutWritingWhenDryRunIsDefault() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳日抛 10片", "自然棕", "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(command(null, null, null, null));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.applicableCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isZero();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).status()).isEqualTo("applicable");
        assertThat(result.items().get(0).after().normalizedName()).isEqualTo("美瞳");
        assertThat(result.items().get(0).after().quantity()).isEqualByComparingTo("10.0");
        PurchaseRecord record = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.normalizedName()).isEqualTo("美瞳日抛 10片");
        assertThat(record.decision()).isEqualTo("exclude");
        assertThat(record.normalizationRule()).isEqualTo("legacy_fallback");
    }

    @Test
    void shouldApplyRuleWhenDryRunIsExplicitFalseAndKeepOriginalFields() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳日抛 10片", "自然棕", "exclude", "legacy_fallback", 33.79D);
        PurchaseRecord before = purchaseRecordRepository.findById(recordId).orElseThrow();

        NormalizationApplyRuleToRecordsResult result = service.apply(command(false, null, null, null));

        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("updated");
        PurchaseRecord after = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(after.normalizedName()).isEqualTo("美瞳");
        assertThat(after.quantity()).isEqualTo(10D);
        assertThat(after.unit()).isEqualTo("片");
        assertThat(after.unitPrice()).isCloseTo(3.379D, within(0.000001D));
        assertThat(after.decision()).isEqualTo("include");
        assertThat(after.normalizationRule()).isEqualTo("contact_lenses");
        assertThat(after.productName()).isEqualTo(before.productName());
        assertThat(after.sku()).isEqualTo(before.sku());
        assertThat(after.platform()).isEqualTo(before.platform());
        assertThat(after.owner()).isEqualTo(before.owner());
        assertThat(after.orderTime()).isEqualTo(before.orderTime());
        assertThat(after.sourceText()).isEqualTo(before.sourceText());
        assertThat(after.sourceFile()).isEqualTo(before.sourceFile());
        assertThat(after.shopName()).isEqualTo(before.shopName());
        assertThat(after.note()).isEqualTo(before.note());
    }

    @Test
    void shouldSkipRecordWhenExcludeKeywordMatches() {
        long ruleId = createContactLensRule();
        normalizationRuleRepository.insertKeyword(ruleId, "取戴器", "exclude", 100, "test");
        long recordId = saveCandidate("美瞳取戴器套装 10片", "新手工具", "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(command(false, null, null, null));

        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("skipped");
        assertThat(result.items().get(0).warnings()).anyMatch(warning -> warning.contains("排除关键词"));
        PurchaseRecord record = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.decision()).isEqualTo("exclude");
        assertThat(record.normalizationRule()).isEqualTo("legacy_fallback");
    }

    @Test
    void shouldIgnoreRecordWhenOnlyExcludeKeywordMatches() {
        long ruleId = createContactLensRule();
        normalizationRuleRepository.insertKeyword(ruleId, "取戴器", "exclude", 100, "test");
        saveCandidate("取戴器套装 10片", "新手工具", "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(command(false, null, null, null));

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.matchedCount()).isZero();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void shouldSkipDuplicateAndNonUniqueRecords() {
        createContactLensRule();
        long duplicateRecordId = saveCandidate("美瞳日抛 10片", "自然棕", "exclude",
                "legacy_fallback", 33.79D, true, "unique");
        long nonUniqueRecordId = saveCandidate("美瞳日抛 20片", "自然黑", "exclude",
                "legacy_fallback", 40D, false, "duplicate");

        NormalizationApplyRuleToRecordsResult result = service.apply(command(false, null, null, null));

        assertThat(result.skippedCount()).isEqualTo(2);
        assertThat(result.items()).extracting("recordId").containsExactly(nonUniqueRecordId, duplicateRecordId);
        assertThat(purchaseRecordRepository.findById(duplicateRecordId).orElseThrow().decision()).isEqualTo("exclude");
        assertThat(purchaseRecordRepository.findById(nonUniqueRecordId).orElseThrow().decision()).isEqualTo("exclude");
    }

    @Test
    void shouldSkipRecordWhenResolvedExcludeReviewExists() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳日抛 10片", "自然棕", "exclude", "legacy_fallback", 33.79D);
        reviewItemRepository.create(recordId, "PRODUCT_NAME_NORMALIZATION_REVIEW", "manual exclude");
        Long reviewId = reviewItemRepository.listPendingDetails().get(0).id();
        reviewItemRepository.resolve(reviewId, "exclude", "人工确认排除");

        NormalizationApplyRuleToRecordsResult result = service.apply(command(false, null, null, null));

        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("skipped");
        PurchaseRecord record = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.decision()).isEqualTo("exclude");
        assertThat(record.normalizationRule()).isEqualTo("legacy_fallback");
    }

    @Test
    void shouldAllowPendingProductNameNormalizationReviewWhenRuleCanApply() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳日抛10片", "自然棕", "exclude", "legacy_fallback", 33.79D);
        reviewItemRepository.create(recordId, "PRODUCT_NAME_NORMALIZATION_REVIEW", "rule missing");

        NormalizationApplyRuleToRecordsResult result = service.apply(command(true, null, null, null));

        assertThat(result.applicableCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("applicable");
        assertThat(result.items().get(0).after().quantity()).isEqualByComparingTo("10.0");
        assertThat(result.items().get(0).after().unit()).isEqualTo("片");
        assertThat(result.items().get(0).after().unitPrice()).isEqualByComparingTo("3.379");
        assertThat(reviewItemRepository.listPendingDetails()).hasSize(1);
    }

    @Test
    void shouldAllowPendingQuantityUnitParseReviewWhenRuleCanApply() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳日抛10片", "自然棕", "exclude", "legacy_fallback", 33.79D);
        reviewItemRepository.create(recordId, "QUANTITY_UNIT_PARSE_REVIEW", "unit missing");

        NormalizationApplyRuleToRecordsResult result = service.apply(command(true, null, null, null));

        assertThat(result.applicableCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("applicable");
        assertThat(result.items().get(0).after().quantity()).isEqualByComparingTo("10.0");
        assertThat(result.items().get(0).after().unit()).isEqualTo("片");
    }

    @Test
    void shouldSkipRiskPendingReviewForRuleApply() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳日抛10片", "自然棕", "exclude", "legacy_fallback", 33.79D);
        reviewItemRepository.create(recordId, "DUPLICATE_ORDER", "duplicate risk");

        NormalizationApplyRuleToRecordsResult result = service.apply(command(false, null, null, null));

        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("skipped");
        assertThat(result.items().get(0).warnings()).anyMatch(warning -> warning.contains("阻断型复核"));
        assertThat(purchaseRecordRepository.findById(recordId).orElseThrow().decision()).isEqualTo("exclude");
        assertThat(reviewItemRepository.listPendingDetails()).hasSize(1);
    }

    @Test
    void shouldPreferSkuPieceQuantityOverProductNameQuantity() {
        createContactLensRule();
        saveCandidate("试用2片装 HanGee美瞳日抛无锁边小直径12mm自然13隐形眼镜素颜",
                "【6片美丽不重样】森林雾+乖乖片 茶茶棕 375", "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(command(true, null, null, null));

        assertThat(result.applicableCount()).isEqualTo(1);
        assertThat(result.items().get(0).after().quantity()).isEqualByComparingTo("6.0");
        assertThat(result.items().get(0).after().unit()).isEqualTo("片");
        assertThat(result.items().get(0).after().unitPrice()).isEqualByComparingTo("5.631666666667");
        assertThat(result.items().get(0).warnings()).contains("数量来源：sku");
    }

    @Test
    void shouldUseProductNamePieceQuantityWhenSkuHasNoTargetUnit() {
        createContactLensRule();
        saveCandidate("SweetColor日抛美瞳10片【淡颜系列】自然三明治工艺日追隐形眼镜",
                "【冷红美人】无锁边晕染红棕色 375", "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(command(true, null, null, null));

        assertThat(result.applicableCount()).isEqualTo(1);
        assertThat(result.items().get(0).after().quantity()).isEqualByComparingTo("10.0");
        assertThat(result.items().get(0).warnings()).contains("数量来源：product_name");
    }

    @Test
    void shouldRequireReviewWhenSkuHasConflictingPieceQuantities() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳日抛10片", "2片试用 + 6片组合",
                "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(command(false, null, null, null));

        assertThat(result.reviewRequiredCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("review_required");
        assertThat(purchaseRecordRepository.findById(recordId).orElseThrow().decision()).isEqualTo("exclude");
    }

    @Test
    void shouldNotTreatNonTargetNumbersAsPieceQuantity() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳自然款 375 14.5 42% 55% 12mm 13mm 0.06mm 50w# 25W#",
                "冷棕色 375 14.2 38% 12mm", "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(command(false, null, null, null));

        assertThat(result.reviewRequiredCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("review_required");
        assertThat(purchaseRecordRepository.findById(recordId).orElseThrow().decision()).isEqualTo("exclude");
    }

    @Test
    void shouldParseTargetCountByPackageMultiplier() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳组合装", "2盒*10片/盒",
                "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(command(true, null, null, null));
        var item = itemByRecordId(result, recordId);

        assertThat(item.status()).isEqualTo("applicable");
        assertThat(item.after().quantity()).isEqualByComparingTo("20");
        assertThat(item.after().unit()).isEqualTo("片");
        assertThat(item.warnings()).contains("数量来源：sku");
    }

    @Test
    void shouldNotConvertOtherCountUnitToTargetCountUnit() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳组合装", "10条装",
                "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(command(true, null, null, null));
        var item = itemByRecordId(result, recordId);

        assertThat(item.status()).isEqualTo("review_required");
        assertThat(item.after()).isNull();
        assertThat(item.warnings())
                .anyMatch(warning -> warning.contains("未找到明确 片 数量")
                        || warning.contains("标准数量未确认"));
    }

    @Test
    void shouldNotTreatPackageCountAsTargetCountUnit() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳组合装", "2盒",
                "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(command(true, null, null, null));
        var item = itemByRecordId(result, recordId);

        assertThat(item.status()).isEqualTo("review_required");
        assertThat(item.after()).isNull();
    }

    @Test
    void shouldFallbackOriginalCountOnlyWhenUnitEqualsTargetUnit() {
        createContactLensRule();
        long targetUnitRecordId = saveCandidateWithQuantity("美瞳默认规格", "默认规格",
                "exclude", "legacy_fallback", 33.79D, 10D, "片");
        long otherCountUnitRecordId = saveCandidateWithQuantity("美瞳默认规格", "默认规格",
                "exclude", "legacy_fallback", 33.79D, 10D, "条");

        NormalizationApplyRuleToRecordsResult result = service.apply(command(true, null, null, null));

        assertThat(itemByRecordId(result, targetUnitRecordId).status()).isEqualTo("applicable");
        assertThat(itemByRecordId(result, targetUnitRecordId).after().quantity()).isEqualByComparingTo("10");
        assertThat(itemByRecordId(result, targetUnitRecordId).after().unit()).isEqualTo("片");
        assertThat(itemByRecordId(result, otherCountUnitRecordId).status()).isEqualTo("review_required");
        assertThat(itemByRecordId(result, otherCountUnitRecordId).after()).isNull();
        assertThat(itemByRecordId(result, otherCountUnitRecordId).warnings())
                .anyMatch(warning -> warning.contains("原始记录单位不是目标计数单位"));
    }

    @Test
    void shouldParseDrawCountOnlyByTargetUnit() {
        createTissueRule();
        long validRecordId = saveCandidate("纸巾", "3包*100抽/包",
                "exclude", "legacy_fallback", 29.9D);
        long invalidRecordId = saveCandidate("纸巾", "3包",
                "exclude", "legacy_fallback", 29.9D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("tissue_draw_count_test", true,
                null, null, 100));

        assertThat(itemByRecordId(result, validRecordId).status()).isEqualTo("applicable");
        assertThat(itemByRecordId(result, validRecordId).after().quantity()).isEqualByComparingTo("300");
        assertThat(itemByRecordId(result, validRecordId).after().unit()).isEqualTo("抽");
        assertThat(itemByRecordId(result, invalidRecordId).status()).isEqualTo("review_required");
        assertThat(itemByRecordId(result, invalidRecordId).after()).isNull();
    }

    @Test
    void shouldParseWeightTotalFromSkuForGramRule() {
        createWeightRule("cat_wet_food", "g");
        long recordId = saveCandidate("Pjoy彼悦猫汤包鲜鸡汤猫咭幼猫成猫补水猫零食猫条猫汤60g*10袋",
                "浓汤补水 鲜炖鸡汤;600g【尝鲜装10包】", "exclude", "legacy_fallback", 30D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", true,
                null, null, 100));
        var item = itemByRecordId(result, recordId);

        assertThat(result.applicableCount()).isEqualTo(1);
        assertThat(item.status()).isEqualTo("applicable");
        assertThat(item.after().quantity()).isEqualByComparingTo("600");
        assertThat(item.after().unit()).isEqualTo("g");
        assertThat(item.after().unitPrice()).isEqualByComparingTo("0.05");
        assertThat(item.warnings()).contains("数量来源：sku");
    }

    @Test
    void shouldUseProductNameWeightWhenSkuHasNoReliableSpec() {
        createWeightRule("cat_wet_food", "g");
        long recordId = saveCandidate("猫汤60g*10袋", "混合口味", "exclude", "legacy_fallback", 30D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", true,
                null, null, 100));
        var item = itemByRecordId(result, recordId);

        assertThat(item.after().quantity()).isEqualByComparingTo("600");
        assertThat(item.after().unit()).isEqualTo("g");
        assertThat(item.warnings()).contains("数量来源：product_name");
    }

    @Test
    void shouldBlockProductNameFallbackWhenSkuPackageCountConflicts() {
        createWeightRule("cat_wet_food", "g");
        long recordId = saveCandidate("猫汤麦富迪猫罐头70g*10包", "混合口味;3包[试吃装]",
                "exclude", "legacy_fallback", 30D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", true,
                null, null, 100));
        var item = itemByRecordId(result, recordId);

        assertThat(item.status()).isEqualTo("review_required");
        assertThat(item.after()).isNull();
        assertThat(item.warnings()).anyMatch(warning -> warning.contains("SKU 包装数量与商品标题规格数量不一致"));
    }

    @Test
    void shouldUseProductNameFallbackWhenSkuHasNoPackageCount() {
        createWeightRule("cat_wet_food", "g");
        long recordId = saveCandidate("猫汤猫罐头70g*10包", "混合口味", "exclude", "legacy_fallback", 35D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", true,
                null, null, 100));
        var item = itemByRecordId(result, recordId);

        assertThat(item.status()).isEqualTo("applicable");
        assertThat(item.after().quantity()).isEqualByComparingTo("700");
        assertThat(item.after().unit()).isEqualTo("g");
        assertThat(item.warnings()).contains("数量来源：product_name");
    }

    @Test
    void shouldUseProductNameFallbackWhenSkuPackageCountMatches() {
        createWeightRule("cat_wet_food", "g");
        long recordId = saveCandidate("猫汤猫罐头70g*10包", "混合口味;10包[试吃装]",
                "exclude", "legacy_fallback", 35D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", true,
                null, null, 100));
        var item = itemByRecordId(result, recordId);

        assertThat(item.status()).isEqualTo("applicable");
        assertThat(item.after().quantity()).isEqualByComparingTo("700");
        assertThat(item.after().unit()).isEqualTo("g");
    }

    @Test
    void shouldParsePackageWeightAndConvertKgToGramFromSku() {
        createWeightRule("cat_wet_food", "g");
        long packageRecordId = saveCandidate("猫汤补水", "混合口味;6包【--80g/包】",
                "exclude", "legacy_fallback", 48D);
        long parenthesizedRecordId = saveCandidate("猫汤补水", "混合口味;10包)60g/包",
                "exclude", "legacy_fallback", 60D);
        long kilogramRecordId = saveCandidate("猫汤补水", "0.6kg",
                "exclude", "legacy_fallback", 30D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", true,
                null, null, 100));

        assertThat(itemByRecordId(result, packageRecordId).after().quantity()).isEqualByComparingTo("480");
        assertThat(itemByRecordId(result, packageRecordId).after().unit()).isEqualTo("g");
        assertThat(itemByRecordId(result, packageRecordId).warnings())
                .contains("规格解析：6包 * 80g/包 => 480g");
        assertThat(itemByRecordId(result, parenthesizedRecordId).after().quantity()).isEqualByComparingTo("600");
        assertThat(itemByRecordId(result, parenthesizedRecordId).warnings())
                .contains("规格解析：10包 * 60g/包 => 600g");
        assertThat(itemByRecordId(result, kilogramRecordId).after().quantity()).isEqualByComparingTo("600");
        assertThat(itemByRecordId(result, kilogramRecordId).after().unit()).isEqualTo("g");
    }

    @Test
    void shouldTreatConsistentSkuSpecificationsAsApplicable() {
        createWeightRule("cat_wet_food", "g");
        long recordId = saveCandidate("猫汤主食餐包", "全价主食餐包50g*3包;150g",
                "exclude", "legacy_fallback", 30D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", true,
                null, null, 100));
        var item = itemByRecordId(result, recordId);

        assertThat(item.status()).isEqualTo("applicable");
        assertThat(item.after().quantity()).isEqualByComparingTo("150");
        assertThat(item.after().quantity().toPlainString()).isEqualTo("150");
        assertThat(item.after().unit()).isEqualTo("g");
        assertThat(item.warnings()).contains("数量来源：sku", "规格解析：50g * 3包 => 150g");
    }

    @Test
    void shouldConvertGramSkuToKilogramRuleUnit() {
        createWeightRule("cat_wet_food_kg", "kg");
        long recordId = saveCandidate("猫汤补水", "600g", "exclude", "legacy_fallback", 30D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food_kg", true,
                null, null, 100));
        var item = itemByRecordId(result, recordId);

        assertThat(item.after().quantity()).isEqualByComparingTo("0.6");
        assertThat(item.after().unit()).isEqualTo("kg");
        assertThat(item.after().unitPrice()).isEqualByComparingTo("50");
    }

    @Test
    void shouldParseVolumeSkuToStandardLiter() {
        createVolumeRule();
        long recordId = saveCandidate("沐浴露补充装", "500ml*2瓶", "exclude", "legacy_fallback", 39.9D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("body_wash", true,
                null, null, 100));
        var item = itemByRecordId(result, recordId);

        assertThat(item.after().quantity()).isEqualByComparingTo("1");
        assertThat(item.after().unit()).isEqualTo("L");
        assertThat(item.warnings()).contains("数量来源：sku");
    }

    @Test
    void shouldKeepReviewRequiredForPackageOnlyAndLooseWeightSpec() {
        createWeightRule("cat_wet_food", "g");
        long packageOnlyRecordId = saveCandidate("猫汤补水", "12罐【1盒】",
                "exclude", "legacy_fallback", 30D);
        long looseSpecRecordId = saveCandidate("猫汤补水", "混合口味*18包 25g",
                "exclude", "legacy_fallback", 30D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", true,
                null, null, 100));

        assertThat(itemByRecordId(result, packageOnlyRecordId).status()).isEqualTo("review_required");
        assertThat(itemByRecordId(result, packageOnlyRecordId).after()).isNull();
        assertThat(itemByRecordId(result, packageOnlyRecordId).warnings())
                .anyMatch(warning -> warning.contains("缺少每份"));
        assertThat(itemByRecordId(result, looseSpecRecordId).status()).isEqualTo("review_required");
        assertThat(itemByRecordId(result, looseSpecRecordId).after()).isNull();

        List<String> packageOnlyWarnings = itemByRecordId(result, packageOnlyRecordId).warnings();
        assertThat(packageOnlyWarnings).doesNotHaveDuplicates();
        assertThat(packageOnlyWarnings).contains("标准数量未确认，无法重算 unit_price");
        assertThat(packageOnlyWarnings).doesNotContain("无法重算有效 unit_price");

        List<String> looseSpecWarnings = itemByRecordId(result, looseSpecRecordId).warnings();
        assertThat(looseSpecWarnings).doesNotHaveDuplicates();
        assertThat(looseSpecWarnings).contains("标准数量未确认，无法重算 unit_price");
        assertThat(looseSpecWarnings).doesNotContain("无法重算有效 unit_price");
    }

    @Test
    void shouldRequireReviewWhenSkuContainsConflictingWeightSpecs() {
        createWeightRule("cat_wet_food", "g");
        long recordId = saveCandidate("猫汤补水", "600g[80g*6包]",
                "exclude", "legacy_fallback", 30D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", true,
                null, null, 100));

        assertThat(itemByRecordId(result, recordId).status()).isEqualTo("review_required");
        assertThat(itemByRecordId(result, recordId).warnings())
                .anyMatch(warning -> warning.contains("多个冲突规格"));
    }

    @Test
    void shouldPreferSkuWeightOverProductNameWeight() {
        createWeightRule("cat_wet_food", "g");
        long recordId = saveCandidate("猫汤60g*10袋", "尝鲜装300g",
                "exclude", "legacy_fallback", 30D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", true,
                null, null, 100));
        var item = itemByRecordId(result, recordId);

        assertThat(item.after().quantity()).isEqualByComparingTo("300");
        assertThat(item.warnings()).contains("数量来源：sku");
    }

    @Test
    void shouldConvertOriginalRecordUnitWhenTextHasNoSpec() {
        createWeightRule("cat_wet_food", "g");
        long recordId = saveCandidateWithQuantity("猫汤默认规格", "默认规格", "exclude", "legacy_fallback",
                30D, 0.6D, "kg");

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", true,
                null, null, 100));
        var item = itemByRecordId(result, recordId);

        assertThat(item.after().quantity()).isEqualByComparingTo("600");
        assertThat(item.after().unit()).isEqualTo("g");
        assertThat(item.warnings()).contains("数量来源：original_record");
    }

    @Test
    void shouldApplyParsedWeightRuleWhenDryRunIsFalseAndKeepOriginalFields() {
        createWeightRule("cat_wet_food", "g");
        long recordId = saveCandidate("猫汤补水", "600g", "exclude", "legacy_fallback", 30D);
        PurchaseRecord before = purchaseRecordRepository.findById(recordId).orElseThrow();

        NormalizationApplyRuleToRecordsResult result = service.apply(commandForRule("cat_wet_food", false,
                null, null, 100));

        assertThat(result.updatedCount()).isEqualTo(1);
        PurchaseRecord after = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(after.normalizedName()).isEqualTo("猫湿粮");
        assertThat(after.quantity()).isEqualTo(600D);
        assertThat(after.unit()).isEqualTo("g");
        assertThat(after.unitPrice()).isEqualTo(0.05D);
        assertThat(after.decision()).isEqualTo("include");
        assertThat(after.normalizationRule()).isEqualTo("cat_wet_food");
        assertThat(after.productName()).isEqualTo(before.productName());
        assertThat(after.sku()).isEqualTo(before.sku());
        assertThat(after.platform()).isEqualTo(before.platform());
        assertThat(after.owner()).isEqualTo(before.owner());
        assertThat(after.orderTime()).isEqualTo(before.orderTime());
        assertThat(after.sourceText()).isEqualTo(before.sourceText());
        assertThat(after.sourceFile()).isEqualTo(before.sourceFile());
        assertThat(after.shopName()).isEqualTo(before.shopName());
        assertThat(after.note()).isEqualTo(before.note());
    }

    @Test
    void shouldResolveRuleApplyResolvableReviewsAfterSuccessfulApply() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳日抛10片", "自然棕", "exclude", "legacy_fallback", 33.79D);
        reviewItemRepository.create(recordId, "PRODUCT_NAME_NORMALIZATION_REVIEW", "rule missing");
        reviewItemRepository.create(recordId, "QUANTITY_UNIT_PARSE_REVIEW", "unit missing");
        List<Long> reviewIds = reviewItemRepository.listPendingDetails().stream()
                .map(ReviewItemDetail::id)
                .toList();

        NormalizationApplyRuleToRecordsResult result = service.apply(command(false, null, null, null));

        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(reviewItemRepository.listPendingDetails()).isEmpty();
        for (Long reviewId : reviewIds) {
            assertThat(reviewItemRepository.findById(reviewId).orElseThrow().reviewDecision())
                    .isEqualTo("rule_applied");
        }
    }

    @Test
    void shouldCreateReviewItemWhenQuantityCanNotBeParsedOnApply() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳自然款", "默认规格", "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(command(false, null, null, null));

        assertThat(result.reviewRequiredCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("review_required");
        PurchaseRecord record = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.decision()).isEqualTo("exclude");
        assertThat(record.normalizationRule()).isEqualTo("legacy_fallback");
        List<ReviewItemDetail> pendingDetails = reviewItemRepository.listPendingDetails();
        assertThat(pendingDetails).hasSize(1);
        assertThat(pendingDetails.get(0).recordId()).isEqualTo(recordId);
        assertThat(pendingDetails.get(0).reasonCode()).isEqualTo("QUANTITY_UNIT_PARSE_REVIEW");
    }

    @Test
    void shouldKeepDefaultLegacyFallbackAndExcludedFilters() {
        createContactLensRule();
        long stableRecordId = saveCandidate("美瞳日抛 10片", "自然棕", "include", "other_rule", 33.79D);
        long includedFallbackRecordId = saveCandidate("美瞳日抛 20片", "自然黑", "include", "legacy_fallback", 40D);

        NormalizationApplyRuleToRecordsResult defaultResult = service.apply(command(false, null, null, null));
        NormalizationApplyRuleToRecordsResult includeAllowedResult = service.apply(command(true, null, false, null));

        assertThat(defaultResult.items()).isEmpty();
        assertThat(includeAllowedResult.items()).extracting("recordId").containsExactly(includedFallbackRecordId);
        assertThat(purchaseRecordRepository.findById(stableRecordId).orElseThrow().normalizationRule())
                .isEqualTo("other_rule");
        assertThat(purchaseRecordRepository.findById(includedFallbackRecordId).orElseThrow().decision())
                .isEqualTo("include");
    }

    @Test
    void shouldUseDefaultSafetyOptionsInFamilyScope() {
        createContactLensRule();
        long firstRecordId = saveCandidate("美瞳日抛 10片", "默认安全参数", "exclude", "legacy_fallback", 33.79D);
        saveCandidate("美瞳日抛 20片", "已纳入统计", "include", "legacy_fallback", 40D);
        saveCandidate("美瞳日抛 30片", "已有明确规则", "exclude", "other_rule", 60D);
        for (int index = 0; index < 100; index++) {
            saveCandidate("美瞳日抛 10片", "默认 limit " + index, "exclude", "legacy_fallback", 33.79D);
        }

        NormalizationApplyRuleToRecordsResult result = service.apply(commandWithScope(null, null, null,
                null, null, null));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(100);
        assertThat(result.matchedCount()).isEqualTo(100);
        PurchaseRecord record = purchaseRecordRepository.findById(firstRecordId).orElseThrow();
        assertThat(record.decision()).isEqualTo("exclude");
        assertThat(record.normalizationRule()).isEqualTo("legacy_fallback");
    }

    @Test
    void shouldAllowFamilyDryRunWithoutBatchIdOrOwner() {
        createContactLensRule();
        long recordId = saveCandidate("美瞳日抛 10片", "自然棕", "exclude", "legacy_fallback", 33.79D);

        NormalizationApplyRuleToRecordsResult result = service.apply(commandWithScope(null, null, true,
                true, true, 100));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        PurchaseRecord record = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.normalizedName()).isEqualTo("美瞳日抛 10片");
        assertThat(record.decision()).isEqualTo("exclude");
        assertThat(record.normalizationRule()).isEqualTo("legacy_fallback");
    }

    @Test
    void shouldAllowFamilyApplyWithoutBatchIdOrOwnerAndKeepSafetyBoundaries() {
        long ruleId = createContactLensRule();
        normalizationRuleRepository.insertKeyword(ruleId, "取戴器", "exclude", 100, "test");
        long validRecordId = saveCandidate("美瞳日抛 10片", "自然棕", "exclude", "legacy_fallback", 33.79D);
        long duplicateRecordId = saveCandidate("美瞳日抛 20片", "自然黑", "exclude",
                "legacy_fallback", 40D, true, "unique");
        long nonUniqueRecordId = saveCandidate("美瞳日抛 30片", "自然灰", "exclude",
                "legacy_fallback", 60D, false, "duplicate");
        long excludeKeywordRecordId = saveCandidate("美瞳取戴器套装 10片", "新手工具",
                "exclude", "legacy_fallback", 33.79D);
        long reviewRecordId = saveCandidate("美瞳自然款", "默认规格", "exclude", "legacy_fallback", 33.79D);
        long resolvedExcludeRecordId = saveCandidate("美瞳日抛 40片", "自然蓝",
                "exclude", "legacy_fallback", 80D);
        reviewItemRepository.create(resolvedExcludeRecordId, "PRODUCT_NAME_NORMALIZATION_REVIEW", "manual exclude");
        Long reviewId = reviewItemRepository.listPendingDetails().get(0).id();
        reviewItemRepository.resolve(reviewId, "exclude", "人工确认排除");

        NormalizationApplyRuleToRecordsResult result = service.apply(commandWithScope(null, null, false,
                true, true, 100));

        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.reviewRequiredCount()).isEqualTo(1);
        assertThat(result.skippedCount()).isEqualTo(4);
        assertThat(purchaseRecordRepository.findById(validRecordId).orElseThrow().decision()).isEqualTo("include");
        assertThat(purchaseRecordRepository.findById(duplicateRecordId).orElseThrow().decision()).isEqualTo("exclude");
        assertThat(purchaseRecordRepository.findById(nonUniqueRecordId).orElseThrow().decision()).isEqualTo("exclude");
        assertThat(purchaseRecordRepository.findById(excludeKeywordRecordId).orElseThrow().decision()).isEqualTo("exclude");
        assertThat(purchaseRecordRepository.findById(reviewRecordId).orElseThrow().decision()).isEqualTo("exclude");
        assertThat(purchaseRecordRepository.findById(resolvedExcludeRecordId).orElseThrow().decision()).isEqualTo("exclude");
        assertThat(reviewItemRepository.listPendingDetails()).anyMatch(item -> reviewRecordId == item.recordId());
    }

    @Test
    void shouldFilterApplyCandidatesByBatchIdWhenProvided() {
        createContactLensRule();
        long targetRecordId = saveCandidate(9001L, "jtxw", "美瞳日抛 10片", "自然棕",
                "exclude", "legacy_fallback", 33.79D, false, "unique");
        long otherBatchRecordId = saveCandidate(9002L, "jtxw", "美瞳日抛 20片", "自然黑",
                "exclude", "legacy_fallback", 40D, false, "unique");

        NormalizationApplyRuleToRecordsResult result = service.apply(commandWithScope(9001L, null, false,
                true, true, 100));

        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.items()).extracting("recordId").containsExactly(targetRecordId);
        assertThat(purchaseRecordRepository.findById(targetRecordId).orElseThrow().decision()).isEqualTo("include");
        assertThat(purchaseRecordRepository.findById(otherBatchRecordId).orElseThrow().decision()).isEqualTo("exclude");
    }

    @Test
    void shouldFilterApplyCandidatesByOwnerWhenProvided() {
        createContactLensRule();
        long targetRecordId = saveCandidate(9001L, "lj", "美瞳日抛 10片", "自然棕",
                "exclude", "legacy_fallback", 33.79D, false, "unique");
        long otherOwnerRecordId = saveCandidate(9001L, "jtxw", "美瞳日抛 20片", "自然黑",
                "exclude", "legacy_fallback", 40D, false, "unique");

        NormalizationApplyRuleToRecordsResult result = service.apply(commandWithScope(null, "lj", false,
                true, true, 100));

        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.items()).extracting("recordId").containsExactly(targetRecordId);
        assertThat(purchaseRecordRepository.findById(targetRecordId).orElseThrow().decision()).isEqualTo("include");
        assertThat(purchaseRecordRepository.findById(otherOwnerRecordId).orElseThrow().decision()).isEqualTo("exclude");
    }

    private NormalizationLibraryOperationCommand command(Boolean dryRun,
                                                         Boolean onlyLegacyFallback,
                                                         Boolean onlyExcluded,
                                                         Integer limit) {
        return commandWithScope(9001L, null, dryRun, onlyLegacyFallback, onlyExcluded, limit);
    }

    private NormalizationLibraryOperationCommand commandWithScope(Long batchId,
                                                                  String owner,
                                                                  Boolean dryRun,
                                                                  Boolean onlyLegacyFallback,
                                                                  Boolean onlyExcluded,
                                                                  Integer limit) {
        return commandForRuleWithScope("contact_lenses", batchId, owner, dryRun, onlyLegacyFallback, onlyExcluded,
                limit);
    }

    private NormalizationLibraryOperationCommand commandForRule(String ruleCode,
                                                                Boolean dryRun,
                                                                Boolean onlyLegacyFallback,
                                                                Boolean onlyExcluded,
                                                                Integer limit) {
        return commandForRuleWithScope(ruleCode, 9001L, null, dryRun, onlyLegacyFallback, onlyExcluded, limit);
    }

    private NormalizationLibraryOperationCommand commandForRuleWithScope(String ruleCode,
                                                                         Long batchId,
                                                                         String owner,
                                                                         Boolean dryRun,
                                                                         Boolean onlyLegacyFallback,
                                                                         Boolean onlyExcluded,
                                                                         Integer limit) {
        return new NormalizationLibraryOperationCommand(
                "apply_rule_to_records",
                ruleCode,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                batchId,
                owner,
                onlyLegacyFallback,
                onlyExcluded,
                dryRun,
                limit
        );
    }

    private long createContactLensRule() {
        long ruleId = normalizationRuleRepository.createRule(
                "contact_lenses", "美瞳", "个护美妆", "片", "count", 120, "test");
        normalizationRuleRepository.insertKeyword(ruleId, "美瞳", "include", 100, "test");
        return ruleId;
    }

    private long createTissueRule() {
        long ruleId = normalizationRuleRepository.createRule(
                "tissue_draw_count_test", "纸巾计数测试", "家庭清洁", "抽", "draw_count", 120, "test");
        normalizationRuleRepository.insertKeyword(ruleId, "纸巾", "include", 100, "test");
        return ruleId;
    }

    private long createWeightRule(String ruleCode, String standardUnit) {
        long ruleId = normalizationRuleRepository.createRule(
                ruleCode, "猫湿粮", "宠物食品", standardUnit, "weight", 120, "test");
        normalizationRuleRepository.insertKeyword(ruleId, "猫汤", "include", 100, "test");
        return ruleId;
    }

    private long createVolumeRule() {
        long ruleId = normalizationRuleRepository.createRule(
                "body_wash", "沐浴露", "个护清洁", "L", "volume", 120, "test");
        normalizationRuleRepository.insertKeyword(ruleId, "沐浴露", "include", 100, "test");
        return ruleId;
    }

    private long saveCandidate(String productName,
                               String sku,
                               String decision,
                               String normalizationRule,
                               Double totalAmount) {
        return saveCandidate(9001L, "jtxw", productName, sku, decision, normalizationRule, totalAmount,
                false, "unique");
    }

    private long saveCandidate(String productName,
                               String sku,
                               String decision,
                               String normalizationRule,
                               Double totalAmount,
                               boolean duplicate,
                               String dedupeStatus) {
        return saveCandidate(9001L, "jtxw", productName, sku, decision, normalizationRule, totalAmount,
                duplicate, dedupeStatus);
    }

    private long saveCandidateWithQuantity(String productName,
                                           String sku,
                                           String decision,
                                           String normalizationRule,
                                           Double totalAmount,
                                           Double quantity,
                                           String unit) {
        return saveCandidate(9001L, "jtxw", productName, sku, decision, normalizationRule, totalAmount,
                false, "unique", quantity, unit);
    }

    private long saveCandidate(Long batchId,
                               String owner,
                               String productName,
                               String sku,
                               String decision,
                               String normalizationRule,
                               Double totalAmount,
                               boolean duplicate,
                               String dedupeStatus) {
        return saveCandidate(batchId, owner, productName, sku, decision, normalizationRule, totalAmount,
                duplicate, dedupeStatus, 1D, "件");
    }

    private long saveCandidate(Long batchId,
                               String owner,
                               String productName,
                               String sku,
                               String decision,
                               String normalizationRule,
                               Double totalAmount,
                               boolean duplicate,
                               String dedupeStatus,
                               Double quantity,
                               String unit) {
        return purchaseRecordRepository.save(new PurchaseRecord(
                null,
                batchId,
                "2026-06-15 08:00:00",
                "jd",
                owner,
                productName,
                productName,
                sku,
                "个护美妆",
                "隐形眼镜",
                quantity,
                unit,
                totalAmount,
                totalAmount,
                totalAmount,
                0D,
                "paid_amount",
                totalAmount,
                "CNY",
                decision,
                duplicate,
                dedupeStatus,
                "orders.csv",
                "测试店铺",
                "原始备注",
                "原始自然语言",
                normalizationRule,
                null
        ));
    }

    private NormalizationApplyRuleToRecordsItem itemByRecordId(NormalizationApplyRuleToRecordsResult result,
                                                               long recordId) {
        return result.items().stream()
                .filter(item -> item.recordId() == recordId)
                .findFirst()
                .orElseThrow();
    }
}
