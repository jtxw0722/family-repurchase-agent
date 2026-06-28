package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.NormalizationLibraryOperationCommand;
import com.jtxw.familyagent.domain.model.NormalizationRecheckRuleRecordsResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.ProductRuleMatcher;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationRuleRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import org.junit.jupiter.api.BeforeEach;
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
 * @Date: 2026/06/25 19:14:00
 * @Description: 归一化规则历史样本重算服务测试，覆盖规则解绑、重新归一化、待复核和筛选边界
 */
class NormalizationRuleRecheckServiceTest {
    /**
     * 被测历史样本重算服务。
     */
    private NormalizationRuleRecheckService service;
    /**
     * 购买记录仓储，用于准备历史样本并校验重算后的数据库状态。
     */
    private PurchaseRecordRepository purchaseRecordRepository;
    /**
     * 归一化规则仓储，用于读取默认规则并补充排除关键词。
     */
    private NormalizationRuleRepository normalizationRuleRepository;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Path.of("target", "normalization-rule-recheck-service-test");
        Files.createDirectories(dir);
        Path db = dir.resolve("normalization-rule-recheck.sqlite");
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer databaseInitializer = new DatabaseInitializer(jdbcTemplate);
        databaseInitializer.initialize();
        normalizationRuleRepository = new NormalizationRuleRepository(jdbcTemplate);
        purchaseRecordRepository = new PurchaseRecordRepository(jdbcTemplate);
        service = new NormalizationRuleRecheckService(databaseInitializer, normalizationRuleRepository,
                purchaseRecordRepository, new ProductRuleMatcher(normalizationRuleRepository::listEnabledProductRules));
    }

    @Test
    void shouldPreviewResetMatchedExcludeKeywordWithoutWriting() {
        ensureCatFoodExcludeKeyword();
        long recordId = saveRecord("某品牌冻干猫粮", "1kg", "猫粮", "cat_food", "include",
                false, "unique");

        NormalizationRecheckRuleRecordsResult result = service.recheck(command(true, null, null, 100));

        assertThat(result.success()).isTrue();
        assertThat(result.dryRun()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.excludedCount()).isEqualTo(1);
        assertThat(result.resetCount()).isEqualTo(1);
        assertThat(result.normalizedCount()).isZero();
        assertThat(result.updatedCount()).isZero();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).status()).isEqualTo("would_reset");
        assertThat(result.items().get(0).matchedExcludeKeyword()).isEqualTo("冻干");
        PurchaseRecord record = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.normalizedName()).isEqualTo("猫粮");
        assertThat(record.normalizationRule()).isEqualTo("cat_food");
        assertThat(record.decision()).isEqualTo("include");
    }

    @Test
    void shouldResetMatchedRecordWhenDryRunIsFalse() {
        ensureCatFoodExcludeKeyword();
        long recordId = saveRecord("某品牌冻干猫粮", "10g*3包", "猫粮", "cat_food", "include",
                false, "unique");
        PurchaseRecord before = purchaseRecordRepository.findById(recordId).orElseThrow();

        NormalizationRecheckRuleRecordsResult result = service.recheck(command(false, null, null, 100));

        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.excludedCount()).isEqualTo(1);
        assertThat(result.resetCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("reset");
        PurchaseRecord after = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(after.decision()).isEqualTo("include");
        assertThat(after.normalizationRule()).isEqualTo("legacy_fallback");
        assertThat(after.normalizedName()).isEqualTo(before.productName());
        assertThat(after.productName()).isEqualTo(before.productName());
        assertThat(after.sku()).isEqualTo(before.sku());
        assertThat(after.owner()).isEqualTo(before.owner());
        assertThat(after.orderTime()).isEqualTo(before.orderTime());
        assertThat(after.quantity()).isEqualTo(before.quantity());
        assertThat(after.unit()).isEqualTo(before.unit());
        assertThat(after.unitPrice()).isEqualTo(before.unitPrice());
    }

    @Test
    void shouldNotReturnItemWhenExcludeKeywordDoesNotMatch() {
        ensureCatFoodExcludeKeyword();
        long recordId = saveRecord("某品牌全价猫粮", "1kg", "猫粮", "cat_food", "include",
                false, "unique");

        NormalizationRecheckRuleRecordsResult result = service.recheck(command(false, null, null, 100));

        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.matchedCount()).isZero();
        assertThat(result.items()).isEmpty();
        assertThat(purchaseRecordRepository.findById(recordId).orElseThrow().decision()).isEqualTo("include");
    }

    @Test
    void shouldNotProcessOtherRuleRecordsWhenMatcherDoesNotHitCurrentRule() {
        ensureCatFoodExcludeKeyword();
        long recordId = saveRecord("某品牌冻干猫砂", "1kg", "猫砂", "cat_litter", "include",
                false, "unique");

        NormalizationRecheckRuleRecordsResult result = service.recheck(command(false, null, null, 100));

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.items()).isEmpty();
        assertThat(purchaseRecordRepository.findById(recordId).orElseThrow().decision()).isEqualTo("include");
    }

    @Test
    void shouldRejectMissingOrDisabledRule() {
        assertThatThrownBy(() -> service.recheck(commandForRule("missing_rule", true, null, null, 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("归一化规则不存在：missing_rule");

        long ruleId = normalizationRuleRepository.createRule("disabled_recheck_rule", "禁用重算规则",
                "测试", "kg", "weight", 90, "test");
        normalizationRuleRepository.disableRule(ruleId);

        assertThatThrownBy(() -> service.recheck(commandForRule("disabled_recheck_rule", true, null, null, 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("归一化规则未启用：disabled_recheck_rule");
    }

    @Test
    void shouldSkipDuplicateOrNonUniqueMatchedRecords() {
        ensureCatFoodExcludeKeyword();
        long duplicateRecordId = saveRecord("某品牌冻干猫粮", "1kg", "猫粮", "cat_food", "include",
                true, "unique");
        long nonUniqueRecordId = saveRecord("某品牌冻干猫粮", "2kg", "猫粮", "cat_food", "include",
                false, "duplicate");

        NormalizationRecheckRuleRecordsResult result = service.recheck(command(false, null, null, 100));

        assertThat(result.candidateCount()).isEqualTo(4);
        assertThat(result.matchedCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(2);
        assertThat(result.items()).extracting("status").containsExactly("skipped", "skipped");
        assertThat(purchaseRecordRepository.findById(duplicateRecordId).orElseThrow().decision()).isEqualTo("include");
        assertThat(purchaseRecordRepository.findById(nonUniqueRecordId).orElseThrow().decision()).isEqualTo("include");
    }

    @Test
    void shouldIgnoreApplyOnlyFiltersAndReturnWarning() {
        ensureCatFoodExcludeKeyword();
        saveRecord("某品牌冻干猫粮", "1kg", "猫粮", "cat_food", "include", false, "unique");

        NormalizationRecheckRuleRecordsResult result = service.recheck(command(true, true, true, 100));

        assertThat(result.warnings()).anyMatch(warning -> warning.contains("不使用 onlyLegacyFallback / onlyExcluded"));
        assertThat(result.matchedCount()).isEqualTo(1);
    }

    @Test
    void shouldNormalizeMatchedIncludeRecordWhenUnitAlreadyMatches() {
        createFreezeFoodRule();
        long recordId = saveRecord("某品牌主食冻干", "30g", "某品牌主食冻干",
                "legacy_fallback", "include", false, "unique", 30D, "g", 60D);

        NormalizationRecheckRuleRecordsResult result = service.recheck(commandForRule("cat_food_freeze",
                false, null, null, 100));

        assertThat(result.normalizedCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.items().get(0).status()).isEqualTo("normalized");
        PurchaseRecord record = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.normalizedName()).isEqualTo("冻干");
        assertThat(record.normalizationRule()).isEqualTo("cat_food_freeze");
        assertThat(record.decision()).isEqualTo("include");
        assertThat(record.quantity()).isEqualTo(30D);
        assertThat(record.unit()).isEqualTo("g");
        assertThat(record.unitPrice()).isEqualTo(2D);
    }

    @Test
    void shouldRespectMatcherExcludeAndPriorityAcrossRules() {
        ensureCatFoodExcludeKeyword();
        createFreezeFoodRule();
        long recordId = saveRecord("某品牌冻干猫粮", "30g", "猫粮",
                "cat_food", "include", false, "unique", 30D, "g", 60D);

        NormalizationRecheckRuleRecordsResult catFoodResult = service.recheck(commandForRule("cat_food",
                false, null, null, 100));

        assertThat(catFoodResult.resetCount()).isEqualTo(1);
        assertThat(catFoodResult.items().get(0).status()).isEqualTo("reset");
        assertThat(purchaseRecordRepository.findById(recordId).orElseThrow().normalizationRule())
                .isEqualTo("legacy_fallback");

        NormalizationRecheckRuleRecordsResult freezeResult = service.recheck(commandForRule("cat_food_freeze",
                false, null, null, 100));

        assertThat(freezeResult.normalizedCount()).isEqualTo(1);
        assertThat(freezeResult.items().get(0).status()).isEqualTo("normalized");
        PurchaseRecord record = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.normalizedName()).isEqualTo("冻干");
        assertThat(record.normalizationRule()).isEqualTo("cat_food_freeze");
        assertThat(record.decision()).isEqualTo("include");
    }

    @Test
    void shouldRequireReviewWhenMatchedRuleUnitDoesNotMatchRecordUnit() {
        createFreezeFoodRule();
        long recordId = saveRecord("某品牌主食冻干", "默认规格", "某品牌主食冻干",
                "legacy_fallback", "include", false, "unique", 1D, "件", 60D);

        NormalizationRecheckRuleRecordsResult result = service.recheck(commandForRule("cat_food_freeze",
                false, null, null, 100));

        assertThat(result.reviewRequiredCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isZero();
        assertThat(result.items().get(0).status()).isEqualTo("review_required");
        PurchaseRecord record = purchaseRecordRepository.findById(recordId).orElseThrow();
        assertThat(record.normalizedName()).isEqualTo("某品牌主食冻干");
        assertThat(record.normalizationRule()).isEqualTo("legacy_fallback");
        assertThat(record.unit()).isEqualTo("件");
    }

    @Test
    void shouldFilterRecheckCandidatesByOwnerAndBatchId() {
        createFreezeFoodRule();
        long targetRecordId = saveRecord(9001L, "lj", "某品牌主食冻干", "30g",
                "某品牌主食冻干", "legacy_fallback", "include", false, "unique", 30D, "g", 60D);
        long otherOwnerRecordId = saveRecord(9001L, "jtxw", "某品牌主食冻干", "30g",
                "某品牌主食冻干", "legacy_fallback", "include", false, "unique", 30D, "g", 60D);
        long otherBatchRecordId = saveRecord(9002L, "lj", "某品牌主食冻干", "30g",
                "某品牌主食冻干", "legacy_fallback", "include", false, "unique", 30D, "g", 60D);

        NormalizationRecheckRuleRecordsResult result = service.recheck(commandForRuleWithScope(
                "cat_food_freeze", 9001L, "lj", false, null, null, 100));

        assertThat(result.normalizedCount()).isEqualTo(1);
        assertThat(result.items()).extracting("recordId").containsExactly(targetRecordId);
        assertThat(purchaseRecordRepository.findById(targetRecordId).orElseThrow().normalizationRule())
                .isEqualTo("cat_food_freeze");
        assertThat(purchaseRecordRepository.findById(otherOwnerRecordId).orElseThrow().normalizationRule())
                .isEqualTo("legacy_fallback");
        assertThat(purchaseRecordRepository.findById(otherBatchRecordId).orElseThrow().normalizationRule())
                .isEqualTo("legacy_fallback");
    }

    private void ensureCatFoodExcludeKeyword() {
        NormalizationRuleRepository.NormalizationRuleRow rule = normalizationRuleRepository.findRuleByCode("cat_food")
                .orElseThrow();
        normalizationRuleRepository.findKeyword(rule.id(), "冻干", "exclude")
                .ifPresentOrElse(keyword -> {
                    if (!keyword.enabled()) {
                        normalizationRuleRepository.enableKeyword(keyword.id(), 100, "test");
                    }
                }, () -> normalizationRuleRepository.insertKeyword(rule.id(), "冻干", "exclude", 100, "test"));
    }

    private NormalizationLibraryOperationCommand command(Boolean dryRun,
                                                         Boolean onlyLegacyFallback,
                                                         Boolean onlyExcluded,
                                                         Integer limit) {
        return commandForRule("cat_food", dryRun, onlyLegacyFallback, onlyExcluded, limit);
    }

    private NormalizationLibraryOperationCommand commandForRule(String ruleCode,
                                                                Boolean dryRun,
                                                                Boolean onlyLegacyFallback,
                                                                Boolean onlyExcluded,
                                                                Integer limit) {
        return commandForRuleWithScope(ruleCode, null, null, dryRun, onlyLegacyFallback, onlyExcluded, limit);
    }

    private NormalizationLibraryOperationCommand commandForRuleWithScope(String ruleCode,
                                                                         Long batchId,
                                                                         String owner,
                                                                         Boolean dryRun,
                                                                         Boolean onlyLegacyFallback,
                                                                         Boolean onlyExcluded,
                                                                         Integer limit) {
        return new NormalizationLibraryOperationCommand(
                "recheck_rule_records",
                ruleCode,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                batchId,
                owner,
                onlyLegacyFallback,
                onlyExcluded,
                dryRun,
                limit
        );
    }

    private void createFreezeFoodRule() {
        long ruleId = normalizationRuleRepository.createRule(
                "cat_food_freeze", "冻干", "宠物用品", "g", "weight", 95, "test");
        normalizationRuleRepository.insertKeyword(ruleId, "冻干", "include", 100, "test");
        normalizationRuleRepository.insertKeyword(ruleId, "主食冻干", "include", 100, "test");
        normalizationRuleRepository.insertKeyword(ruleId, "咖啡", "exclude", 100, "test");
    }

    private long saveRecord(String productName,
                            String sku,
                            String normalizedName,
                            String normalizationRule,
                            String decision,
                            boolean duplicate,
                            String dedupeStatus) {
        return purchaseRecordRepository.save(new PurchaseRecord(
                null,
                9001L,
                "2026-06-25 08:00:00",
                "jd",
                "jtxw",
                productName,
                normalizedName,
                sku,
                "宠物用品",
                "猫粮",
                1D,
                "kg",
                39.9D,
                39.9D,
                39.9D,
                0D,
                "paid_amount",
                39.9D,
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

    private long saveRecord(String productName,
                            String sku,
                            String normalizedName,
                            String normalizationRule,
                            String decision,
                            boolean duplicate,
                            String dedupeStatus,
                            Double quantity,
                            String unit,
                            Double totalAmount) {
        return saveRecord(9001L, "jtxw", productName, sku, normalizedName, normalizationRule, decision,
                duplicate, dedupeStatus, quantity, unit, totalAmount);
    }

    private long saveRecord(Long batchId,
                            String owner,
                            String productName,
                            String sku,
                            String normalizedName,
                            String normalizationRule,
                            String decision,
                            boolean duplicate,
                            String dedupeStatus,
                            Double quantity,
                            String unit,
                            Double totalAmount) {
        return purchaseRecordRepository.save(new PurchaseRecord(
                null,
                batchId,
                "2026-06-25 08:00:00",
                "jd",
                owner,
                productName,
                normalizedName,
                sku,
                "宠物用品",
                "猫粮",
                quantity,
                unit,
                totalAmount,
                totalAmount,
                totalAmount,
                0D,
                "paid_amount",
                totalAmount / quantity,
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
}
