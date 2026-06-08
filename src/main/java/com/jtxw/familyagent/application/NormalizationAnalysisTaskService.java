package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.AnalyzeNormalizationCommand;
import com.jtxw.familyagent.domain.model.NormalizationAnalysisTask;
import com.jtxw.familyagent.domain.model.NormalizationAnalysisTaskCreateResult;
import com.jtxw.familyagent.domain.model.NormalizationAnalyzeResult;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationAnalysisTaskRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * @Author: jtxw
 * @Date: 2026/06/07 15:14:11
 * @Description: 商品归一化异步分析任务应用服务，负责任务创建、启动恢复、后台执行和状态查询。
 */
@Service
public class NormalizationAnalysisTaskService implements ApplicationRunner {
    /**
     * 应用重启时中断遗留 active 任务的错误提示，必须明确告知用户重新创建任务。
     */
    private static final String INTERRUPTED_TASK_MESSAGE = "应用重启，任务已中断，请重新创建分析任务。";

    /**
     * 数据库初始化组件，用于确保任务表和建议表在接口调用前可用。
     */
    private final DatabaseInitializer databaseInitializer;
    /**
     * 归一化分析任务仓储，负责 normalization_analysis_tasks 表的读写。
     */
    private final NormalizationAnalysisTaskRepository taskRepository;
    /**
     * 原有同步归一化建议服务，后台任务复用其分析和写 suggestion 的业务逻辑。
     */
    private final NormalizationSuggestionService suggestionService;
    /**
     * 归一化分析任务执行器，固定为单线程，避免多个 LLM 分析任务并发执行。
     */
    private final ExecutorService executorService;

    /**
     * 创建商品归一化异步任务服务。
     *
     * @param databaseInitializer 数据库初始化组件，不能为空
     * @param taskRepository      任务仓储，不能为空
     * @param suggestionService   同步归一化建议服务，不能为空
     * @param executorService     单线程后台执行器，不能为空
     */
    public NormalizationAnalysisTaskService(DatabaseInitializer databaseInitializer,
                                            NormalizationAnalysisTaskRepository taskRepository,
                                            NormalizationSuggestionService suggestionService,
                                            @Qualifier("normalizationAnalysisExecutor") ExecutorService executorService) {
        this.databaseInitializer = databaseInitializer;
        this.taskRepository = taskRepository;
        this.suggestionService = suggestionService;
        this.executorService = executorService;
    }

    /**
     * 应用启动后恢复上次进程遗留的 active 任务。
     *
     * <p>如果服务在 pending/running 任务执行中被 Jenkins 部署或手动重启，内存中的后台线程已经丢失；
     * 因此启动时必须把这些任务标记为 failed，避免永久阻塞后续新任务创建。</p>
     *
     * @param args Spring Boot 启动参数，本方法不读取具体参数
     */
    @Override
    public void run(ApplicationArguments args) {
        recoverInterruptedActiveTasks();
    }

    /**
     * 将数据库中遗留的 pending/running 任务标记为 failed。
     *
     * @return 被标记为中断失败的任务数量，单位为条；没有遗留任务时返回 0
     */
    public int recoverInterruptedActiveTasks() {
        databaseInitializer.initialize();
        return taskRepository.markInterruptedActiveTasks(INTERRUPTED_TASK_MESSAGE);
    }

    /**
     * 创建商品归一化异步分析任务并提交后台执行。
     *
     * <p>同一时间只允许存在一个 pending/running 任务；如果已有 active 任务，本方法抛出冲突异常，
     * 由 REST 层转换为 409，避免任务堆积和 LLM 并发写库。</p>
     *
     * @param batchId         导入批次 ID，对应 raw_import_batches.id
     * @param limit           最大分析候选数，小于等于 0 时由仓储落库为默认值 100
     * @param forceReanalyze  是否强制重新分析已存在建议的商品
     * @param includeKeywords 包含关键词列表，允许为空或 null
     * @param excludeKeywords 排除关键词列表，允许为空或 null
     * @param onlyFailed      是否只重试已有 failed suggestion 对应的候选商品
     * @return 异步任务创建结果，包含 taskId、batchId、初始状态和提示信息
     * @throws NormalizationAnalysisTaskConflictException 已存在 pending/running 任务时抛出
     */
    public synchronized NormalizationAnalysisTaskCreateResult create(long batchId,
                                                                      int limit,
                                                                      boolean forceReanalyze,
                                                                      List<String> includeKeywords,
                                                                      List<String> excludeKeywords,
                                                                      boolean onlyFailed) {
        return create(new AnalyzeNormalizationCommand(batchId, limit, forceReanalyze,
                includeKeywords, excludeKeywords, onlyFailed));
    }

    /**
     * 创建商品归一化异步分析任务并提交后台执行。
     *
     * <p>同一时间只允许存在一个 pending/running 任务；如果已有 active 任务，本方法抛出冲突异常，
     * 由 REST 层转换为 409，避免任务堆积和 LLM 并发写库。</p>
     *
     * @param command 商品归一化分析命令
     * @return 异步任务创建结果，包含 taskId、batchId、初始状态和提示信息
     * @throws NormalizationAnalysisTaskConflictException 已存在 pending/running 任务时抛出
     */
    public synchronized NormalizationAnalysisTaskCreateResult create(AnalyzeNormalizationCommand command) {
        databaseInitializer.initialize();
        if (taskRepository.existsActiveTask()) {
            throw new NormalizationAnalysisTaskConflictException("已有归一化建议分析任务正在执行，请稍后再试");
        }
        long taskId = taskRepository.create(command.batchId(), command.limit(), command.forceReanalyze(),
                safeKeywords(command.includeKeywords()), safeKeywords(command.excludeKeywords()), command.onlyFailed());
        executorService.submit(() -> runTask(taskId, command.batchId(), command.limit(), command.forceReanalyze(),
                safeKeywords(command.includeKeywords()), safeKeywords(command.excludeKeywords()), command.onlyFailed()));
        return new NormalizationAnalysisTaskCreateResult(taskId, command.batchId(), "pending", "归一化建议分析任务已创建");
    }

    /**
     * 查询商品归一化异步分析任务详情。
     *
     * @param taskId 任务 ID，对应 normalization_analysis_tasks.id
     * @return 任务状态、进度、统计结果和错误信息
     * @throws IllegalArgumentException 指定任务不存在时抛出
     */
    public NormalizationAnalysisTask get(long taskId) {
        databaseInitializer.initialize();
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("归一化建议分析任务不存在：" + taskId));
    }

    /**
     * 执行后台归一化分析任务。
     *
     * <p>任务级异常会将任务标记为 failed；单个 LLM 小批次失败由同步服务写入 failed suggestion，
     * 任务本身仍可 completed 并通过 failedCount 暴露失败数量。</p>
     *
     * @param taskId          任务 ID，对应 normalization_analysis_tasks.id
     * @param batchId         导入批次 ID
     * @param limit           最大分析候选数
     * @param forceReanalyze  是否强制重新分析
     * @param includeKeywords 包含关键词列表，不能为空列表
     * @param excludeKeywords 排除关键词列表，不能为空列表
     * @param onlyFailed      是否只重试 failed suggestion
     */
    private void runTask(long taskId,
                         long batchId,
                         int limit,
                         boolean forceReanalyze,
                         List<String> includeKeywords,
                         List<String> excludeKeywords,
                         boolean onlyFailed) {
        taskRepository.markRunning(taskId);
        try {
            NormalizationAnalyzeResult result = suggestionService.analyzeBatch(batchId, limit, forceReanalyze,
                    includeKeywords, excludeKeywords, onlyFailed,
                    progress -> taskRepository.updateProgress(taskId, progress));
            taskRepository.markCompleted(taskId, result);
        } catch (Exception e) {
            taskRepository.markFailed(taskId, sanitizeError(errorMessage(e)));
        }
    }

    /**
     * 将可空关键词列表转换为空列表，避免后台线程中重复做 null 判断。
     *
     * @param keywords 调用方传入的关键词列表，允许为 null
     * @return 非 null 关键词列表
     */
    private List<String> safeKeywords(List<String> keywords) {
        return keywords == null ? List.of() : keywords;
    }

    /**
     * 提取异常消息，避免异常 message 为空时任务失败原因不可读。
     *
     * @param e 任务级异常，不允许为空
     * @return 异常消息；当 message 为空时返回异常类名
     */
    private String errorMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * 脱敏任务级错误信息，防止 API Key 或 Bearer Token 写入任务表并通过查询接口暴露。
     *
     * @param message 原始错误信息，允许为空
     * @return 脱敏后的错误信息，不包含常见 Bearer Token 或 sk- 前缀 API Key
     */
    private String sanitizeError(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer ***")
                .replaceAll("sk-[A-Za-z0-9_-]+", "sk-***");
    }
}
