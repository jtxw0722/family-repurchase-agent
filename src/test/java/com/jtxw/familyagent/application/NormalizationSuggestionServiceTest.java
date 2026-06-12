package com.jtxw.familyagent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.*;
import com.jtxw.familyagent.domain.policy.*;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import com.jtxw.familyagent.infrastructure.importer.CsvPurchaseImporter;
import com.jtxw.familyagent.infrastructure.importer.ExcelPurchaseImporter;
import com.jtxw.familyagent.infrastructure.importer.OrderImportMapper;
import com.jtxw.familyagent.infrastructure.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 19:17:57
 * @Description: Normalization LLM Advisor 建议链路集成测试。
 */
class NormalizationSuggestionServiceTest {
    @Test
    void importFileShouldSkipLegacyFallbackReviewWhenModeIsLlmSuggestion() throws Exception {
        Fixture fixture = fixture("import-llm-suggestion.sqlite", properties(true, "llm_suggestion"),
                new StubAdvisor(properties(true, "llm_suggestion"), new ObjectMapper()));
        Path file = csv(fixture.dir(), "orders-llm.csv", "手机壳透明款", "硅胶");

        fixture.importService().importFile(file, null);

        List<ReviewItemDetail> reviewItems = fixture.reviewItemRepository().listPendingDetails();
        assertThat(reviewItems).extracting(ReviewItemDetail::reasonCode)
                .doesNotContain("PRODUCT_NAME_NORMALIZATION_REVIEW");
        assertThat(fixture.jdbcTemplate().queryForList("SELECT decision FROM purchase_records", String.class))
                .containsExactly("exclude");
    }

    @Test
    void importFileShouldKeepImmediateReviewLegacyBehavior() throws Exception {
        Fixture fixture = fixture("import-immediate-review.sqlite", properties(true, "immediate_review"),
                new StubAdvisor(properties(true, "immediate_review"), new ObjectMapper()));
        Path file = csv(fixture.dir(), "orders-immediate.csv", "手机壳透明款", "硅胶");

        fixture.importService().importFile(file, null);

        assertThat(fixture.reviewItemRepository().listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains("PRODUCT_NAME_NORMALIZATION_REVIEW");
    }

    @Test
    void analyzeBatchShouldAutoExcludeHighConfidenceNonRepurchaseAndDurable() throws Exception {
        StubAdvisor advisor = advisor(
                exclude("手机壳透明款", "NON_REPURCHASE"),
                exclude("猫砂盆大号", "DURABLE")
        );
        Fixture fixture = fixture("analyze-auto-exclude.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "手机壳透明款", "猫砂盆大号");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.autoExcludedCount()).isEqualTo(2);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .extracting(NormalizationSuggestion::status)
                .containsExactly("auto_excluded", "auto_excluded");
        assertThat(fixture.reviewItemRepository().listPendingDetails()).isEmpty();
        assertThat(fixture.purchaseRecordRepository().listByBatchId(batchId))
                .extracting(PurchaseRecord::decision)
                .containsOnly("exclude");
    }

    @Test
    void analyzeBatchShouldPutHighConfidenceNormalizeIntoPendingBatchApproval() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫条三文鱼口味", "猫条", "g"));
        Fixture fixture = fixture("analyze-normalize.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingBatchApprovalCount()).isEqualTo(1);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.status()).isEqualTo("pending_batch_approval");
        assertThat(fixture.productAliasRepository().findByAliasKey(suggestion.aliasKey())).isEmpty();
        assertThat(fixture.purchaseRecordRepository().listByBatchId(batchId).get(0).decision()).isEqualTo("exclude");
    }

    @Test
    void analyzeBatchShouldNotWriteDebugDumpWhenDisabled() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫条三文鱼口味", "猫条", "g"));
        NormalizationProperties properties = properties(true, "llm_suggestion");
        Fixture fixture = fixture("debug-disabled.sqlite", properties, advisor);
        Path debugDir = fixture.dir().resolve("debug-disabled");
        deleteTree(debugDir);
        properties.getLlm().setDebugLogDir(debugDir.toString());
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(Files.exists(debugDir)).isFalse();
        assertThat(fixture.suggestionRepository().listByBatchId(batchId).get(0).status())
                .isEqualTo("pending_batch_approval");
    }

    @Test
    void analyzeBatchShouldWriteDebugDumpWithoutBodiesByDefault() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫条三文鱼口味", "猫条", "g"));
        NormalizationProperties properties = properties(true, "llm_suggestion");
        Fixture fixture = fixture("debug-no-body.sqlite", properties, advisor);
        Path debugDir = fixture.dir().resolve("debug-no-body");
        deleteTree(debugDir);
        properties.getLlm().setDebugLogEnabled(true);
        properties.getLlm().setDebugLogDir(debugDir.toString());
        properties.getLlm().setApiKey("secret-test-key");
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        String debugJson = Files.readString(singleDebugFile(debugDir), StandardCharsets.UTF_8);
        assertThat(debugJson).doesNotContain("Authorization").doesNotContain("secret-test-key");
        var root = new ObjectMapper().readTree(debugJson);
        assertThat(root.path("request").path("body").isNull()).isTrue();
        assertThat(root.path("response").path("body").isNull()).isTrue();
        assertThat(root.path("promptChars").asInt()).isGreaterThan(0);
        assertThat(root.path("requestBytes").asInt()).isGreaterThan(0);
    }

    @Test
    void analyzeBatchShouldWriteFullPromptAndResponseWhenEnabled() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫条三文鱼口味", "猫条", "g"));
        advisor.responseBody("{\"choices\":[{\"message\":{\"content\":\"ok-response\"}}]}");
        NormalizationProperties properties = properties(true, "llm_suggestion");
        Fixture fixture = fixture("debug-full-body.sqlite", properties, advisor);
        Path debugDir = fixture.dir().resolve("debug-full-body");
        deleteTree(debugDir);
        properties.getLlm().setDebugLogEnabled(true);
        properties.getLlm().setDebugLogFullPrompt(true);
        properties.getLlm().setDebugLogFullResponse(true);
        properties.getLlm().setDebugLogDir(debugDir.toString());
        properties.getLlm().setApiKey("secret-test-key");
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        String debugJson = Files.readString(singleDebugFile(debugDir), StandardCharsets.UTF_8);
        assertThat(debugJson).doesNotContain("Authorization").doesNotContain("secret-test-key");
        var root = new ObjectMapper().readTree(debugJson);
        assertThat(root.path("request").path("body").asText()).contains("messages");
        assertThat(root.path("response").path("body").asText()).contains("ok-response");
        assertThat(root.path("extractedContent").asText()).contains("ok-response");
    }

    @Test
    void analyzeBatchShouldTruncateDebugResponseBody() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫条三文鱼口味", "猫条", "g"));
        advisor.responseBody("0123456789");
        NormalizationProperties properties = properties(true, "llm_suggestion");
        Fixture fixture = fixture("debug-truncated-response.sqlite", properties, advisor);
        Path debugDir = fixture.dir().resolve("debug-truncated-response");
        deleteTree(debugDir);
        properties.getLlm().setDebugLogEnabled(true);
        properties.getLlm().setDebugLogFullResponse(true);
        properties.getLlm().setDebugLogDir(debugDir.toString());
        properties.getLlm().setDebugMaxResponseChars(5);
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        var root = new ObjectMapper().readTree(Files.readString(singleDebugFile(debugDir), StandardCharsets.UTF_8));
        assertThat(root.path("response").path("body").asText()).isEqualTo("01234");
        assertThat(root.path("response").path("truncated").asBoolean()).isTrue();
    }

    @Test
    void analyzeBatchShouldIgnoreDebugDumpWriteFailure() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫条三文鱼口味", "猫条", "g"));
        NormalizationProperties properties = properties(true, "llm_suggestion");
        Fixture fixture = fixture("debug-write-failure.sqlite", properties, advisor);
        Path debugDirAsFile = fixture.dir().resolve("debug-dir-as-file");
        deleteTree(debugDirAsFile);
        Files.writeString(debugDirAsFile, "not a directory", StandardCharsets.UTF_8);
        properties.getLlm().setDebugLogEnabled(true);
        properties.getLlm().setDebugLogDir(debugDirAsFile.toString());
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingBatchApprovalCount()).isEqualTo(1);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId).get(0).status())
                .isEqualTo("pending_batch_approval");
    }

    @Test
    void analyzeBatchShouldAutoExcludePureCouponOrDeposit() throws Exception {
        StubAdvisor advisor = advisor(
                exclude("0.01元锁定30元", "COUPON_OR_DEPOSIT"),
                exclude("1元预定礼", "COUPON_OR_DEPOSIT")
        );
        Fixture fixture = fixture("analyze-pure-coupon-deposit.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "0.01元锁定30元", "1元预定礼");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.autoExcludedCount()).isEqualTo(2);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.action()).isEqualTo("EXCLUDE");
                    assertThat(suggestion.productType()).isEqualTo("COUPON_OR_DEPOSIT");
                    assertThat(suggestion.status()).isEqualTo("auto_excluded");
                });
    }

    @Test
    void analyzeBatchShouldDowngradeRealProductsWithPresaleOrDepositToReview() throws Exception {
        StubAdvisor advisor = advisor(
                couponDeposit("【双11预售立即付定】尾巴生活彩虹泥主食餐盒一餐一杯猫罐头", "猫主食罐", "罐"),
                couponDeposit("【双11预售】地狱厨房咕噜酱猫零食营养补水增肥酱包", "猫零食", "g")
        );
        Fixture fixture = fixture("analyze-real-product-presale-deposit.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture,
                "【双11预售立即付定】尾巴生活彩虹泥主食餐盒一餐一杯猫罐头",
                "【双11预售】地狱厨房咕噜酱猫零食营养补水增肥酱包");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        List<NormalizationSuggestion> suggestions = fixture.suggestionRepository().listByBatchId(batchId);

        assertThat(result.autoExcludedCount()).isZero();
        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.pendingReviewCount()).isEqualTo(2);
        assertThat(suggestions)
                .extracting(NormalizationSuggestion::action)
                .containsExactly("REVIEW", "REVIEW");
        assertThat(suggestions)
                .extracting(NormalizationSuggestion::productType)
                .containsExactly("REPURCHASE_CONSUMABLE", "REPURCHASE_CONSUMABLE");
        assertThat(suggestions.get(0).suggestedNormalizedName()).isEqualTo("猫主食罐");
        assertThat(suggestions.get(1).suggestedNormalizedName()).isIn("猫条", "猫零食");
        assertThat(suggestions)
                .extracting(NormalizationSuggestion::status)
                .containsExactly("pending_review", "pending_review");
        assertThat(suggestions.get(0).targetUnit()).isEqualTo("罐");
        assertThat(suggestions)
                .allSatisfy(suggestion -> assertThat(suggestion.reason()).contains("真实商品含预售付定需复核"));
    }

    @Test
    void analyzeBatchShouldNotBatchApproveSafeUnitWhenRealProductHasDepositWord() throws Exception {
        StubAdvisor advisor = advisor(normalize("【双11预售】猫条三文鱼口味付定", "猫条", "g"));
        Fixture fixture = fixture("analyze-deposit-safe-unit-review.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "【双11预售】猫条三文鱼口味付定");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.pendingReviewCount()).isEqualTo(1);
        assertThat(suggestion.action()).isEqualTo("REVIEW");
        assertThat(suggestion.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.targetUnit()).isEqualTo("g");
    }

    @Test
    void analyzeBatchShouldCanonicalizeSuggestionBeforeSave() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫罐头主食罐鸡肉味", "主食罐", "罐"));
        Fixture fixture = fixture("analyze-canonicalize-save.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫罐头主食罐鸡肉味");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(fixture.suggestionRepository().listByBatchId(batchId).get(0).suggestedNormalizedName())
                .isEqualTo("猫主食罐");
    }

    @Test
    void analyzeBatchShouldCanonicalizeBroadCatCanByMainCanContextBeforeSave() throws Exception {
        StubAdvisor advisor = advisor(
                normalize("诚实一口全价成猫幼猫用主食餐盒营养湿粮非零食40g*7/盒", "猫罐头", "240g")
        );
        Fixture fixture = fixture("analyze-broad-cat-can-main-context.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "诚实一口全价成猫幼猫用主食餐盒营养湿粮非零食40g*7/盒");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);

        assertThat(suggestion.suggestedNormalizedName()).isEqualTo("猫主食罐");
        assertThat(suggestion.targetUnit()).isEqualTo("g");
        assertThat(suggestion.status()).isEqualTo("pending_batch_approval");
    }

    @Test
    void analyzeBatchShouldCanonicalizeBroadCatCanSnackContextBeforeSave() throws Exception {
        StubAdvisor advisor = advisor(normalize("鸡肉零食罐补水罐尝鲜罐猫咪零食", "猫罐头", "240g"));
        Fixture fixture = fixture("analyze-broad-cat-can-snack-context.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "鸡肉零食罐补水罐尝鲜罐猫咪零食");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);

        assertThat(suggestion.suggestedNormalizedName()).isEqualTo("猫零食");
        assertThat(suggestion.targetUnit()).isEqualTo("g");
    }

    @Test
    void analyzeBatchShouldKeepSafeCatMainCanGramUnit() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫主食罐鸡肉味", "猫主食罐", "g"));
        Fixture fixture = fixture("analyze-cat-main-can-g.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫主食罐鸡肉味");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);

        assertThat(result.pendingBatchApprovalCount()).isEqualTo(1);
        assertThat(suggestion.status()).isEqualTo("pending_batch_approval");
        assertThat(suggestion.targetUnit()).isEqualTo("g");
    }

    @Test
    void analyzeBatchShouldCanonicalizeCatMainCanKgToGram() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫主食罐鸡肉味", "猫主食罐", "kg"));
        Fixture fixture = fixture("analyze-cat-main-can-kg.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫主食罐鸡肉味");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);

        assertThat(result.pendingBatchApprovalCount()).isEqualTo(1);
        assertThat(suggestion.status()).isEqualTo("pending_batch_approval");
        assertThat(suggestion.targetUnit()).isEqualTo("g");
    }

    @Test
    void analyzeBatchShouldStripTargetUnitSpecValuesBeforeSafetyCheck() throws Exception {
        StubAdvisor advisor = advisor(
                normalize("猫主食罐240g规格", "猫主食罐", "240g"),
                normalize("猫主食罐840g规格", "猫主食罐", "840g"),
                normalize("猫主食罐80g多包装", "猫主食罐", "80g*4")
        );
        Fixture fixture = fixture("analyze-target-unit-spec-values.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫主食罐240g规格", "猫主食罐840g规格", "猫主食罐80g多包装");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingBatchApprovalCount()).isEqualTo(3);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.status()).isEqualTo("pending_batch_approval");
                    assertThat(suggestion.targetUnit()).isEqualTo("g");
                });
    }

    @Test
    void analyzeBatchShouldDowngradeUnsafeCatMainCanUnits() throws Exception {
        StubAdvisor advisor = advisor(
                normalize("猫主食罐罐装", "猫主食罐", "罐"),
                normalize("猫主食罐盒装", "猫主食罐", "盒"),
                normalize("猫主食罐包", "猫主食罐", "包"),
                normalize("猫主食罐杯", "猫主食罐", "杯")
        );
        Fixture fixture = fixture("analyze-cat-main-can-unsafe-units.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫主食罐罐装", "猫主食罐盒装", "猫主食罐包", "猫主食罐杯");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingReviewCount()).isEqualTo(4);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.status()).isEqualTo("pending_review");
                    assertThat(suggestion.reason()).contains("单位需复核");
                });
    }

    @Test
    void analyzeBatchShouldHandleTargetUnitSafetyByCategory() throws Exception {
        StubAdvisor advisor = advisor(
                normalize("猫条三文鱼口味", "猫条", "g"),
                normalize("猫条鸡肉味", "猫条", "包"),
                normalize("全价猫粮", "猫粮", "kg"),
                normalize("豆腐猫砂", "猫砂", "kg"),
                normalize("美瞳日抛", "美瞳", "片")
        );
        Fixture fixture = fixture("analyze-target-unit-by-category.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味", "猫条鸡肉味", "全价猫粮", "豆腐猫砂", "美瞳日抛");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingBatchApprovalCount()).isEqualTo(4);
        assertThat(result.pendingReviewCount()).isEqualTo(1);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .extracting(NormalizationSuggestion::status)
                .containsExactly("pending_batch_approval", "pending_review", "pending_batch_approval",
                        "pending_batch_approval", "pending_batch_approval");
    }

    @Test
    void safetyGuardShouldReviewNormalizeWithNonPositiveProductType() throws Exception {
        StubAdvisor advisor = advisor(
                normalizeTyped("未知摆件商品", "未知品类", "件", "UNKNOWN"),
                normalizeTyped("一次性服务", "服务", "件", "NON_REPURCHASE")
        );
        Fixture fixture = fixture("safety-type-conflict.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "未知摆件商品", "一次性服务");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.pendingReviewCount()).isEqualTo(1);
        assertThat(result.autoExcludedCount()).isEqualTo(1);
        List<NormalizationSuggestion> suggestions = fixture.suggestionRepository().listByBatchId(batchId);
        assertThat(suggestions.get(0).action()).isEqualTo("REVIEW");
        assertThat(suggestions.get(0).status()).isEqualTo("pending_review");
        assertThat(suggestions.get(0).reason()).contains("类型动作冲突");
        assertThat(suggestions.get(1).action()).isEqualTo("EXCLUDE");
        assertThat(suggestions.get(1).status()).isEqualTo("auto_excluded");
    }

    @Test
    void safetyGuardShouldReviewReasonCodesThatRequireReview() throws Exception {
        StubAdvisor advisor = advisor(
                normalizeWithReasonCode("手作面包早餐", "面包", "件", "FOOD_REVIEW"),
                normalizeWithReasonCode("持妆粉底液", "粉底液", "ml", "COLOR_COSMETIC_REVIEW"),
                normalizeWithReasonCode("双11预售猫条15g", "猫条", "g", "REAL_PRODUCT_WITH_DEPOSIT")
        );
        Fixture fixture = fixture("safety-review-reason-code.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "手作面包早餐", "持妆粉底液", "双11预售猫条15g");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.pendingReviewCount()).isEqualTo(3);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.action()).isEqualTo("REVIEW");
                    assertThat(suggestion.status()).isEqualTo("pending_review");
                    assertThat(suggestion.reason()).contains("reasonCode 需复核");
                });
    }

    @Test
    void safetyGuardShouldReviewWhenModelExplanationRequiresReview() throws Exception {
        StubAdvisor advisor = advisor(normalizeWithShortReason("美瞳取戴器", "美瞳", "件", "需确认是否为耗材"));
        Fixture fixture = fixture("safety-short-reason-review.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "美瞳取戴器");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.pendingReviewCount()).isEqualTo(1);
        assertThat(suggestion.action()).isEqualTo("REVIEW");
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("模型解释需复核");
    }

    @Test
    void safetyGuardShouldReviewWhenFinalReasonRequiresManualReview() throws Exception {
        StubAdvisor advisor = advisor(normalizeWithReason("美瞳护理液", "护理液", "ml", "最终判断需要人工复核"));
        Fixture fixture = fixture("safety-final-reason-review.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "美瞳护理液");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.pendingReviewCount()).isEqualTo(1);
        assertThat(suggestion.action()).isEqualTo("REVIEW");
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("需要人工复核");
    }

    @Test
    void safetyGuardShouldReviewUnitFamilyValueInTargetUnit() throws Exception {
        StubAdvisor advisor = advisor(normalize("美瞳日抛", "美瞳", "PIECE"));
        Fixture fixture = fixture("safety-invalid-target-unit.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "美瞳日抛");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("单位字段非法");
    }

    @Test
    void safetyGuardShouldReviewOrdinaryFoodAndSamples() throws Exception {
        StubAdvisor advisor = advisor(
                normalize("手作面包早餐", "面包", "件"),
                normalize("中秋月饼礼盒", "月饼", "盒"),
                normalize("【U先尝鲜】营养补水奶酪冻尝鲜60g*4", "猫零食", "g"),
                normalize("【会员试用装】麦富迪主食冻干成幼猫试吃包15g*2", "主食冻干", "g"),
                normalize("1元抢先加赠兰花油", "精华液", "ml")
        );
        Fixture fixture = fixture("safety-food-sample-review.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "手作面包早餐", "中秋月饼礼盒",
                "【U先尝鲜】营养补水奶酪冻尝鲜60g*4",
                "【会员试用装】麦富迪主食冻干成幼猫试吃包15g*2",
                "1元抢先加赠兰花油");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.pendingReviewCount()).isEqualTo(5);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .extracting(NormalizationSuggestion::status)
                .containsOnly("pending_review");
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .extracting(NormalizationSuggestion::reason)
                .anySatisfy(reason -> assertThat(reason).contains("食品需复核"))
                .anySatisfy(reason -> assertThat(reason).contains("试吃/尝鲜/会员试用"));
    }

    @Test
    void safetyGuardShouldKeepPureCouponDepositAutoExcludedWhenSampleWordAppears() throws Exception {
        StubAdvisor advisor = advisor(exclude("1元加赠权益", "COUPON_OR_DEPOSIT"));
        Fixture fixture = fixture("safety-pure-coupon-sample.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "1元加赠权益");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);

        assertThat(result.autoExcludedCount()).isEqualTo(1);
        assertThat(suggestion.action()).isEqualTo("EXCLUDE");
        assertThat(suggestion.productType()).isEqualTo("COUPON_OR_DEPOSIT");
        assertThat(suggestion.status()).isEqualTo("auto_excluded");
    }

    @Test
    void safetyGuardShouldBlockClothingSunscreenAndTravelFromBatchApproval() throws Exception {
        StubAdvisor advisor = advisor(
                normalizeTyped("【狂欢价】木耳边袜子女夏季薄款纯棉中筒袜无骨花边短袜春秋松口中短筒棉袜",
                        "袜子", "件", "DURABLE"),
                normalizeTyped("法式灰色长袖防晒衬衫外套女夏薄款天丝外穿宽松衬衣开衫外搭上衣",
                        "防晒", "件", "DURABLE"),
                normalizeTyped("迪卡侬潜水服男水母服湿衣长袖泳衣冲浪服防晒速干衣",
                        "防晒", "件", "DURABLE"),
                normalizeWithReason("麗枫酒店抚州高铁站华美立家店名人雕塑园豪华大床房",
                        "住宿", "件", "服务类")
        );
        Fixture fixture = fixture("safety-clothing-sunscreen-travel.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture,
                seed("【狂欢价】木耳边袜子女夏季薄款纯棉中筒袜无骨花边短袜春秋松口中短筒棉袜",
                        "【4双装 纯棉无骨抗起球】4双白色;均码"),
                seed("法式灰色长袖防晒衬衫外套女夏薄款天丝外穿宽松衬衣开衫外搭上衣",
                        "灰色【防晒衣/罩衫/空调衫】"),
                seed("迪卡侬潜水服男水母服湿衣长袖泳衣冲浪服防晒速干衣", "默认"),
                seed("麗枫酒店抚州高铁站华美立家店名人雕塑园豪华大床房", "默认"));

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        List<NormalizationSuggestion> suggestions = fixture.suggestionRepository().listByBatchId(batchId);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.autoExcludedCount()).isEqualTo(4);
        assertThat(suggestions)
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.action()).isEqualTo("EXCLUDE");
                    assertThat(suggestion.reviewRequired()).isFalse();
                    assertThat(suggestion.status()).isEqualTo("auto_excluded");
                    assertThat(suggestion.suggestedNormalizedName()).isNotEqualTo("防晒");
                });
        assertThat(suggestions.get(3).productType()).isEqualTo("NON_REPURCHASE");
        assertThat(suggestions.get(3).productType()).isNotEqualTo("COUPON_OR_DEPOSIT");
    }

    @Test
    void safetyGuardShouldKeepSunscreenCreamAsConsumableCandidate() throws Exception {
        StubAdvisor advisor = advisor(normalize("防晒霜 SPF50", "防晒", "ml"));
        Fixture fixture = fixture("safety-sunscreen-cream.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "防晒霜 SPF50");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.action()).isEqualTo("NORMALIZE");
        assertThat(suggestion.suggestedNormalizedName()).isEqualTo("防晒");
        assertThat(suggestion.status()).isEqualTo("pending_batch_approval");
    }

    @Test
    void safetyGuardShouldCleanCoffeeCrossDomainPetReason() throws Exception {
        StubAdvisor advisor = advisor(normalizeWithReason(
                "隅田川速溶黑咖啡粉冻干美式拿铁豆浓缩液无糖0脂官方旗舰店正品",
                "猫主食罐", "g", "命中猫食品消耗品关键词；宠物食品单位需复核"));
        Fixture fixture = fixture("safety-coffee-cross-domain.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture,
                "隅田川速溶黑咖啡粉冻干美式拿铁豆浓缩液无糖0脂官方旗舰店正品");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.action()).isEqualTo("REVIEW");
        assertThat(suggestion.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
        assertThat(suggestion.reviewRequired()).isTrue();
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("咖啡类复购品需人工确认归一化");
        assertThat(suggestion.reason()).doesNotContain("猫食品", "宠物食品");
    }

    @Test
    void safetyGuardShouldReviewCoffeeEvenWhenLlmExcludes() throws Exception {
        StubAdvisor advisor = advisor(new NormalizationAdvisorResult(
                "隅田川速溶黑咖啡粉冻干美式拿铁豆浓缩液无糖0脂官方旗舰店正品",
                "默认", "EXCLUDE", null, null, "NON_REPURCHASE", null, "UNKNOWN",
                0.95D, false, "普通饮品排除", List.of("测试证据"), false));
        Fixture fixture = fixture("safety-coffee-exclude-review.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture,
                "隅田川速溶黑咖啡粉冻干美式拿铁豆浓缩液无糖0脂官方旗舰店正品");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.action()).isEqualTo("REVIEW");
        assertThat(suggestion.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
        assertThat(suggestion.reviewRequired()).isTrue();
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("咖啡类复购品需人工确认归一化");
    }

    @Test
    void safetyGuardShouldReviewCoffeeEvenWhenLlmNormalizesWithHighConfidence() throws Exception {
        StubAdvisor advisor = advisor(normalize(
                "隅田川速溶黑咖啡粉冻干美式拿铁豆浓缩液无糖0脂官方旗舰店正品",
                "咖啡", "g"));
        Fixture fixture = fixture("safety-coffee-normalize-review.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture,
                "隅田川速溶黑咖啡粉冻干美式拿铁豆浓缩液无糖0脂官方旗舰店正品");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.action()).isEqualTo("REVIEW");
        assertThat(suggestion.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
        assertThat(suggestion.reviewRequired()).isTrue();
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("咖啡类复购品需人工确认归一化");
    }

    @Test
    void safetyGuardShouldAllowBrandSeriesSuggestedNameButReviewCoffee() throws Exception {
        StubAdvisor advisor = advisor(normalize(
                "Nestle/雀巢咖啡丝滑拿铁味268ml*15瓶 整箱饮料即饮咖啡熬夜提神",
                "雀巢丝滑拿铁", "ml"));
        Fixture fixture = fixture("safety-safe-brand-series-coffee.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture,
                "Nestle/雀巢咖啡丝滑拿铁味268ml*15瓶 整箱饮料即饮咖啡熬夜提神");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.action()).isEqualTo("REVIEW");
        assertThat(suggestion.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
        assertThat(suggestion.reviewRequired()).isTrue();
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("咖啡类复购品需人工确认归一化");
        assertThat(suggestion.reason()).doesNotContain("建议标准名疑似包含规格/包装形态/促销销售词");
    }

    @Test
    void safetyGuardShouldNotTreatAmericanStyleTShirtAsCoffeeBeverage() throws Exception {
        String productName = "SALT 美式潮牌字母印花基础款短袖t恤女夏季2026新款休闲百搭上衣";
        StubAdvisor advisor = advisor(normalizeWithSku(productName, "酒红;L", "短袖T恤", "件"));
        Fixture fixture = fixture("safety-american-style-shirt-not-coffee.sqlite",
                properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture, seed(productName, "酒红;L"));

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.reason()).doesNotContain("咖啡类复购品需人工确认归一化");
    }

    @Test
    void safetyGuardShouldNotTreatCoffeeColorPantsAsCoffeeBeverage() throws Exception {
        String productName = "羊城故事 美式复古咖色休闲裤女秋季新款小众高腰宽松直筒裤子";
        StubAdvisor advisor = advisor(normalizeWithSku(productName, "咖色（配丝带）;M", "休闲裤", "件"));
        Fixture fixture = fixture("safety-coffee-color-pants-not-coffee.sqlite",
                properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture, seed(productName, "咖色（配丝带）;M"));

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.reason()).doesNotContain("咖啡类复购品需人工确认归一化");
    }

    @Test
    void safetyGuardShouldNotTreatAmericanoContactLensColorAsCoffeeBeverage() throws Exception {
        String productName = "隐形眼镜半年抛2片装LEMONADE美瞳女彩色近视眼镜";
        String sku = "【日抛同款】312 冰美式 Americano（原生感伪素颜神器）;375";
        StubAdvisor advisor = advisor(normalizeWithSku(productName, sku, "美瞳", "片"));
        Fixture fixture = fixture("safety-americano-contact-lens-not-coffee.sqlite",
                properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture, seed(productName, sku));

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.reason()).doesNotContain("咖啡类复购品需人工确认归一化");
    }

    @Test
    void safetyGuardShouldNotTreatLaundryConcentrateAsCoffeeBeverage() throws Exception {
        String productName = "洗衣浓缩液持久留香家庭装";
        StubAdvisor advisor = advisor(normalizeWithSku(productName, "500ml", "洗衣液", "ml"));
        Fixture fixture = fixture("safety-laundry-concentrate-not-coffee.sqlite",
                properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture, seed(productName, "500ml"));

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.reason()).doesNotContain("咖啡类复购品需人工确认归一化");
    }

    @Test
    void safetyGuardShouldNotTreatSerumConcentrateAsCoffeeBeverage() throws Exception {
        String productName = "修护精华浓缩液补水保湿";
        StubAdvisor advisor = advisor(normalizeWithSku(productName, "30ml", "精华液", "ml"));
        Fixture fixture = fixture("safety-serum-concentrate-not-coffee.sqlite",
                properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture, seed(productName, "30ml"));

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.reason()).doesNotContain("咖啡类复购品需人工确认归一化");
    }

    @Test
    void safetyGuardShouldNotTreatCoffeeColorPantsKeywordAsCoffeeBeverage() throws Exception {
        String productName = "美式复古咖啡色休闲裤女秋季新款高腰宽松直筒裤子";
        StubAdvisor advisor = advisor(normalizeWithSku(productName, "咖啡色;M", "休闲裤", "件"));
        Fixture fixture = fixture("safety-coffee-color-keyword-pants-not-coffee.sqlite",
                properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture, seed(productName, "咖啡色;M"));

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.reason()).doesNotContain("咖啡类复购品需人工确认归一化");
    }

    @Test
    void safetyGuardShouldReviewCoffeeWhenWeakWordsHaveStrongContext() throws Exception {
        String productName = "隅田川速溶黑咖啡粉冻干美式拿铁豆浓缩液无糖0脂官方旗舰店正品";
        StubAdvisor advisor = advisor(normalize(productName, "咖啡", "g"));
        Fixture fixture = fixture("safety-coffee-weak-words-with-strong-context.sqlite",
                properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, productName);

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.action()).isEqualTo("REVIEW");
        assertThat(suggestion.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("咖啡类复购品需人工确认归一化");
    }

    @Test
    void safetyGuardShouldKeepAutoExcludedDurableWhenWeakCoffeeWordAppears() throws Exception {
        String productName = "SALT 美式潮牌字母印花基础款短袖t恤";
        StubAdvisor advisor = advisor(normalizeTypedWithSku(productName, "酒红;L", "短袖T恤", "件", "DURABLE"));
        Fixture fixture = fixture("safety-auto-excluded-shirt-not-coffee-review.sqlite",
                properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture, seed(productName, "酒红;L"));

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.action()).isEqualTo("EXCLUDE");
        assertThat(suggestion.productType()).isEqualTo("DURABLE");
        assertThat(suggestion.reviewRequired()).isFalse();
        assertThat(suggestion.status()).isEqualTo("auto_excluded");
        assertThat(suggestion.reason()).doesNotContain("咖啡类复购品需人工确认归一化");
        assertThat(fixture.reviewItemRepository().listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .doesNotContain("PRODUCT_NAME_NORMALIZATION_REVIEW");
    }

    @Test
    void safetyGuardShouldKeepHighConfidenceDurableAutoExcludedWhenSuggestedNameUnsafe() throws Exception {
        StubAdvisor advisor = advisor(normalizeTyped("女装套装", "女装套装", "件", "DURABLE"));
        Fixture fixture = fixture("safety-durable-unsafe-name-auto-exclude.sqlite",
                properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "女装套装");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.action()).isEqualTo("EXCLUDE");
        assertThat(suggestion.productType()).isEqualTo("DURABLE");
        assertThat(suggestion.reviewRequired()).isFalse();
        assertThat(suggestion.status()).isEqualTo("auto_excluded");
        assertThat(suggestion.reason()).contains("高置信耐用品，自动排除");
        assertThat(suggestion.reason()).doesNotContain("建议标准名疑似包含规格/包装形态/促销销售词");
        assertThat(fixture.reviewItemRepository().listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .doesNotContain("PRODUCT_NAME_NORMALIZATION_REVIEW");
    }

    @Test
    void safetyGuardShouldReviewSuggestedNameWithPackagingShape() throws Exception {
        StubAdvisor advisor = advisor(normalize(
                "Nestle/雀巢咖啡丝滑拿铁味268ml*15瓶 整箱饮料即饮咖啡熬夜提神",
                "雀巢丝滑拿铁整箱", "ml"));
        Fixture fixture = fixture("safety-unsafe-name-packaging.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture,
                "Nestle/雀巢咖啡丝滑拿铁味268ml*15瓶 整箱饮料即饮咖啡熬夜提神");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.action()).isEqualTo("REVIEW");
        assertThat(suggestion.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
        assertThat(suggestion.reviewRequired()).isTrue();
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("建议标准名疑似包含规格/包装形态/促销销售词，需人工确认");
    }

    @Test
    void safetyGuardShouldReviewSuggestedNameWithSpecQuantity() throws Exception {
        StubAdvisor advisor = advisor(normalize(
                "Nestle/雀巢咖啡丝滑拿铁味268ml*15瓶 整箱饮料即饮咖啡熬夜提神",
                "雀巢丝滑拿铁268ml15瓶", "ml"));
        Fixture fixture = fixture("safety-unsafe-name-spec.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture,
                "Nestle/雀巢咖啡丝滑拿铁味268ml*15瓶 整箱饮料即饮咖啡熬夜提神");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.action()).isEqualTo("REVIEW");
        assertThat(suggestion.reviewRequired()).isTrue();
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("建议标准名疑似包含规格/包装形态/促销销售词，需人工确认");
    }

    @Test
    void safetyGuardShouldNotMarkSafeSuggestedNamesAsUnsafe() throws Exception {
        StubAdvisor advisor = advisor(
                normalize("豆腐猫砂 6L", "猫砂", "L"),
                normalize("猫条三文鱼口味", "猫条", "g"),
                normalize("猫主食罐鸡肉味", "猫主食罐", "g"),
                normalize("洗衣凝珠", "洗衣凝珠", "颗"),
                normalize("防晒喷雾 SPF50", "防晒喷雾", "ml"),
                normalize("即饮咖啡饮料", "咖啡饮料", "ml"),
                normalize("雀巢丝滑拿铁", "雀巢丝滑拿铁", "ml"),
                normalize("隅田川速溶咖啡", "隅田川速溶咖啡", "g"),
                normalize("维达超韧抽纸", "维达超韧抽纸", "抽")
        );
        Fixture fixture = fixture("safety-safe-suggested-names.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture,
                "豆腐猫砂 6L", "猫条三文鱼口味", "猫主食罐鸡肉味", "洗衣凝珠", "防晒喷雾 SPF50",
                "即饮咖啡饮料", "雀巢丝滑拿铁", "隅田川速溶咖啡", "维达超韧抽纸");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .extracting(NormalizationSuggestion::reason)
                .allSatisfy(reason -> assertThat(reason)
                        .doesNotContain("建议标准名疑似包含规格/包装形态/促销销售词"));
    }

    @Test
    void safetyGuardShouldReviewSuggestedNameWithPromotionWords() throws Exception {
        StubAdvisor advisor = advisor(normalize(
                "Nestle/雀巢咖啡丝滑拿铁味268ml*15瓶 双11优惠装",
                "雀巢丝滑拿铁双11优惠装", "ml"));
        Fixture fixture = fixture("safety-unsafe-name-promotion.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture,
                "Nestle/雀巢咖啡丝滑拿铁味268ml*15瓶 双11优惠装");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.action()).isEqualTo("REVIEW");
        assertThat(suggestion.reviewRequired()).isTrue();
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("建议标准名疑似包含规格/包装形态/促销销售词，需人工确认");
    }

    @Test
    void safetyGuardShouldNotTreatCatFreezeDriedFoodAsCoffeeBeverage() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫冻干主食冻干鸡肉味", "猫冻干", "g"));
        Fixture fixture = fixture("safety-cat-freeze-dried-not-coffee.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫冻干主食冻干鸡肉味");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
        assertThat(suggestion.reason()).doesNotContain("咖啡类复购品需人工确认归一化");
    }

    @Test
    void safetyGuardShouldCleanReasonPunctuationResidue() throws Exception {
        StubAdvisor advisor = advisor(normalizeWithReason(
                "普通商品",
                "猫主食罐",
                "g",
                "猫主食罐消耗品；，不应按 EXCLUDE 或 DURABLE 处理"));
        Fixture fixture = fixture("safety-reason-punctuation-clean.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "普通商品");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);
        assertThat(suggestion.reason()).doesNotContain("；，");
        assertThat(fixture.reviewItemRepository().listPendingDetails())
                .extracting(ReviewItemDetail::reasonMessage)
                .allSatisfy(reasonMessage -> assertThat(reasonMessage).doesNotContain("；，"));
    }

    @Test
    void safetyGuardShouldReviewTrialAndRealDepositButExcludePureRight() throws Exception {
        StubAdvisor advisor = advisor(
                normalize("【天猫U先】帕特猫条猫咪零食牛肉兔肉便携装营养补水湿粮猫条60g",
                        "猫条", "g"),
                normalizeWithReason("0.01元锁定30元", "权益", "件", "模型误判可归一"),
                normalize("【李佳琦直播间爆品节付定金】弗列加特主食罐头95g*18罐囤货装",
                        "猫主食罐", "g")
        );
        Fixture fixture = fixture("safety-trial-right-deposit.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture,
                "【天猫U先】帕特猫条猫咪零食牛肉兔肉便携装营养补水湿粮猫条60g",
                "0.01元锁定30元",
                "【李佳琦直播间爆品节付定金】弗列加特主食罐头95g*18罐囤货装");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        List<NormalizationSuggestion> suggestions = fixture.suggestionRepository().listByBatchId(batchId);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(suggestions.get(0).action()).isEqualTo("REVIEW");
        assertThat(suggestions.get(0).reviewRequired()).isTrue();
        assertThat(suggestions.get(0).status()).isEqualTo("pending_review");
        assertThat(suggestions.get(0).reason()).contains("试吃/尝鲜/会员试用");
        assertThat(suggestions.get(1).action()).isEqualTo("EXCLUDE");
        assertThat(suggestions.get(1).productType()).isEqualTo("COUPON_OR_DEPOSIT");
        assertThat(suggestions.get(1).reviewRequired()).isFalse();
        assertThat(suggestions.get(1).status()).isEqualTo("auto_excluded");
        assertThat(suggestions.get(2).action()).isEqualTo("REVIEW");
        assertThat(suggestions.get(2).reviewRequired()).isTrue();
        assertThat(suggestions.get(2).status()).isEqualTo("pending_review");
        assertThat(suggestions.get(2).reason()).contains("真实商品含预售/付定/定金");
    }

    @Test
    void stateMachineShouldNormalizeActionReviewRequiredAndStatus() throws Exception {
        StubAdvisor advisor = advisor(
                new NormalizationAdvisorResult("模型误报复核", "默认", "REVIEW", null, null,
                        "UNKNOWN", null, "UNKNOWN", 0.7D, false, "模型要求复核", List.of("测试证据"), false),
                new NormalizationAdvisorResult("模型误报排除", "默认", "EXCLUDE", null, null,
                        "NON_REPURCHASE", null, "UNKNOWN", 0.96D, false, "高置信排除", List.of("测试证据"), false),
                new NormalizationAdvisorResult("模型误报归一", "100g", "NORMALIZE", "猫条", null,
                        "REPURCHASE_CONSUMABLE", "g", "WEIGHT", 0.96D, true, "高置信复购消耗品",
                        List.of("测试证据"), false)
        );
        Fixture fixture = fixture("state-machine-normalize.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "模型误报复核", "模型误报排除", "模型误报归一");

        fixture.suggestionService().analyzeBatch(batchId, 100, false);

        List<NormalizationSuggestion> suggestions = fixture.suggestionRepository().listByBatchId(batchId);
        assertThat(suggestions.get(0).action()).isEqualTo("REVIEW");
        assertThat(suggestions.get(0).reviewRequired()).isTrue();
        assertThat(suggestions.get(0).status()).isEqualTo("pending_review");
        assertThat(suggestions.get(1).action()).isEqualTo("EXCLUDE");
        assertThat(suggestions.get(1).reviewRequired()).isFalse();
        assertThat(suggestions.get(1).status()).isEqualTo("auto_excluded");
        assertThat(suggestions.get(2).action()).isEqualTo("REVIEW");
        assertThat(suggestions.get(2).reviewRequired()).isTrue();
        assertThat(suggestions.get(2).status()).isEqualTo("pending_review");
    }

    @Test
    void safetyGuardShouldReviewPetFoodWeightUnitWithoutSpecEvidence() throws Exception {
        StubAdvisor advisor = advisor(normalizeNoSpec("猫主食罐鸡肉味", "猫主食罐", "g"));
        Fixture fixture = fixture("safety-pet-food-without-spec.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫主食罐鸡肉味");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.pendingReviewCount()).isEqualTo(1);
        assertThat(suggestion.status()).isEqualTo("pending_review");
        assertThat(suggestion.reason()).contains("缺少规格证据");
    }

    @Test
    void safetyGuardShouldReviewPetFoodPackageAndCountUnits() throws Exception {
        StubAdvisor advisor = advisor(
                normalize("猫主食罐鸡肉味", "猫主食罐", "罐"),
                normalize("猫咪零食冻干", "猫咪零食", "袋"),
                normalize("猫条三文鱼口味", "猫条", "条"),
                normalize("猫汤包鸡肉味", "猫汤包", "盒")
        );
        Fixture fixture = fixture("safety-pet-food-package-units.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture,
                "猫主食罐鸡肉味", "猫咪零食冻干", "猫条三文鱼口味", "猫汤包鸡肉味");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.pendingReviewCount()).isEqualTo(4);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.action()).isEqualTo("REVIEW");
                    assertThat(suggestion.status()).isEqualTo("pending_review");
                    assertThat(suggestion.reason()).contains("单位需复核");
                });
    }

    @Test
    void safetyGuardShouldReviewAmbiguousSoupCatFoodAsCatMainCan() throws Exception {
        StubAdvisor advisor = advisor(
                normalizeWithSku("猫咪鸡肉汤罐", "80g", "猫主食罐", "g"),
                normalizeWithSku("幼猫补水奶昔", "60g", "猫主食罐", "g"),
                normalizeWithSku("猫咪嘘嘘汤", "40ml", "猫主食罐", "ml")
        );
        Fixture fixture = fixture("safety-cat-soup-main-can-review.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture,
                seed("猫咪鸡肉汤罐", "80g"),
                seed("幼猫补水奶昔", "60g"),
                seed("猫咪嘘嘘汤", "40ml"));

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.pendingReviewCount()).isEqualTo(3);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.action()).isEqualTo("REVIEW");
                    assertThat(suggestion.status()).isEqualTo("pending_review");
                    assertThat(suggestion.reason()).contains("汤类猫食品需复核");
                });
    }

    @Test
    void safetyGuardShouldReviewPackageUnitWhenSpecExists() throws Exception {
        StubAdvisor advisor = advisor(
                normalizeWithSku("补水精华液", "30ml", "精华液", "瓶"),
                normalizeWithSku("洗衣液家庭装", "2kg", "洗衣液", "袋")
        );
        Fixture fixture = fixture("safety-spec-package-unit-review.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture,
                seed("补水精华液", "30ml"),
                seed("洗衣液家庭装", "2kg"));

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingBatchApprovalCount()).isZero();
        assertThat(result.pendingReviewCount()).isEqualTo(2);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.action()).isEqualTo("REVIEW");
                    assertThat(suggestion.status()).isEqualTo("pending_review");
                    assertThat(suggestion.reason()).contains("规格单位不一致需复核");
                });
    }

    @Test
    void safetyGuardShouldAllowPetFoodWeightUnitWithSpecEvidence() throws Exception {
        StubAdvisor advisor = advisor(
                normalizeWithSku("猫主食罐鸡肉味", "510g", "猫主食罐", "g"),
                normalizeWithSku("猫粮鸡肉味", "600g", "猫粮", "g"),
                normalizeWithSku("猫条三文鱼口味", "100g", "猫条", "g")
        );
        Fixture fixture = fixture("safety-pet-food-with-spec.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture,
                seed("猫主食罐鸡肉味", "510g"),
                seed("猫粮鸡肉味", "600g"),
                seed("猫条三文鱼口味", "100g"));

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingBatchApprovalCount()).isEqualTo(3);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .extracting(NormalizationSuggestion::status)
                .containsOnly("pending_batch_approval");
    }

    @Test
    void analyzeBatchShouldUseSingleLlmRequestWhenLimitFitsBatchSize() throws Exception {
        StubAdvisor advisor = advisor(
                exclude("手机壳透明款", "DURABLE"),
                exclude("衣服春款", "DURABLE"),
                exclude("包包通勤", "DURABLE")
        );
        NormalizationProperties properties = properties(true, "llm_suggestion");
        properties.getLlm().setBatchSize(10);
        Fixture fixture = fixture("analyze-batch-size.sqlite", properties, advisor);
        long batchId = batchWithLegacyRecords(fixture, "手机壳透明款", "衣服春款", "包包通勤");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 3, false);

        assertThat(result.analyzedCount()).isEqualTo(3);
        assertThat(advisor.callCount()).isEqualTo(1);
    }

    @Test
    void analyzeBatchShouldFilterCandidatesByIncludeKeyword() throws Exception {
        StubAdvisor advisor = advisor(
                normalize("猫主食罐鸡肉味", "猫主食罐", "罐"),
                normalize("普通商品", "猫主食罐", "罐")
        );
        Fixture fixture = fixture("analyze-include-keyword.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture,
                seed("猫主食罐鸡肉味", "85g"),
                seed("猫条三文鱼口味", "默认"),
                seed("普通商品", "主食罐规格"));

        NormalizationAnalyzeResult result = fixture.suggestionService()
                .analyzeBatch(batchId, 10, false, List.of("主食罐"), List.of(), false);

        assertThat(result.analyzedCount()).isEqualTo(2);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .extracting(NormalizationSuggestion::rawProductName)
                .containsExactly("猫主食罐鸡肉味", "普通商品");
    }

    @Test
    void analyzeBatchShouldMatchIncludeKeywordAgainstProductNameOrSku() throws Exception {
        StubAdvisor advisor = advisor(
                normalize("普通商品", "猫主食罐", "罐"),
                normalize("湿粮餐盒鸡肉味", "猫主食罐", "罐")
        );
        Fixture fixture = fixture("analyze-include-product-or-sku.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture,
                seed("普通商品", "猫罐头规格"),
                seed("湿粮餐盒鸡肉味", "默认"),
                seed("猫条三文鱼口味", "默认"));

        NormalizationAnalyzeResult result = fixture.suggestionService()
                .analyzeBatch(batchId, 10, false, List.of("猫罐头", "湿粮"), List.of(), false);

        assertThat(result.analyzedCount()).isEqualTo(2);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .extracting(NormalizationSuggestion::rawProductName)
                .containsExactly("普通商品", "湿粮餐盒鸡肉味");
    }

    @Test
    void analyzeBatchShouldExcludeCandidatesByKeyword() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫主食罐鸡肉味", "猫主食罐", "罐"));
        Fixture fixture = fixture("analyze-exclude-keyword.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecordsAndSku(fixture,
                seed("猫主食罐鸡肉味", "85g"),
                seed("猫主食罐试吃装", "赠品"));

        NormalizationAnalyzeResult result = fixture.suggestionService()
                .analyzeBatch(batchId, 10, false, List.of("主食罐"), List.of("试吃", "赠品"), false);

        assertThat(result.analyzedCount()).isEqualTo(1);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .extracting(NormalizationSuggestion::rawProductName)
                .containsExactly("猫主食罐鸡肉味");
    }

    @Test
    void analyzeBatchShouldCreateReviewForLowConfidenceReviewAndFailedJson() throws Exception {
        StubAdvisor advisor = advisor(review("美瞳日抛"), failed("奇怪商品"));
        Fixture fixture = fixture("analyze-review-failed.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "美瞳日抛", "奇怪商品");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.pendingReviewCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .extracting(NormalizationSuggestion::status)
                .containsExactly("pending_review", "failed");
        assertThat(fixture.reviewItemRepository().listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .containsExactly("PRODUCT_NAME_NORMALIZATION_REVIEW", "PRODUCT_NAME_NORMALIZATION_REVIEW");
    }

    @Test
    void analyzeBatchShouldKeepLaterBatchWhenOneBatchFails() throws Exception {
        StubAdvisor advisor = advisor();
        advisor.failNextBatch();
        advisor.add(normalize("猫条三文鱼口味", "猫条", "g"));
        NormalizationProperties properties = properties(true, "llm_suggestion");
        properties.getLlm().setBatchSize(2);
        Fixture fixture = fixture("analyze-one-batch-failed.sqlite", properties, advisor);
        long batchId = batchWithLegacyRecords(fixture, "坏 JSON 商品1", "坏 JSON 商品2", "猫条三文鱼口味");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 3, false);

        assertThat(result.failedCount()).isEqualTo(2);
        assertThat(result.pendingBatchApprovalCount()).isEqualTo(1);
        assertThat(advisor.callCount()).isEqualTo(2);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .extracting(NormalizationSuggestion::status)
                .containsExactly("failed", "failed", "pending_batch_approval");
    }

    @Test
    void analyzeBatchShouldRetryFailedSuggestionWhenForceReanalyzeFalse() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫条三文鱼口味", "猫条", "g"));
        Fixture fixture = fixture("analyze-retry-failed.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味");
        String aliasKey = fixture.cleaner().aliasKey("猫条三文鱼口味", "默认");
        fixture.suggestionRepository().save(new NormalizationSuggestion(
                null, batchId, "猫条三文鱼口味", "默认", aliasKey, "REVIEW",
                null, null, "UNKNOWN", null, "UNKNOWN", 0.5D,
                true, "timeout_error：Read timed out", "[]", "test", "test-model", "test-prompt",
                "failed", ClockUtils.nowText(), null));

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.analyzedCount()).isEqualTo(1);
        assertThat(advisor.callCount()).isEqualTo(1);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .hasSize(1)
                .extracting(NormalizationSuggestion::status)
                .containsExactly("pending_batch_approval");
    }

    @Test
    void analyzeBatchShouldOnlyRetryFailedCandidatesWhenOnlyFailedTrue() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫条三文鱼口味", "猫条", "g"));
        Fixture fixture = fixture("analyze-only-failed.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味", "猫主食罐鸡肉味");
        String aliasKey = fixture.cleaner().aliasKey("猫条三文鱼口味", "默认");
        fixture.suggestionRepository().save(new NormalizationSuggestion(
                null, batchId, "猫条三文鱼口味", "默认", aliasKey, "REVIEW",
                null, null, "UNKNOWN", null, "UNKNOWN", 0.5D,
                true, "timeout_error：Read timed out", "[]", "test", "test-model", "test-prompt",
                "failed", ClockUtils.nowText(), null));

        NormalizationAnalyzeResult result = fixture.suggestionService()
                .analyzeBatch(batchId, 10, false, List.of(), List.of(), true);

        assertThat(result.analyzedCount()).isEqualTo(1);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .hasSize(1)
                .extracting(NormalizationSuggestion::rawProductName)
                .containsExactly("猫条三文鱼口味");
    }

    @Test
    void analyzeBatchShouldSkipNonFailedSuggestionsWhenForceReanalyzeFalse() throws Exception {
        StubAdvisor advisor = advisor();
        Fixture fixture = fixture("analyze-skip-non-failed.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "手机壳透明款", "美瞳日抛", "猫条三文鱼口味", "猫主食罐鸡肉味");
        saveExistingSuggestion(fixture, batchId, "手机壳透明款", "auto_excluded");
        saveExistingSuggestion(fixture, batchId, "美瞳日抛", "pending_review");
        saveExistingSuggestion(fixture, batchId, "猫条三文鱼口味", "pending_batch_approval");
        saveExistingSuggestion(fixture, batchId, "猫主食罐鸡肉味", "approved");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.candidateCount()).isZero();
        assertThat(advisor.callCount()).isZero();
    }

    @Test
    void analyzeBatchShouldClassifyTimeoutExceptionAsFailedSuggestion() throws Exception {
        StubAdvisor advisor = advisor();
        advisor.throwNextBatch(new RuntimeException("Read timed out"));
        Fixture fixture = fixture("analyze-timeout-error.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.message()).contains("timeout_error");
        assertThat(fixture.suggestionRepository().listByBatchId(batchId).get(0).reason())
                .contains("timeout_error")
                .contains("Read timed out");
    }

    @Test
    void analyzeBatchShouldReplaceFailedSuggestionAfterRetrySuccess() throws Exception {
        StubAdvisor advisor = advisor();
        advisor.throwNextBatch(new RuntimeException("Read timed out"));
        advisor.add(normalize("猫条三文鱼口味", "猫条", "g"));
        Fixture fixture = fixture("analyze-timeout-retry-success.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味");

        NormalizationAnalyzeResult firstResult = fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationAnalyzeResult secondResult = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(firstResult.failedCount()).isEqualTo(1);
        assertThat(secondResult.pendingBatchApprovalCount()).isEqualTo(1);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .hasSize(1)
                .allSatisfy(suggestion -> {
                    assertThat(suggestion.status()).isEqualTo("pending_batch_approval");
                    assertThat(suggestion.reason()).doesNotContain("timeout_error");
                });
    }

    @Test
    void analyzeBatchShouldReanalyzeMatchedCandidatesWhenForceReanalyzeTrue() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫条三文鱼口味", "猫条", "g"));
        Fixture fixture = fixture("analyze-force-reanalyze-filtered.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味", "手机壳透明款");
        saveExistingSuggestion(fixture, batchId, "猫条三文鱼口味", "pending_batch_approval");

        NormalizationAnalyzeResult result = fixture.suggestionService()
                .analyzeBatch(batchId, 10, true, List.of("猫条"), List.of(), false);

        assertThat(result.analyzedCount()).isEqualTo(1);
        assertThat(advisor.callCount()).isEqualTo(1);
        assertThat(fixture.suggestionRepository().listByBatchId(batchId))
                .hasSize(2)
                .extracting(NormalizationSuggestion::rawProductName)
                .containsExactly("猫条三文鱼口味", "猫条三文鱼口味");
    }

    @Test
    void analyzeBatchShouldReturnClearErrorWhenLlmDisabled() throws Exception {
        Fixture fixture = fixture("analyze-disabled.sqlite", properties(false, "llm_suggestion"),
                new StubAdvisor(properties(false, "llm_suggestion"), new ObjectMapper()));
        long batchId = batchWithLegacyRecords(fixture, "手机壳透明款");

        assertThatThrownBy(() -> fixture.suggestionService().analyzeBatch(batchId, 100, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM normalization advisor 未启用");
    }

    @Test
    void analyzeBatchShouldSkipConfirmedPositiveAndNegativeAlias() throws Exception {
        StubAdvisor advisor = advisor(exclude("手机壳透明款", "NON_REPURCHASE"));
        Fixture fixture = fixture("analyze-skip-alias.sqlite", properties(true, "llm_suggestion"), advisor);
        String positiveAliasKey = fixture.cleaner().aliasKey("手机壳透明款", "默认");
        String negativeAliasKey = fixture.cleaner().aliasKey("猫砂盆大号", "默认");
        fixture.productAliasRepository().upsert("手机壳透明款", positiveAliasKey, "手机壳", "件", "NON_REPURCHASE");
        fixture.productNegativeAliasRepository().upsert("猫砂盆大号", negativeAliasKey, "猫砂", "耐用品");
        long batchId = batchWithLegacyRecords(fixture, "手机壳透明款", "猫砂盆大号");

        NormalizationAnalyzeResult result = fixture.suggestionService().analyzeBatch(batchId, 100, false);

        assertThat(result.candidateCount()).isZero();
        assertThat(advisor.callCount()).isZero();
    }

    @Test
    void batchApplyShouldWriteAliasWithoutChangingPurchaseRecordDecision() throws Exception {
        StubAdvisor advisor = advisor(normalize("猫条三文鱼口味", "猫条", "g"));
        Fixture fixture = fixture("batch-apply.sqlite", properties(true, "llm_suggestion"), advisor);
        long batchId = batchWithLegacyRecords(fixture, "猫条三文鱼口味");
        fixture.suggestionService().analyzeBatch(batchId, 100, false);
        NormalizationSuggestion suggestion = fixture.suggestionRepository().listByBatchId(batchId).get(0);

        NormalizationBatchApplyResult result = fixture.suggestionService()
                .batchApply(batchId, "approve_normalize", 0.9D, "pending_batch_approval");

        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(fixture.productAliasRepository().findByAliasKey(suggestion.aliasKey()))
                .isPresent()
                .get()
                .extracting(ProductAliasRepository.ProductAlias::category)
                .isNull();
        assertThat(fixture.suggestionRepository().listByBatchId(batchId).get(0).status()).isEqualTo("approved");
        assertThat(fixture.purchaseRecordRepository().listByBatchId(batchId).get(0).decision()).isEqualTo("exclude");
    }

    @Test
    void batchApplyShouldCanonicalizeOldSuggestionBeforeWritingAlias() throws Exception {
        Fixture fixture = fixture("batch-apply-canonicalize.sqlite", properties(true, "llm_suggestion"), advisor());
        long batchId = batchWithLegacyRecords(fixture, "猫罐头主食罐鸡肉味");
        String aliasKey = fixture.cleaner().aliasKey("猫罐头主食罐鸡肉味", "默认");
        fixture.suggestionRepository().save(new NormalizationSuggestion(
                null, batchId, "猫罐头主食罐鸡肉味", "默认", aliasKey, "NORMALIZE",
                "主食罐", null, "REPURCHASE_CONSUMABLE", "240g", "WEIGHT", 0.96D,
                true, "旧 suggestion", "[]", "test", "test-model", "test-prompt",
                "pending_batch_approval", ClockUtils.nowText(), null));

        fixture.suggestionService().batchApply(batchId, "approve_normalize", 0.9D, "pending_batch_approval");

        assertThat(fixture.productAliasRepository().findByAliasKey(aliasKey))
                .isPresent()
                .get()
                .extracting(ProductAliasRepository.ProductAlias::normalizedName)
                .isEqualTo("猫主食罐");
        assertThat(fixture.productAliasRepository().findByAliasKey(aliasKey))
                .isPresent()
                .get()
                .extracting(ProductAliasRepository.ProductAlias::targetUnit)
                .isEqualTo("g");
        assertThat(fixture.purchaseRecordRepository().listByBatchId(batchId).get(0).decision()).isEqualTo("exclude");
    }

    @Test
    void batchApplyShouldDowngradeUnsafeOldSuggestionWithoutWritingAlias() throws Exception {
        Fixture fixture = fixture("batch-apply-unsafe-target-unit.sqlite", properties(true, "llm_suggestion"), advisor());
        long batchId = batchWithLegacyRecords(fixture, "猫罐头主食罐鸡肉味");
        String aliasKey = fixture.cleaner().aliasKey("猫罐头主食罐鸡肉味", "默认");
        fixture.suggestionRepository().save(new NormalizationSuggestion(
                null, batchId, "猫罐头主食罐鸡肉味", "默认", aliasKey, "NORMALIZE",
                "主食罐", null, "REPURCHASE_CONSUMABLE", "罐", "COUNT", 0.96D,
                true, "旧 suggestion", "[]", "test", "test-model", "test-prompt",
                "pending_batch_approval", ClockUtils.nowText(), null));

        NormalizationBatchApplyResult result = fixture.suggestionService()
                .batchApply(batchId, "approve_normalize", 0.9D, "pending_batch_approval");

        assertThat(result.appliedCount()).isZero();
        assertThat(fixture.productAliasRepository().findByAliasKey(aliasKey)).isEmpty();
        assertThat(fixture.suggestionRepository().listByBatchId(batchId).get(0).status()).isEqualTo("pending_review");
        assertThat(fixture.suggestionRepository().listByBatchId(batchId).get(0).reason())
                .contains("targetUnit 不适合批量确认");
        assertThat(fixture.purchaseRecordRepository().listByBatchId(batchId).get(0).decision()).isEqualTo("exclude");
    }


    private long batchWithLegacyRecords(Fixture fixture, String... productNames) {
        long batchId = fixture.importBatchRepository().create("test-source");
        for (String productName : productNames) {
            saveLegacyRecord(fixture, batchId, productName, "默认");
        }
        fixture.importBatchRepository().complete(batchId, productNames.length, productNames.length, 0);
        return batchId;
    }

    private long batchWithLegacyRecordsAndSku(Fixture fixture, LegacyRecordSeed... records) {
        long batchId = fixture.importBatchRepository().create("test-source");
        for (LegacyRecordSeed record : records) {
            saveLegacyRecord(fixture, batchId, record.productName(), record.sku());
        }
        fixture.importBatchRepository().complete(batchId, records.length, records.length, 0);
        return batchId;
    }

    private void saveLegacyRecord(Fixture fixture, long batchId, String productName, String sku) {
        fixture.purchaseRecordRepository().save(new PurchaseRecord(
                null, batchId, "2026-06-01 00:00:00", "jd", "jtxw", productName, productName,
                sku, "测试分类", "测试子类", 1D, "件", 10D, 10D, 10D, null,
                "paid_amount", 10D, "CNY", "exclude", false, "unique",
                "test-source", null, null, null, "legacy_fallback", ClockUtils.nowText()
        ));
    }

    private LegacyRecordSeed seed(String productName, String sku) {
        return new LegacyRecordSeed(productName, sku);
    }

    private Path singleDebugFile(Path debugDir) throws Exception {
        try (var files = Files.list(debugDir)) {
            List<Path> debugFiles = files.toList();
            assertThat(debugFiles).hasSize(1);
            return debugFiles.get(0);
        }
    }

    private void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    private Path csv(Path dir, String filename, String productName, String sku) throws Exception {
        Path file = dir.resolve(filename);
        Files.writeString(file, """
                order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
                2026-06-01 00:00:00,jd,jtxw,%s,%s,测试分类,测试子类,1,件,10,CNY
                """.formatted(productName, sku), StandardCharsets.UTF_8);
        return file;
    }

    private StubAdvisor advisor(NormalizationAdvisorResult... results) {
        NormalizationProperties properties = properties(true, "llm_suggestion");
        StubAdvisor advisor = new StubAdvisor(properties, new ObjectMapper());
        for (NormalizationAdvisorResult result : results) {
            advisor.add(result);
        }
        return advisor;
    }

    private NormalizationAdvisorResult exclude(String productName, String productType) {
        return new NormalizationAdvisorResult(productName, "默认", "EXCLUDE", null, null,
                productType, null, "UNKNOWN", 0.95D, false, "高置信排除", List.of("测试证据"), false);
    }

    /**
     * 构造高置信 NORMALIZE 结果，按 targetUnit 自动补充默认规格 SKU。
     *
     * @param productName    原始商品名
     * @param normalizedName LLM 建议标准品类
     * @param targetUnit     LLM 建议目标单位
     * @return 测试用 LLM 归一化结果
     */
    private NormalizationAdvisorResult normalize(String productName, String normalizedName, String targetUnit) {
        return new NormalizationAdvisorResult(productName, defaultSkuFor(targetUnit), "NORMALIZE", normalizedName, null,
                "REPURCHASE_CONSUMABLE", targetUnit, "COUNT", 0.96D, false, "高置信复购消耗品",
                List.of("测试证据"), false);
    }

    /**
     * 构造不带规格证据的高置信 NORMALIZE 结果。
     *
     * @param productName    原始商品名
     * @param normalizedName LLM 建议标准品类
     * @param targetUnit     LLM 建议目标单位
     * @return 测试用 LLM 归一化结果
     */
    private NormalizationAdvisorResult normalizeNoSpec(String productName, String normalizedName, String targetUnit) {
        return new NormalizationAdvisorResult(productName, "默认", "NORMALIZE", normalizedName, null,
                "REPURCHASE_CONSUMABLE", targetUnit, "COUNT", 0.96D, false, "高置信复购消耗品",
                List.of("测试证据"), false);
    }

    /**
     * 构造带指定 SKU 的高置信 NORMALIZE 结果。
     *
     * @param productName    原始商品名
     * @param sku            商品规格或 SKU
     * @param normalizedName LLM 建议标准品类
     * @param targetUnit     LLM 建议目标单位
     * @return 测试用 LLM 归一化结果
     */
    private NormalizationAdvisorResult normalizeWithSku(String productName,
                                                        String sku,
                                                        String normalizedName,
                                                        String targetUnit) {
        return new NormalizationAdvisorResult(productName, sku, "NORMALIZE", normalizedName, null,
                "REPURCHASE_CONSUMABLE", targetUnit, "COUNT", 0.96D, false, "高置信复购消耗品",
                List.of("测试证据"), false);
    }

    /**
     * 构造指定 productType 的 NORMALIZE 结果，用于测试类型和动作冲突。
     *
     * @param productName    原始商品名
     * @param normalizedName LLM 建议标准品类
     * @param targetUnit     LLM 建议目标单位
     * @param productType    LLM 建议商品类型
     * @return 测试用 LLM 归一化结果
     */
    private NormalizationAdvisorResult normalizeTyped(String productName,
                                                      String normalizedName,
                                                      String targetUnit,
                                                      String productType) {
        return new NormalizationAdvisorResult(productName, defaultSkuFor(targetUnit), "NORMALIZE", normalizedName, null,
                productType, targetUnit, "COUNT", 0.96D, false, "高置信归一化",
                List.of("测试证据"), false);
    }

    /**
     * 构造带指定 SKU 和 productType 的 NORMALIZE 结果，用于测试后处理安全规则。
     *
     * @param productName    原始商品名
     * @param sku            商品规格或 SKU
     * @param normalizedName LLM 建议标准品类
     * @param targetUnit     LLM 建议目标单位
     * @param productType    LLM 建议商品类型
     * @return 测试用 LLM 归一化结果
     */
    private NormalizationAdvisorResult normalizeTypedWithSku(String productName,
                                                             String sku,
                                                             String normalizedName,
                                                             String targetUnit,
                                                             String productType) {
        return new NormalizationAdvisorResult(productName, sku, "NORMALIZE", normalizedName, null,
                productType, targetUnit, "COUNT", 0.96D, false, "高置信归一化",
                List.of("测试证据"), false);
    }

    /**
     * 构造带 reasonCode 的 NORMALIZE 结果，用于测试 reasonCode 安全降级。
     *
     * @param productName    原始商品名
     * @param normalizedName LLM 建议标准品类
     * @param targetUnit     LLM 建议目标单位
     * @param reasonCode     LLM 输出原因码
     * @return 测试用 LLM 归一化结果
     */
    private NormalizationAdvisorResult normalizeWithReasonCode(String productName,
                                                               String normalizedName,
                                                               String targetUnit,
                                                               String reasonCode) {
        return new NormalizationAdvisorResult(productName, defaultSkuFor(targetUnit), "NORMALIZE", normalizedName, null,
                "REPURCHASE_CONSUMABLE", targetUnit, "COUNT", 0.96D, false, "高置信复购消耗品",
                List.of("测试证据"), reasonCode, null, false);
    }

    /**
     * 构造带 shortReason 的 NORMALIZE 结果，用于测试解释文本安全降级。
     *
     * @param productName    原始商品名
     * @param normalizedName LLM 建议标准品类
     * @param targetUnit     LLM 建议目标单位
     * @param shortReason    LLM 输出短原因
     * @return 测试用 LLM 归一化结果
     */
    private NormalizationAdvisorResult normalizeWithShortReason(String productName,
                                                                String normalizedName,
                                                                String targetUnit,
                                                                String shortReason) {
        return new NormalizationAdvisorResult(productName, defaultSkuFor(targetUnit), "NORMALIZE", normalizedName, null,
                "REPURCHASE_CONSUMABLE", targetUnit, "COUNT", 0.96D, false, "高置信复购消耗品",
                List.of("测试证据"), null, shortReason, false);
    }

    /**
     * 构造带自定义 reason 的 NORMALIZE 结果，用于测试最终 reason 安全兜底。
     *
     * @param productName    原始商品名
     * @param normalizedName LLM 建议标准品类
     * @param targetUnit     LLM 建议目标单位
     * @param reason         LLM 输出原因说明
     * @return 测试用 LLM 归一化结果
     */
    private NormalizationAdvisorResult normalizeWithReason(String productName,
                                                           String normalizedName,
                                                           String targetUnit,
                                                           String reason) {
        return new NormalizationAdvisorResult(productName, defaultSkuFor(targetUnit), "NORMALIZE", normalizedName, null,
                "REPURCHASE_CONSUMABLE", targetUnit, "COUNT", 0.96D, false, reason,
                List.of("测试证据"), false);
    }

    /**
     * 根据 targetUnit 生成默认 SKU，保证重量和体积单位测试默认带规格证据。
     *
     * @param targetUnit LLM 建议目标单位
     * @return 默认 SKU 文本
     */
    private String defaultSkuFor(String targetUnit) {
        return targetUnit != null && List.of("g", "kg", "ml", "L").contains(targetUnit) ? "100g" : "默认";
    }

    private NormalizationAdvisorResult couponDeposit(String productName, String normalizedName, String targetUnit) {
        return new NormalizationAdvisorResult(productName, "默认", "EXCLUDE", normalizedName, null,
                "COUPON_OR_DEPOSIT", targetUnit, "COUNT", 0.96D, false, "命中预售或付定",
                List.of("测试证据"), false);
    }

    private NormalizationAdvisorResult review(String productName) {
        return new NormalizationAdvisorResult(productName, "默认", "REVIEW", null, null,
                "UNKNOWN", null, "UNKNOWN", 0.6D, true, "低置信需要复核", List.of("测试证据"), false);
    }

    private NormalizationAdvisorResult failed(String productName) {
        return new NormalizationAdvisorResult(productName, "默认", "REVIEW", null, null,
                "UNKNOWN", null, "UNKNOWN", 0.5D, true, "非法 JSON", List.of("非法 JSON"), true);
    }

    private void saveExistingSuggestion(Fixture fixture, long batchId, String productName, String status) {
        String aliasKey = fixture.cleaner().aliasKey(productName, "默认");
        fixture.suggestionRepository().save(new NormalizationSuggestion(
                null, batchId, productName, "默认", aliasKey, "REVIEW",
                null, null, "UNKNOWN", null, "UNKNOWN", 0.6D,
                true, "已有 " + status + " 建议", "[]", "test", "test-model", "test-prompt",
                status, ClockUtils.nowText(), null));
    }

    private NormalizationProperties properties(boolean llmEnabled, String fallbackReviewMode) {
        NormalizationProperties properties = new NormalizationProperties();
        properties.setFallbackReviewMode(fallbackReviewMode);
        properties.getLlm().setEnabled(llmEnabled);
        properties.getLlm().setApiKey("test-key");
        return properties;
    }

    private Fixture fixture(String dbName, NormalizationProperties properties, StubAdvisor advisor) throws Exception {
        Path dir = Path.of("target", "normalization-suggestion-service-test");
        Files.createDirectories(dir);
        Path db = dir.resolve(dbName);
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer databaseInitializer = new DatabaseInitializer(jdbcTemplate);
        databaseInitializer.initialize();
        ObjectMapper objectMapper = new ObjectMapper();
        ProductTitleCleaner cleaner = new ProductTitleCleaner();
        ProductAliasRepository productAliasRepository = new ProductAliasRepository(jdbcTemplate);
        ProductNegativeAliasRepository productNegativeAliasRepository = new ProductNegativeAliasRepository(jdbcTemplate);
        PurchaseRecordRepository purchaseRecordRepository = new PurchaseRecordRepository(jdbcTemplate);
        ReviewItemRepository reviewItemRepository = new ReviewItemRepository(jdbcTemplate);
        ImportBatchRepository importBatchRepository = new ImportBatchRepository(jdbcTemplate);
        NormalizationSuggestionRepository suggestionRepository = new NormalizationSuggestionRepository(jdbcTemplate);
        ProductRuleProperties ruleProperties = new ProductRuleProperties(List.of());
        ProductNameNormalizer delegate = new ProductNameNormalizer(
                new ProductNormalizer(new ProductRuleMatcher(ruleProperties)), List.of());
        LearningProductNameNormalizer learningNormalizer = new LearningProductNameNormalizer(
                cleaner, productAliasRepository, productNegativeAliasRepository, delegate);
        OrderImportMapper orderImportMapper = new OrderImportMapper(new ProductSpecParser());
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
                reviewItemRepository,
                properties
        );
        NormalizationRagContextRetriever ragContextRetriever = new NormalizationRagContextRetriever(
                cleaner, productAliasRepository, productNegativeAliasRepository, ruleProperties);
        NormalizationSuggestionService suggestionService = new NormalizationSuggestionService(
                databaseInitializer,
                purchaseRecordRepository,
                reviewItemRepository,
                cleaner,
                productAliasRepository,
                productNegativeAliasRepository,
                suggestionRepository,
                ragContextRetriever,
                advisor,
                new SuggestedNormalizedNameCanonicalizer(),
                new SuggestedTargetUnitCanonicalizer(),
                properties,
                objectMapper
        );
        return new Fixture(dir, jdbcTemplate, cleaner, productAliasRepository, productNegativeAliasRepository,
                purchaseRecordRepository, reviewItemRepository, importBatchRepository, suggestionRepository,
                importService, suggestionService);
    }

    private static class StubAdvisor implements ProductNormalizationAdvisor {
        /**
         * 按调用顺序返回的模拟 LLM 结果队列。
         */
        private final Queue<NormalizationAdvisorResult> results = new ArrayDeque<>();
        /**
         * 请求指标构建代理，用于复用真实 prompt 渲染和请求体构建逻辑。
         */
        private final NormalizationLlmAdvisor requestMetricsAdvisor;
        /**
         * 批次级失败标记队列，用于模拟单个 LLM batch 失败。
         */
        private final Queue<Boolean> failedBatches = new ArrayDeque<>();
        /**
         * 批次级异常队列，用于模拟 LLM 请求超时或网络错误。
         */
        private final Queue<RuntimeException> batchExceptions = new ArrayDeque<>();
        /**
         * LLM 批量调用次数，用于验证 batch-size 生效。
         */
        private int callCount;
        /**
         * 模拟 LLM 原始响应体，用于 debug dump 测试。
         */
        private String responseBody = "{\"stub\":\"response\"}";

        StubAdvisor(NormalizationProperties normalizationProperties, ObjectMapper objectMapper) {
            this.requestMetricsAdvisor = new NormalizationLlmAdvisor(normalizationProperties, objectMapper);
        }

        void add(NormalizationAdvisorResult result) {
            results.add(result);
        }

        void failNextBatch() {
            failedBatches.add(true);
        }

        void throwNextBatch(RuntimeException exception) {
            batchExceptions.add(exception);
        }

        int callCount() {
            return callCount;
        }

        void responseBody(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        public List<NormalizationAdvisorResult> analyzeBatch(List<NormalizationAdvisorRequest> requests) {
            return analyzeBatchWithObservation(requests).results();
        }

        @Override
        public NormalizationAdviceRequestMetrics requestMetrics(List<NormalizationAdvisorRequest> requests)
                throws JsonProcessingException {
            return requestMetricsAdvisor.requestMetrics(requests);
        }

        @Override
        public NormalizationAdviceBatchAnalysis analyzeBatchWithObservation(List<NormalizationAdvisorRequest> requests) {
            callCount++;
            if (!batchExceptions.isEmpty()) {
                throw batchExceptions.remove();
            }
            NormalizationAdviceRequestMetrics requestMetrics;
            try {
                requestMetrics = requestMetrics(requests);
            } catch (Exception e) {
                requestMetrics = new NormalizationAdviceRequestMetrics(0, 0, null);
            }
            if (!failedBatches.isEmpty() && failedBatches.remove()) {
                List<NormalizationAdvisorResult> failedResults = requests.stream()
                        .map(request -> new NormalizationAdvisorResult(request.productName(), request.sku(), "REVIEW",
                                null, null, "UNKNOWN", null, "UNKNOWN", 0.5D, true,
                                "批量 JSON 解析失败", List.of("批量 JSON 解析失败"), true))
                        .toList();
                return new NormalizationAdviceBatchAnalysis(failedResults, observation(requestMetrics, 0, "json_parse_error",
                        "批量 JSON 解析失败"));
            }
            List<NormalizationAdvisorResult> batchResults = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                batchResults.add(results.remove());
            }
            return new NormalizationAdviceBatchAnalysis(batchResults, observation(requestMetrics, batchResults.size(), null, null));
        }

        private NormalizationAdviceObservation observation(NormalizationAdviceRequestMetrics requestMetrics,
                                                           int parsedItems,
                                                           String errorType,
                                                           String errorMessage) {
            return new NormalizationAdviceObservation(requestMetrics.promptChars(), requestMetrics.requestBytes(),
                    requestMetrics.requestBody(), 1L, 2L, 3L, 4L, 10L, 200, "application/json",
                    responseBody.getBytes(StandardCharsets.UTF_8).length, responseBody.length(), parsedItems,
                    errorType, errorMessage, responseBody, responseBody);
        }
    }

    private record Fixture(Path dir,
                           JdbcTemplate jdbcTemplate,
                           ProductTitleCleaner cleaner,
                           ProductAliasRepository productAliasRepository,
                           ProductNegativeAliasRepository productNegativeAliasRepository,
                           PurchaseRecordRepository purchaseRecordRepository,
                           ReviewItemRepository reviewItemRepository,
                           ImportBatchRepository importBatchRepository,
                           NormalizationSuggestionRepository suggestionRepository,
                           ImportApplicationService importService,
                           NormalizationSuggestionService suggestionService) {
    }

    private record LegacyRecordSeed(String productName, String sku) {
    }
}
