package com.jtxw.familyagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.domain.model.NormalizationAnalysisTask;
import com.jtxw.familyagent.domain.model.NormalizationAnalysisTaskCreateResult;
import com.jtxw.familyagent.domain.model.NormalizationAnalyzeProgress;
import com.jtxw.familyagent.domain.model.NormalizationAnalyzeResult;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationAnalysisTaskRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @Author: jtxw
 * @Date: 2026/06/07 15:15:18
 * @Description: 商品归一化异步分析任务服务测试，覆盖任务创建、进度查询、失败恢复和并发冲突边界。
 */
class NormalizationAnalysisTaskServiceTest {
    @Test
    void createShouldPersistPendingOrRunningTask() throws Exception {
        try (Fixture fixture = fixture("task-initial-status.sqlite")) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(fixture.suggestionService().analyzeBatch(anyLong(), anyInt(), anyBoolean(), anyList(), anyList(),
                    anyBoolean(), any()))
                    .thenAnswer(invocation -> {
                        entered.countDown();
                        release.await(2, TimeUnit.SECONDS);
                        return result(7L, 1, 1, 0);
                    });

            NormalizationAnalysisTaskCreateResult createResult = fixture.taskService()
                    .create(7L, 10, false, List.of(), List.of(), false);
            NormalizationAnalysisTask task = fixture.taskRepository().findById(createResult.taskId()).orElseThrow();

            assertThat(task.status()).isIn("pending", "running");
            release.countDown();
            awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");
        }
    }

    @Test
    void createShouldReturnTaskIdBeforeAnalysisCompletes() throws Exception {
        try (Fixture fixture = fixture("task-return-quickly.sqlite")) {
            CountDownLatch release = new CountDownLatch(1);
            when(fixture.suggestionService().analyzeBatch(anyLong(), anyInt(), anyBoolean(), anyList(), anyList(),
                    anyBoolean(), any()))
                    .thenAnswer(invocation -> {
                        release.await(2, TimeUnit.SECONDS);
                        return result(7L, 1, 1, 0);
                    });

            long started = System.nanoTime();
            NormalizationAnalysisTaskCreateResult createResult = fixture.taskService()
                    .create(7L, 10, false, List.of("cat"), List.of("try"), true);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(elapsedMs).isLessThan(500);
            assertThat(createResult.taskId()).isNotNull();
            assertThat(createResult.status()).isEqualTo("pending");
            release.countDown();
            awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");
        }
    }

    @Test
    void getShouldReturnTaskStatus() throws Exception {
        try (Fixture fixture = fixture("task-get-status.sqlite")) {
            when(fixture.suggestionService().analyzeBatch(anyLong(), anyInt(), anyBoolean(), anyList(), anyList(),
                    anyBoolean(), any()))
                    .thenReturn(result(7L, 2, 2, 0));

            NormalizationAnalysisTaskCreateResult createResult = fixture.taskService()
                    .create(7L, 10, false, List.of("cat"), List.of(), true);
            awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");

            NormalizationAnalysisTask task = fixture.taskService().get(createResult.taskId());

            assertThat(task.taskId()).isEqualTo(createResult.taskId());
            assertThat(task.batchId()).isEqualTo(7L);
            assertThat(task.status()).isEqualTo("completed");
            assertThat(task.includeKeywords()).containsExactly("cat");
            assertThat(task.onlyFailed()).isTrue();
        }
    }

    @Test
    void backgroundSuccessShouldCompleteWithAnalyzeCounters() throws Exception {
        try (Fixture fixture = fixture("task-success-counters.sqlite")) {
            when(fixture.suggestionService().analyzeBatch(eq(7L), eq(10), eq(false), eq(List.of("cat")),
                    eq(List.of()), eq(false), any()))
                    .thenAnswer(invocation -> {
                        NormalizationAnalyzeProgressListener listener = invocation.getArgument(6);
                        listener.onProgress(new NormalizationAnalyzeProgress(7L, 3, 1, 0, 1, 0, 0, 1, 2));
                        return new NormalizationAnalyzeResult(7L, 3, 3, 1, 1, 1, 0, "done");
                    });

            NormalizationAnalysisTaskCreateResult createResult = fixture.taskService()
                    .create(7L, 10, false, List.of("cat"), List.of(), false);
            NormalizationAnalysisTask task = awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");

            assertThat(task.candidateCount()).isEqualTo(3);
            assertThat(task.analyzedCount()).isEqualTo(3);
            assertThat(task.autoExcludedCount()).isEqualTo(1);
            assertThat(task.pendingBatchApprovalCount()).isEqualTo(1);
            assertThat(task.pendingReviewCount()).isEqualTo(1);
            assertThat(task.failedCount()).isZero();
            assertThat(task.message()).isEqualTo("done");
            verify(fixture.suggestionService()).analyzeBatch(eq(7L), eq(10), eq(false), eq(List.of("cat")),
                    eq(List.of()), eq(false), any());
        }
    }

    @Test
    void llmBatchFailureShouldCompleteTaskWithFailedCount() throws Exception {
        try (Fixture fixture = fixture("task-batch-failure.sqlite")) {
            when(fixture.suggestionService().analyzeBatch(anyLong(), anyInt(), anyBoolean(), anyList(), anyList(),
                    anyBoolean(), any()))
                    .thenReturn(new NormalizationAnalyzeResult(7L, 2, 2, 0, 1, 0, 1,
                            "completed with failed suggestions"));

            NormalizationAnalysisTaskCreateResult createResult = fixture.taskService()
                    .create(7L, 10, false, List.of(), List.of(), false);
            NormalizationAnalysisTask task = awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");

            assertThat(task.failedCount()).isEqualTo(1);
            assertThat(task.errorMessage()).isNull();
        }
    }

    @Test
    void taskLevelExceptionShouldMarkFailedAndSanitizeError() throws Exception {
        try (Fixture fixture = fixture("task-level-failure.sqlite")) {
            when(fixture.suggestionService().analyzeBatch(anyLong(), anyInt(), anyBoolean(), anyList(), anyList(),
                    anyBoolean(), any()))
                    .thenThrow(new IllegalStateException("HTTP failed Bearer secret-token sk-live123"));

            NormalizationAnalysisTaskCreateResult createResult = fixture.taskService()
                    .create(7L, 10, false, List.of(), List.of(), false);
            NormalizationAnalysisTask task = awaitStatus(fixture.taskRepository(), createResult.taskId(), "failed");

            assertThat(task.errorMessage()).contains("HTTP failed");
            assertThat(task.errorMessage()).doesNotContain("secret-token").doesNotContain("sk-live123");
        }
    }

    @Test
    void startupRecoveryShouldFailInterruptedActiveTasksAndAllowNewTask() throws Exception {
        try (Fixture fixture = fixture("task-startup-recovery.sqlite")) {
            long pendingTaskId = fixture.taskRepository()
                    .create(7L, 10, false, List.of(), List.of(), false);
            long runningTaskId = fixture.taskRepository()
                    .create(8L, 10, false, List.of(), List.of(), false);
            fixture.taskRepository().markRunning(runningTaskId);

            int recoveredCount = fixture.taskService().recoverInterruptedActiveTasks();

            NormalizationAnalysisTask pendingTask = fixture.taskRepository().findById(pendingTaskId).orElseThrow();
            NormalizationAnalysisTask runningTask = fixture.taskRepository().findById(runningTaskId).orElseThrow();
            assertThat(recoveredCount).isEqualTo(2);
            assertThat(pendingTask.status()).isEqualTo("failed");
            assertThat(runningTask.status()).isEqualTo("failed");
            assertThat(pendingTask.errorMessage()).contains("应用重启");
            assertThat(runningTask.errorMessage()).contains("请重新创建分析任务");
            assertThat(fixture.taskRepository().existsActiveTask()).isFalse();

            when(fixture.suggestionService().analyzeBatch(anyLong(), anyInt(), anyBoolean(), anyList(), anyList(),
                    anyBoolean(), any()))
                    .thenReturn(result(9L, 1, 1, 0));
            NormalizationAnalysisTaskCreateResult createResult = fixture.taskService()
                    .create(9L, 10, false, List.of(), List.of(), false);

            awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");
        }
    }

    @Test
    void activeTaskShouldRejectNewTask() throws Exception {
        try (Fixture fixture = fixture("task-active-conflict.sqlite")) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(fixture.suggestionService().analyzeBatch(anyLong(), anyInt(), anyBoolean(), anyList(), anyList(),
                    anyBoolean(), any()))
                    .thenAnswer(invocation -> {
                        entered.countDown();
                        release.await(2, TimeUnit.SECONDS);
                        return result(7L, 1, 1, 0);
                    });

            NormalizationAnalysisTaskCreateResult createResult = fixture.taskService()
                    .create(7L, 10, false, List.of(), List.of(), false);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> fixture.taskService().create(8L, 10, false, List.of(), List.of(), false))
                    .isInstanceOf(NormalizationAnalysisTaskConflictException.class);

            release.countDown();
            awaitStatus(fixture.taskRepository(), createResult.taskId(), "completed");
        }
    }

    private NormalizationAnalyzeResult result(long batchId, int candidateCount, int analyzedCount, int failedCount) {
        return new NormalizationAnalyzeResult(batchId, candidateCount, analyzedCount, 0,
                analyzedCount - failedCount, 0, failedCount, "done");
    }

    private NormalizationAnalysisTask awaitStatus(NormalizationAnalysisTaskRepository repository,
                                                  long taskId,
                                                  String status) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        NormalizationAnalysisTask task = null;
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
        NormalizationAnalysisTaskRepository taskRepository =
                new NormalizationAnalysisTaskRepository(jdbcTemplate, new ObjectMapper());
        NormalizationSuggestionService suggestionService = mock(NormalizationSuggestionService.class);
        ExecutorService executorService = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "normalization-analysis-task-test");
            thread.setDaemon(true);
            return thread;
        });
        NormalizationAnalysisTaskService taskService = new NormalizationAnalysisTaskService(
                databaseInitializer, taskRepository, suggestionService, executorService);
        return new Fixture(taskRepository, suggestionService, taskService, executorService);
    }

    /**
     * @Author: jtxw
     * @Date: 2026/06/07 15:10:28
     * @Description: 归一化任务服务测试夹具，集中持有临时数据库仓储、Mock 服务和后台执行器。
     *
     * @param taskRepository   任务仓储，连接当前测试临时 SQLite 数据库
     * @param suggestionService Mock 的同步建议服务，用于控制后台任务返回结果
     * @param taskService      被测异步任务服务
     * @param executorService  测试专用单线程执行器，测试结束时关闭
     */
    private record Fixture(NormalizationAnalysisTaskRepository taskRepository,
                           NormalizationSuggestionService suggestionService,
                           NormalizationAnalysisTaskService taskService,
                           ExecutorService executorService) implements AutoCloseable {
        @Override
        public void close() {
            executorService.shutdownNow();
        }
    }
}
