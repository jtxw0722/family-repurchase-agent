package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.NormalizationAnalysisTaskService;
import com.jtxw.familyagent.application.NormalizationLibraryService;
import com.jtxw.familyagent.application.NormalizationSuggestionService;
import com.jtxw.familyagent.application.AutoExcludedNormalizationSuggestionResult;
import com.jtxw.familyagent.domain.model.NormalizationAnalysisTask;
import com.jtxw.familyagent.domain.model.NormalizationAnalysisTaskCreateResult;
import com.jtxw.familyagent.domain.model.NormalizationBatchApplyResult;
import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.domain.model.NormalizationLibraryOperationResult;
import com.jtxw.familyagent.domain.model.NormalizationSuggestion;
import com.jtxw.familyagent.interfaces.rest.request.AnalyzeNormalizationRequest;
import com.jtxw.familyagent.interfaces.rest.request.BatchApplyNormalizationRequest;
import com.jtxw.familyagent.interfaces.rest.request.NormalizationLibraryOperationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 16:50:03
 * @Description: 商品归一化工具 Controller，暴露归一化异步分析任务、归一化建议管理和名称库查询接口。
 */
@Tag(name = "Agent Tool API", description = "家庭复购品价格决策工具接口")
@RestController
@RequestMapping("/api/tools")
public class NormalizationToolController {
    /**
     * 商品归一化异步分析任务服务，负责创建任务和查询任务进度。
     */
    private final NormalizationAnalysisTaskService normalizationAnalysisTaskService;
    /**
     * 商品归一化建议服务，负责查询和批量应用 normalization_suggestions。
     */
    private final NormalizationSuggestionService normalizationSuggestionService;
    /**
     * 归一化名称库服务，负责查询 SQLite 规则库和动态样本数量。
     */
    private final NormalizationLibraryService normalizationLibraryService;

    /**
     * 创建商品归一化工具 Controller。
     *
     * @param normalizationAnalysisTaskService 商品归一化异步分析任务服务
     * @param normalizationSuggestionService   商品归一化建议服务
     * @param normalizationLibraryService      归一化名称库服务
     */
    public NormalizationToolController(NormalizationAnalysisTaskService normalizationAnalysisTaskService,
                                       NormalizationSuggestionService normalizationSuggestionService,
                                       NormalizationLibraryService normalizationLibraryService) {
        this.normalizationAnalysisTaskService = normalizationAnalysisTaskService;
        this.normalizationSuggestionService = normalizationSuggestionService;
        this.normalizationLibraryService = normalizationLibraryService;
    }

    /**
     * 查询归一化名称库。
     *
     * @return 归一化名称库条目，包含标准单位、单位族、正负关键词和动态样本数量
     */
    @Operation(summary = "查询归一化名称库", description = "查询 SQLite normalization_rules 名称库，返回规则基础信息、关键词和动态历史样本数量。")
    @GetMapping("/normalization-library")
    public List<NormalizationLibraryItem> listNormalizationLibrary() {
        return normalizationLibraryService.listLibraryItems();
    }

    /**
     * 统一处理归一化规则库写操作。
     *
     * @param request 统一写操作请求，使用 action 区分 create_rule、update_rule、disable_rule、add_keyword、disable_keyword
     * @return 统一写操作响应结果
     */
    @Operation(summary = "维护归一化规则库", description = "通过 action 统一处理归一化规则新增、更新、禁用和关键词维护。")
    @PostMapping("/normalization-library")
    public NormalizationLibraryOperationResult operateNormalizationLibrary(
            @RequestBody NormalizationLibraryOperationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("归一化规则库操作请求不能为空");
        }
        return normalizationLibraryService.operate(request.toCommand());
    }

    /**
     * 触发指定导入批次的 LLM 商品归一化建议分析。
     *
     * <p>该接口只分析 legacy_fallback 商品，并将 LLM 输出写入 normalization_suggestions；
     * 高置信 NORMALIZE 不会直接纳入价格基准。</p>
     *
     * @param batchId 导入批次 ID
     * @param request 分析控制参数，允许为空
     * @return 异步分析任务创建结果
     */
    @Operation(summary = "分析批次商品归一化建议", description = "对导入批次内 legacy_fallback 商品创建 LLM Advisor 异步分析任务，并在后台保存建议审计记录。")
    @PostMapping("/import-batches/{batchId}/analyze-normalization")
    public NormalizationAnalysisTaskCreateResult analyzeNormalization(@PathVariable long batchId,
                                                                      @RequestBody(required = false) AnalyzeNormalizationRequest request) {
        AnalyzeNormalizationRequest body = request == null ? new AnalyzeNormalizationRequest() : request;
        return normalizationAnalysisTaskService.create(body.toCommand(batchId));
    }

    /**
     * 查询商品归一化分析任务状态。
     *
     * @param taskId 商品归一化分析任务 ID
     * @return 任务状态、进度和统计结果
     */
    @Operation(summary = "查询商品归一化分析任务", description = "查询 analyze-normalization 异步任务的状态、进度和统计结果。")
    @GetMapping("/normalization-analysis-tasks/{taskId}")
    public NormalizationAnalysisTask getNormalizationAnalysisTask(@PathVariable long taskId) {
        return normalizationAnalysisTaskService.get(taskId);
    }

    /**
     * 查询指定批次的商品归一化建议。
     *
     * @param batchId 导入批次 ID
     * @return 当前批次的建议列表
     */
    @Operation(summary = "查询指定批次的商品归一化建议", description = "查询指定导入批次的 normalization_suggestions 审计记录。")
    @GetMapping("/normalization-suggestions")
    public List<NormalizationSuggestion> listNormalizationSuggestions(@RequestParam long batchId) {
        return normalizationSuggestionService.listByBatchId(batchId);
    }

    /**
     * 查询指定批次中已自动排除且无需人工复核的高置信 EXCLUDE 建议。
     *
     * @param batchId       导入批次 ID，必须大于 0
     * @param minConfidence 最低置信度阈值，允许为空；为空时使用默认值 0.9
     * @return 自动排除建议查询结果，包含总数、类型分布和明细
     */
    @Operation(summary = "查询自动排除的归一化建议", description = "按批次查询 auto_excluded 且无需人工复核的高置信 EXCLUDE suggestions。")
    @GetMapping("/normalization-suggestions/auto-excluded")
    public AutoExcludedNormalizationSuggestionResult listAutoExcludedNormalizationSuggestions(
            @RequestParam long batchId,
            @RequestParam(required = false) Double minConfidence) {
        return normalizationSuggestionService.listAutoExcluded(batchId, minConfidence);
    }

    /**
     * 批量应用高置信 NORMALIZE 商品归一化建议。
     *
     * <p>该接口只写入 product_aliases 和更新 suggestion 状态，不修改历史购买记录 decision。</p>
     *
     * @param request 批量应用请求
     * @return 批量应用结果
     */
    @Operation(summary = "批量应用商品归一化建议", description = "批量确认 pending_batch_approval 的 NORMALIZE suggestions，并写入 product_aliases。")
    @PostMapping("/normalization-suggestions/batch-apply")
    public NormalizationBatchApplyResult applyNormalizationSuggestions(@Valid @RequestBody BatchApplyNormalizationRequest request) {
        return normalizationSuggestionService.batchApply(request.toCommand());
    }
}
