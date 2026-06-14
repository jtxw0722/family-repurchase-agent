package com.jtxw.familyagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.domain.model.NormalizationAnalyzeResult;
import com.jtxw.familyagent.domain.model.NormalizationLlmTask;
import com.jtxw.familyagent.domain.model.NormalizationLlmTaskCreateResult;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationLlmTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 商品归一化异步分析任务服务测试，覆盖旧分析入口写入通用 LLM 任务表、状态流转和并发冲突边界
 */
class NormalizationAnalysisTaskServiceTest {
    @Test
    void createShouldPersistGenericLlmTask() throws Exception {
        try (Fixture fixture = fixture("generic-task-initial-status.sqlite")) {
            CountDownLatch release = new CountDownLatch(1);
            when(fixture.suggestionService().analyzeBatch(anyLong(), anyInt(), anyBoolean(), anyList(), anyList(),
                    anyBoolean(), any()))
                    .thenAnswer(invocation -> {
                        release.await(2, TimeUnit.SECONDS);
                        return result(7L, 1, 1, 0);
                    });

            NormalizationLlmTaskCreateResult createResult = fixture.taskService()
                    .create(7L, 10, false, List.of(), List.of(), false);
            NormalizationLlmTask task = fixture.llmTaskRepository().findById(createResult.taskId()).orElseThrow();

            assertThat(createResult.taskType()).isEqualTo("normalization_suggestion_analysis");
            assertThat(task.taskType()).isEqualTo("normalization_suggestion_analysis");
            assertThat(task.status()).isIn("pending", "running");
            release.countDown();
            assertThat(awaitStatus(fixture.llmTaskRepository(), createResult.taskId(), "completed").analyzedCount())
                    .isEqualTo(1);
        }
    }

    @Test
    void backgroundSuccessShouldCompleteWithAnalyzeCounters() throws Exception {
        try (Fixture fixture = fixture("generic-task-success-counters.sqlite")) {
            when(fixture.suggestionService().analyzeBatch(anyLong(), anyInt(), anyBoolean(), anyList(), anyList(),
                    anyBoolean(), any()))
                    .thenReturn(new NormalizationAnalyzeResult(7L, 3, 3, 1, 1, 1, 0, "done"));

            NormalizationLlmTaskCreateResult createResult = fixture.taskService()
                    .create(7L, 10, false, List.of("cat"), List.of(), false);
            NormalizationLlmTask task = awaitStatus(fixture.llmTaskRepository(), createResult.taskId(), "completed");

            assertThat(task.candidateCount()).isEqualTo(3);
            assertThat(task.analyzedCount()).isEqualTo(3);
            assertThat(task.suggestedOperationCount()).isEqualTo(3);
            assertThat(task.skippedCount()).isZero();
            assertThat(task.result()).containsKey("candidateCount");
        }
    }

    @Test
    void taskLevelExceptionShouldMarkFailedAndSanitizeError() throws Exception {
        try (Fixture fixture = fixture("generic-task-level-failure.sqlite")) {
            when(fixture.suggestionService().analyzeBatch(anyLong(), anyInt(), anyBoolean(), anyList(), anyList(),
                    anyBoolean(), any()))
                    .thenThrow(new IllegalStateException("HTTP failed Bearer secret-token sk-live123"));

            NormalizationLlmTaskCreateResult createResult = fixture.taskService()
                    .create(7L, 10, false, List.of(), List.of(), false);
            NormalizationLlmTask task = awaitStatus(fixture.llmTaskRepository(), createResult.taskId(), "failed");

            assertThat(task.errorMessage()).contains("HTTP failed");
            assertThat(task.errorMessage()).doesNotContain("secret-token").doesNotContain("sk-live123");
        }
    }

    @Test
    void activeTaskShouldRejectNewTask() throws Exception {
        try (Fixture fixture = fixture("generic-task-active-conflict.sqlite")) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(fixture.suggestionService().analyzeBatch(anyLong(), anyInt(), anyBoolean(), anyList(), anyList(),
                    anyBoolean(), any()))
                    .thenAnswer(invocation -> {
                        entered.countDown();
                        release.await(2, TimeUnit.SECONDS);
                        return result(7L, 1, 1, 0);
                    });

            NormalizationLlmTaskCreateResult createResult = fixture.taskService()
                    .create(7L, 10, false, List.of(), List.of(), false);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> fixture.taskService().create(8L, 10, false, List.of(), List.of(), false))
                    .isInstanceOf(NormalizationAnalysisTaskConflictException.class);

            release.countDown();
            awaitStatus(fixture.llmTaskRepository(), createResult.taskId(), "completed");
        }
    }

    private NormalizationAnalyzeResult result(long batchId, int candidateCount, int analyzedCount, int failedCount) {
        return new NormalizationAnalyzeResult(batchId, candidateCount, analyzedCount, 0,
                analyzedCount - failedCount, 0, failedCount, "done");
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
        Path dir = Path.of("target", "normalization-analysis-task-service-test");
        Files.createDirectories(dir);
        Path db = dir.resolve(dbName);
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer databaseInitializer = new DatabaseInitializer(jdbcTemplate);
        databaseInitializer.initialize();
        NormalizationLlmTaskRepository llmTaskRepository =
                new NormalizationLlmTaskRepository(jdbcTemplate, new ObjectMapper());
        NormalizationSuggestionService suggestionService = mock(NormalizationSuggestionService.class);
        ExecutorService executorService = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "normalization-analysis-task-test");
            thread.setDaemon(true);
            return thread;
        });
        NormalizationAnalysisTaskService taskService = new NormalizationAnalysisTaskService(
                databaseInitializer, llmTaskRepository, suggestionService, executorService);
        return new Fixture(llmTaskRepository, suggestionService, taskService, executorService);
    }

    /**
     * @Author: jtxw
     * @Date: 2026/06/15 00:44:27
     * @Description: 归一化通用任务服务测试夹具，集中持有临时数据库仓储、Mock 服务和后台执行器
     *
     * @param llmTaskRepository 通用 LLM 任务仓储，连接当前测试临时 SQLite 数据库
     * @param suggestionService Mock 的同步建议服务，用于控制后台任务返回结果
     * @param taskService       被测异步任务服务
     * @param executorService   测试专用单线程执行器，测试结束时关闭
     */
    private record Fixture(NormalizationLlmTaskRepository llmTaskRepository,
                           NormalizationSuggestionService suggestionService,
                           NormalizationAnalysisTaskService taskService,
                           ExecutorService executorService) implements AutoCloseable {
        @Override
        public void close() {
            executorService.shutdownNow();
        }
    }
}
