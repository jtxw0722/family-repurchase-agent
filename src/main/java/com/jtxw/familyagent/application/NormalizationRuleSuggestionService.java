package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.AddNormalizationRuleKeywordCommand;
import com.jtxw.familyagent.application.command.CreateNormalizationRuleCommand;
import com.jtxw.familyagent.application.command.NormalizationRuleSuggestionCommand;
import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.domain.model.NormalizationLlmTaskCreateResult;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestion;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestionCandidate;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestionResult;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationLlmTaskRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 规则维护建议应用服务，负责任务创建、候选筛选、LLM 调用、本地校验、按需应用和通用任务状态更新
 */
@Service
public class NormalizationRuleSuggestionService {
    /**
     * 新规则维护建议任务类型，写入 normalization_llm_tasks.task_type。
     */
    private static final String TASK_TYPE_RULE_SUGGESTION = "rule_suggestion";
    /**
     * 默认候选模式，只分析 legacy_fallback 或未命中规则的样本。
     */
    private static final String CANDIDATE_MODE_LEGACY_FALLBACK = "legacy_fallback";
    /**
     * 全量候选模式，必须由用户显式传入。
     */
    private static final String CANDIDATE_MODE_ALL = "all";
    /**
     * 默认最大候选数量，单位为条。
     */
    private static final int DEFAULT_LIMIT = 100;
    /**
     * 最大候选数量上限，避免一次性向 LLM 发送过多历史样本。
     */
    private static final int MAX_LIMIT = 500;
    /**
     * 数据库初始化组件，确保任务表和规则表可用。
     */
    private final DatabaseInitializer databaseInitializer;
    /**
     * 通用 LLM 任务仓储，负责任务状态和结果写入。
     */
    private final NormalizationLlmTaskRepository taskRepository;
    /**
     * 购买记录仓储，负责筛选脱敏候选样本。
     */
    private final PurchaseRecordRepository purchaseRecordRepository;
    /**
     * 规则库服务，负责查询规则摘要和执行真实规则库写操作。
     */
    private final NormalizationLibraryService normalizationLibraryService;
    /**
     * 规则维护建议 Advisor，负责调用 LLM 并返回结构化建议。
     */
    private final NormalizationRuleSuggestionAdvisor advisor;
    /**
     * 本地校验器，负责拦截危险建议和 keyword。
     */
    private final NormalizationRuleSuggestionValidator validator;
    /**
     * 归一化 LLM 通用任务执行器，复用单线程执行能力避免并发写规则库。
     */
    private final ExecutorService executorService;

    /**
     * 创建规则维护建议应用服务。
     *
     * @param databaseInitializer        数据库初始化组件，不能为空
     * @param taskRepository             通用任务仓储，不能为空
     * @param purchaseRecordRepository   购买记录仓储，不能为空
     * @param normalizationLibraryService 规则库服务，不能为空
     * @param advisor                    LLM 规则建议 Advisor，不能为空
     * @param validator                  本地校验器，不能为空
     * @param executorService            单线程归一化任务执行器，不能为空
     */
    public NormalizationRuleSuggestionService(DatabaseInitializer databaseInitializer,
                                              NormalizationLlmTaskRepository taskRepository,
                                              PurchaseRecordRepository purchaseRecordRepository,
                                              NormalizationLibraryService normalizationLibraryService,
                                              NormalizationRuleSuggestionAdvisor advisor,
                                              NormalizationRuleSuggestionValidator validator,
                                              @Qualifier("normalizationLlmTaskExecutor") ExecutorService executorService) {
        this.databaseInitializer = databaseInitializer;
        this.taskRepository = taskRepository;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.normalizationLibraryService = normalizationLibraryService;
        this.advisor = advisor;
        this.validator = validator;
        this.executorService = executorService;
    }

    /**
     * 创建规则维护建议异步任务。
     *
     * @param command 规则维护建议命令，不能为空
     * @return 通用 LLM 任务创建结果
     */
    public synchronized NormalizationLlmTaskCreateResult create(NormalizationRuleSuggestionCommand command) {
        NormalizationRuleSuggestionCommand normalizedCommand = normalizeCommand(command);
        databaseInitializer.initialize();
        if (taskRepository.existsActiveTask()) {
            throw new NormalizationLlmTaskConflictException("已有归一化 LLM 任务正在执行，请稍后再试");
        }
        long taskId = taskRepository.create(TASK_TYPE_RULE_SUGGESTION, normalizedCommand.batchId(),
                blankToNull(normalizedCommand.owner()), normalizedCommand.fullScan(), normalizedCommand.apply(),
                normalizedCommand.candidateMode(), normalizedCommand.limit(), normalizedCommand);
        executorService.submit(() -> runTask(taskId, normalizedCommand));
        return new NormalizationLlmTaskCreateResult(taskId, TASK_TYPE_RULE_SUGGESTION,
                "pending", "normalization rule suggestion task created");
    }

    private void runTask(long taskId, NormalizationRuleSuggestionCommand command) {
        taskRepository.markRunning(taskId);
        try {
            List<NormalizationRuleSuggestionCandidate> candidates = purchaseRecordRepository.listRuleSuggestionCandidates(
                    command.batchId(), blankToNull(command.owner()), command.fullScan(), command.candidateMode(),
                    command.includeKeywords(), command.excludeKeywords(), command.limit());
            taskRepository.updateProgress(taskId, candidates.size(), 0);
            List<NormalizationLibraryItem> libraryItems = normalizationLibraryService.listLibraryItems();
            NormalizationRuleSuggestionResult llmResult = advisor.advise(candidates, libraryItems);
            AppliedResult appliedResult = validateAndApply(llmResult, libraryItems, command.apply());
            taskRepository.markCompleted(taskId, candidates.size(), candidates.size(),
                    appliedResult.suggestions().size(), appliedResult.appliedCount(), appliedResult.skippedCount(),
                    new NormalizationRuleSuggestionResult(appliedResult.suggestions(), appliedResult.warnings()),
                    appliedResult.warnings());
        } catch (Exception e) {
            taskRepository.markFailed(taskId, sanitizeError(errorMessage(e)));
        }
    }

    private AppliedResult validateAndApply(NormalizationRuleSuggestionResult llmResult,
                                           List<NormalizationLibraryItem> libraryItems,
                                           boolean apply) {
        List<String> taskWarnings = new ArrayList<>(llmResult.warnings());
        List<NormalizationRuleSuggestion> results = new ArrayList<>();
        int appliedCount = 0;
        int skippedCount = 0;
        for (NormalizationRuleSuggestion suggestion : llmResult.suggestions()) {
            List<String> warnings = new ArrayList<>(validator.validate(suggestion, libraryItems));
            if (!warnings.isEmpty()) {
                skippedCount++;
                taskWarnings.addAll(warnings);
                results.add(suggestion.withExecution(false, true, warnings));
                continue;
            }
            if (!apply) {
                results.add(suggestion.withExecution(false, false, List.of()));
                continue;
            }
            try {
                applySuggestion(suggestion);
                appliedCount++;
                results.add(suggestion.withExecution(true, false, List.of()));
            } catch (Exception e) {
                skippedCount++;
                String warning = "建议应用失败：" + sanitizeError(errorMessage(e));
                taskWarnings.add(warning);
                results.add(suggestion.withExecution(false, true, List.of(warning)));
            }
        }
        return new AppliedResult(results, appliedCount, skippedCount, taskWarnings);
    }

    private void applySuggestion(NormalizationRuleSuggestion suggestion) {
        String operation = suggestion.operation().toLowerCase(Locale.ROOT);
        if ("create_rule".equals(operation)) {
            normalizationLibraryService.createRule(new CreateNormalizationRuleCommand(
                    suggestion.ruleCode(), suggestion.normalizedName(), suggestion.category(),
                    suggestion.standardUnit(), suggestion.unitFamily(), priorityOrDefault(suggestion.priority()),
                    suggestion.keywords(), suggestion.excludeKeywords()));
            return;
        }
        if ("add_keyword".equals(operation)) {
            normalizationLibraryService.addKeyword(new AddNormalizationRuleKeywordCommand(
                    suggestion.ruleCode(), suggestion.keyword(), suggestion.matchType(), priorityOrDefault(suggestion.priority())));
            return;
        }
        throw new IllegalArgumentException("不支持的规则维护建议操作：" + suggestion.operation());
    }

    private NormalizationRuleSuggestionCommand normalizeCommand(NormalizationRuleSuggestionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("规则维护建议命令不能为空");
        }
        boolean hasBatchId = command.batchId() != null;
        boolean hasOwner = command.owner() != null && !command.owner().isBlank();
        if (!hasBatchId && !hasOwner && !command.fullScan()) {
            throw new IllegalArgumentException("batchId、owner、fullScan=true 至少满足一个");
        }
        String candidateMode = normalizeCandidateMode(command.candidateMode());
        int limit = normalizeLimit(command.limit());
        return new NormalizationRuleSuggestionCommand(command.batchId(), blankToNull(command.owner()),
                command.fullScan(), candidateMode, limit, command.apply(),
                safeKeywords(command.includeKeywords()), safeKeywords(command.excludeKeywords()));
    }

    private String normalizeCandidateMode(String candidateMode) {
        if (candidateMode == null || candidateMode.isBlank()) {
            return CANDIDATE_MODE_LEGACY_FALLBACK;
        }
        String value = candidateMode.trim().toLowerCase(Locale.ROOT);
        if (!CANDIDATE_MODE_LEGACY_FALLBACK.equals(value) && !CANDIDATE_MODE_ALL.equals(value)) {
            throw new IllegalArgumentException("candidateMode 只支持 legacy_fallback 或 all");
        }
        return value;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            return MAX_LIMIT;
        }
        return limit;
    }

    private int priorityOrDefault(Integer priority) {
        return priority == null ? 100 : priority;
    }

    private List<String> safeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        return keywords.stream()
                .filter(keyword -> keyword != null && !keyword.isBlank())
                .map(String::trim)
                .toList();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String sanitizeError(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer ***")
                .replaceAll("sk-[A-Za-z0-9_\\-]+", "sk-***");
    }

    /**
     * 已校验和应用后的建议执行结果。
     *
     * @param suggestions  结果建议列表，包含应用状态
     * @param appliedCount 已应用数量
     * @param skippedCount 已跳过数量
     * @param warnings     任务级警告列表
     */
    private record AppliedResult(List<NormalizationRuleSuggestion> suggestions,
                                 int appliedCount,
                                 int skippedCount,
                                 List<String> warnings) {
    }
}
