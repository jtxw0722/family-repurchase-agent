package com.jtxw.familyagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.application.command.NormalizationRuleSuggestionCommand;
import com.jtxw.familyagent.domain.model.NormalizationLlmTask;
import com.jtxw.familyagent.domain.model.NormalizationLlmTaskCreateResult;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestion;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestionResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.ProductRuleMatcher;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationLlmTaskRepository;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationRuleRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.SqliteProductRuleProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 规则维护建议应用服务测试，覆盖 apply 开关、规则库写入、安全拦截和候选筛选约束
 */
class NormalizationRuleSuggestionServiceTest {
    @Test
    void applyFalseShouldOnlyPersistTaskResult() throws Exception {
        try (Fixture fixture = fixture("rule-suggestion-apply-false.sqlite")) {
            fixture.saveLegacyFallbackRecord(1L, "jtxw", "舒肤佳沐浴露 720ml", "沐浴露");
            when(fixture.advisor().advise(anyList(), anyList()))
                    .thenReturn(new NormalizationRuleSuggestionResult(List.of(bodyWashSuggestion()), List.of()));

            NormalizationLlmTaskCreateResult createResult = fixture.service().create(command(1L, false));
            NormalizationLlmTask task = awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");

            assertThat(task.taskType()).isEqualTo("rule_suggestion");
            assertThat(task.appliedCount()).isZero();
            assertThat(task.skippedCount()).isZero();
            assertThat(fixture.libraryService().listLibraryItems())
                    .extracting(item -> item.ruleCode())
                    .doesNotContain("body_wash");
        }
    }

    @Test
    void applyTrueShouldCreateRuleAndExposeMatcherResult() throws Exception {
        try (Fixture fixture = fixture("rule-suggestion-create-rule.sqlite")) {
            fixture.saveLegacyFallbackRecord(1L, "jtxw", "舒肤佳沐浴露 720ml", "沐浴露");
            when(fixture.advisor().advise(anyList(), anyList()))
                    .thenReturn(new NormalizationRuleSuggestionResult(List.of(bodyWashSuggestion()), List.of()));

            NormalizationLlmTaskCreateResult createResult = fixture.service().create(command(1L, true));
            NormalizationLlmTask task = awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");

            ProductRuleMatcher matcher = fixture.matcher();
            assertThat(task.appliedCount()).isEqualTo(1);
            assertThat(matcher.match("舒肤佳沐浴露 720ml").normalizedName()).isEqualTo("沐浴露");
            assertThat(matcher.match("沐浴露瓶旅行装").matched()).isFalse();
        }
    }

    @Test
    void applyTrueShouldAddIncludeAndExcludeKeyword() throws Exception {
        try (Fixture fixture = fixture("rule-suggestion-add-keyword.sqlite")) {
            fixture.saveLegacyFallbackRecord(1L, "jtxw", "猫砂桶大号", "猫砂桶");
            NormalizationRuleSuggestion includeKeyword = new NormalizationRuleSuggestion(
                    "add_keyword", "cat_litter", null, null, null, null, null,
                    List.of(), List.of(), "膨润土砂", "include", 0.9D,
                    "可泛化猫砂关键词", List.of("膨润土砂"), false, false, List.of());
            NormalizationRuleSuggestion excludeKeyword = new NormalizationRuleSuggestion(
                    "add_keyword", "cat_litter", null, null, null, null, null,
                    List.of(), List.of(), "猫砂桶", "exclude", 0.95D,
                    "耐用品排除关键词", List.of("猫砂桶"), false, false, List.of());
            when(fixture.advisor().advise(anyList(), anyList()))
                    .thenReturn(new NormalizationRuleSuggestionResult(List.of(includeKeyword, excludeKeyword), List.of()));

            NormalizationLlmTaskCreateResult createResult = fixture.service().create(command(1L, true));
            NormalizationLlmTask task = awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");

            ProductRuleMatcher matcher = fixture.matcher();
            assertThat(task.appliedCount()).isEqualTo(2);
            assertThat(matcher.match("膨润土砂 10kg").normalizedName()).isEqualTo("猫砂");
            assertThat(matcher.match("猫砂桶大号").matched()).isFalse();
        }
    }

    @Test
    void dangerousKeywordShouldBeSkipped() throws Exception {
        try (Fixture fixture = fixture("rule-suggestion-dangerous-keyword.sqlite")) {
            fixture.saveLegacyFallbackRecord(1L, "jtxw", "pidan混合豆腐猫砂2.5kg*8包升级除臭官方旗舰店", "猫砂");
            NormalizationRuleSuggestion dangerousKeyword = new NormalizationRuleSuggestion(
                    "add_keyword", "cat_litter", null, null, null, null, null,
                    List.of(), List.of(), "pidan混合豆腐猫砂2.5kg*8包升级除臭官方旗舰店", "include", 0.95D,
                    "模型误把长标题当关键词", List.of("长标题"), false, false, List.of());
            when(fixture.advisor().advise(anyList(), anyList()))
                    .thenReturn(new NormalizationRuleSuggestionResult(List.of(dangerousKeyword), List.of()));

            NormalizationLlmTaskCreateResult createResult = fixture.service().create(command(1L, true));
            NormalizationLlmTask task = awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");

            assertThat(task.appliedCount()).isZero();
            assertThat(task.skippedCount()).isEqualTo(1);
            assertThat(task.warnings()).anySatisfy(warning -> assertThat(warning).contains("危险 keyword"));
        }
    }

    @Test
    void dangerousCreateRuleExcludeKeywordShouldBeSkipped() throws Exception {
        try (Fixture fixture = fixture("rule-suggestion-dangerous-exclude-keyword.sqlite")) {
            fixture.saveLegacyFallbackRecord(1L, "jtxw", "舒肤佳沐浴露 720ml", "沐浴露");
            NormalizationRuleSuggestion dangerousExcludeKeyword = new NormalizationRuleSuggestion(
                    "create_rule", "body_wash", "沐浴露", "个护清洁", "L", "VOLUME", 80,
                    List.of("沐浴露"), List.of("pidan混合豆腐猫砂2.5kg*8包升级除臭官方旗舰店"),
                    null, null, 0.92D, "模型误把长标题当排除关键词", List.of("长标题"),
                    false, false, List.of());
            when(fixture.advisor().advise(anyList(), anyList()))
                    .thenReturn(new NormalizationRuleSuggestionResult(List.of(dangerousExcludeKeyword), List.of()));

            NormalizationLlmTaskCreateResult createResult = fixture.service().create(command(1L, true));
            NormalizationLlmTask task = awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");

            assertThat(task.appliedCount()).isZero();
            assertThat(task.skippedCount()).isEqualTo(1);
            assertThat(task.warnings()).anySatisfy(warning -> assertThat(warning).contains("危险 keyword"));
            assertThat(fixture.libraryService().listLibraryItems())
                    .extracting(item -> item.ruleCode())
                    .doesNotContain("body_wash");
        }
    }

    @Test
    void createShouldRejectImplicitFullScan() throws Exception {
        try (Fixture fixture = fixture("rule-suggestion-implicit-full-scan.sqlite")) {
            assertThatThrownBy(() -> fixture.service().create(new NormalizationRuleSuggestionCommand(
                    null, null, false, "legacy_fallback", 100, false, List.of(), List.of())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("batchId、owner、fullScan=true");
        }
    }

    private NormalizationRuleSuggestionCommand command(Long batchId, boolean apply) {
        return new NormalizationRuleSuggestionCommand(batchId, null, false, "legacy_fallback",
                100, apply, List.of(), List.of());
    }

    private NormalizationRuleSuggestion bodyWashSuggestion() {
        return new NormalizationRuleSuggestion(
                "create_rule", "body_wash", "沐浴露", "个护清洁", "L", "VOLUME", 80,
                List.of("沐浴露", "沐浴乳"), List.of("沐浴露瓶"), null, null, 0.92D,
                "候选样本稳定指向同一复购消耗品类", List.of("舒肤佳沐浴露 720ml"), false, false, List.of());
    }

    private NormalizationLlmTask awaitStatus(NormalizationLlmTaskRepository repository,
                                             long taskId,
                                             String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        NormalizationLlmTask task = null;
        while (System.nanoTime() < deadline) {
            task = repository.findById(taskId).orElseThrow();
            if (status.equals(task.status())) {
                return task;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Task " + taskId + " did not reach status " + status
                + ", last status=" + (task == null ? "missing" : task.status()));
    }

    private Fixture fixture(String dbName) throws Exception {
        Path dir = Path.of("target", "normalization-rule-suggestion-service-test");
        Files.createDirectories(dir);
        Path db = dir.resolve(dbName);
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer databaseInitializer = new DatabaseInitializer(jdbcTemplate);
        databaseInitializer.initialize();
        NormalizationLlmTaskRepository taskRepository =
                new NormalizationLlmTaskRepository(jdbcTemplate, new ObjectMapper());
        PurchaseRecordRepository purchaseRecordRepository = new PurchaseRecordRepository(jdbcTemplate);
        NormalizationRuleRepository ruleRepository = new NormalizationRuleRepository(jdbcTemplate);
        NormalizationLibraryService libraryService = new NormalizationLibraryService(databaseInitializer, ruleRepository);
        NormalizationRuleSuggestionAdvisor advisor = mock(NormalizationRuleSuggestionAdvisor.class);
        NormalizationRuleSuggestionValidator validator =
                new NormalizationRuleSuggestionValidator(new NormalizationKeywordSafetyValidator());
        ExecutorService executorService = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "normalization-rule-suggestion-test");
            thread.setDaemon(true);
            return thread;
        });
        NormalizationRuleSuggestionService service = new NormalizationRuleSuggestionService(
                databaseInitializer, taskRepository, purchaseRecordRepository, libraryService,
                advisor, validator, executorService);
        return new Fixture(taskRepository, purchaseRecordRepository, libraryService,
                ruleRepository, advisor, service, executorService);
    }

    /**
     * @Author: jtxw
     * @Date: 2026/06/15 00:44:27
     * @Description: 规则建议服务测试夹具，集中持有临时 SQLite 组件、Mock Advisor 和后台执行器
     *
     * @param taskRepository           通用 LLM 任务仓储
     * @param purchaseRecordRepository 购买记录仓储
     * @param libraryService           规则库应用服务
     * @param ruleRepository           规则仓储
     * @param advisor                  Mock LLM Advisor
     * @param service                  被测规则维护建议服务
     * @param executorService          测试专用执行器
     */
    private record Fixture(NormalizationLlmTaskRepository taskRepository,
                           PurchaseRecordRepository purchaseRecordRepository,
                           NormalizationLibraryService libraryService,
                           NormalizationRuleRepository ruleRepository,
                           NormalizationRuleSuggestionAdvisor advisor,
                           NormalizationRuleSuggestionService service,
                           ExecutorService executorService) implements AutoCloseable {
        void saveLegacyFallbackRecord(Long batchId, String owner, String productName, String sku) {
            purchaseRecordRepository.save(new PurchaseRecord(null, batchId, "2026-06-15 00:00:00",
                    "JD", owner, productName, "legacy_fallback", sku, "测试分类", "测试子类",
                    1D, "件", 10D, 10D, 10D, null, "paid_amount", 10D, "CNY",
                    "include", false, "unique", "test.csv", null, null, null,
                    "legacy_fallback", null));
        }

        ProductRuleMatcher matcher() {
            return new ProductRuleMatcher(new SqliteProductRuleProvider(ruleRepository));
        }

        @Override
        public void close() {
            executorService.shutdownNow();
        }
    }
}
