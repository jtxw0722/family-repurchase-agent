package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.ImportApplicationService;
import com.jtxw.familyagent.application.NormalizationAnalysisTaskConflictException;
import com.jtxw.familyagent.application.NormalizationAnalysisTaskService;
import com.jtxw.familyagent.application.NormalizationSuggestionService;
import com.jtxw.familyagent.application.PriceAnalysisApplicationService;
import com.jtxw.familyagent.application.RecordPurchaseApplicationService;
import com.jtxw.familyagent.application.ReportApplicationService;
import com.jtxw.familyagent.application.ReviewApplicationService;
import com.jtxw.familyagent.domain.model.ImportResult;
import com.jtxw.familyagent.domain.model.NormalizationAnalysisTask;
import com.jtxw.familyagent.domain.model.NormalizationAnalysisTaskCreateResult;
import com.jtxw.familyagent.domain.model.NormalizationBatchApplyResult;
import com.jtxw.familyagent.domain.model.NormalizationSuggestion;
import com.jtxw.familyagent.domain.model.PriceBaselineResult;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.PriceReportResult;
import com.jtxw.familyagent.domain.model.RecordPurchaseResult;
import com.jtxw.familyagent.domain.model.ReviewApplyResult;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import com.jtxw.familyagent.interfaces.rest.request.AnalyzeNormalizationRequest;
import com.jtxw.familyagent.interfaces.rest.request.ApplyNormalizationReviewRequest;
import com.jtxw.familyagent.interfaces.rest.request.BatchApplyNormalizationRequest;
import com.jtxw.familyagent.interfaces.rest.request.ComparePriceRequest;
import com.jtxw.familyagent.interfaces.rest.request.GenerateReportRequest;
import com.jtxw.familyagent.interfaces.rest.request.GetPriceBaselineRequest;
import com.jtxw.familyagent.interfaces.rest.request.ImportFileRequest;
import com.jtxw.familyagent.interfaces.rest.request.RecordPurchaseRequest;
import com.jtxw.familyagent.interfaces.rest.request.ReviewApplyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 17:58:26
 * @Description: REST Tool API 控制器，暴露导入、比价、报告、复核和商品归一化任务接口。
 */
@Tag(name = "Agent Tool API", description = "家庭复购品价格决策工具接口")
@RestController
@RequestMapping("/api/tools")
public class AgentToolController {
    /**
     * 文件导入应用服务，负责 CSV/Excel 订单导入。
     */
    private final ImportApplicationService importApplicationService;
    /**
     * 价格分析应用服务，负责比价和历史基准线查询。
     */
    private final PriceAnalysisApplicationService priceAnalysisApplicationService;
    /**
     * 手动购买记录录入应用服务，负责结构化记录校验和入库。
     */
    private final RecordPurchaseApplicationService recordPurchaseApplicationService;
    /**
     * 报告应用服务，负责生成本地价格报告。
     */
    private final ReportApplicationService reportApplicationService;
    /**
     * 人工复核应用服务，负责查询和应用复核结果。
     */
    private final ReviewApplicationService reviewApplicationService;
    /**
     * 商品归一化建议服务，负责查询和批量应用 normalization_suggestions。
     */
    private final NormalizationSuggestionService normalizationSuggestionService;
    /**
     * 商品归一化异步分析任务服务，负责创建任务和查询任务进度。
     */
    private final NormalizationAnalysisTaskService normalizationAnalysisTaskService;

    /**
     * 创建 REST Tool API 控制器。
     *
     * @param importApplicationService         文件导入应用服务，不能为空
     * @param priceAnalysisApplicationService  价格分析应用服务，不能为空
     * @param recordPurchaseApplicationService 手动记录录入应用服务，不能为空
     * @param reportApplicationService         报告应用服务，不能为空
     * @param reviewApplicationService         人工复核应用服务，不能为空
     * @param normalizationSuggestionService   商品归一化建议服务，不能为空
     * @param normalizationAnalysisTaskService 商品归一化异步分析任务服务，不能为空
     */
    public AgentToolController(ImportApplicationService importApplicationService,
                               PriceAnalysisApplicationService priceAnalysisApplicationService,
                               RecordPurchaseApplicationService recordPurchaseApplicationService,
                               ReportApplicationService reportApplicationService,
                               ReviewApplicationService reviewApplicationService,
                               NormalizationSuggestionService normalizationSuggestionService,
                               NormalizationAnalysisTaskService normalizationAnalysisTaskService) {
        this.importApplicationService = importApplicationService;
        this.priceAnalysisApplicationService = priceAnalysisApplicationService;
        this.recordPurchaseApplicationService = recordPurchaseApplicationService;
        this.reportApplicationService = reportApplicationService;
        this.reviewApplicationService = reviewApplicationService;
        this.normalizationSuggestionService = normalizationSuggestionService;
        this.normalizationAnalysisTaskService = normalizationAnalysisTaskService;
    }

    /**
     * 导入本地订单文件。
     *
     * <p>该接口只读取用户提供的本地文件路径，不会访问电商平台、不会读取浏览器 Cookie，
     * 也不会上传订单数据。导入过程会写入本地 SQLite，并可能生成待复核记录。</p>
     *
     * @param request 文件导入请求
     * @return 导入结果，包括导入记录数和待复核记录数
     */
    @Operation(summary = "导入订单文件", description = "导入本地 CSV 或 Excel 订单文件，并生成购买记录和待复核记录。")
    @PostMapping("/import-file")
    public ImportResult importFile(@Valid @RequestBody ImportFileRequest request) {
        return importApplicationService.importFile(request.toCommand());
    }

    /**
     * 录入手动或自然语言抽取后的结构化购买记录。
     *
     * <p>Controller 只负责暴露工具型 REST API，业务校验、归一化、单价计算、去重和复核创建
     * 均由 RecordPurchaseApplicationService 完成。</p>
     *
     * @param request 手动购买记录录入请求
     * @return 逐条录入结果
     */
    @Operation(summary = "录入购买记录", description = "录入 Claude 已抽取好的结构化购买记录，并由后端完成归一化、单价计算、去重、入库或复核。")
    @PostMapping("/record-purchase")
    public RecordPurchaseResult recordPurchase(@Valid @RequestBody RecordPurchaseRequest request) {
        return recordPurchaseApplicationService.record(request.toCommand());
    }

    /**
     * 查询商品历史价格基准线。
     *
     * @param request 历史价格基准线查询请求
     * @return 历史价格基准线，包括历史最低价、中位价、平均价、样本数量和证据
     */
    @Operation(summary = "查询历史价格基准线", description = "查询指定复购品的本地历史价格基准线，包括历史最低价、中位价、平均价、样本数量和证据。")
    @PostMapping("/get-price-baseline")
    public PriceBaselineResult getPriceBaseline(@Valid @RequestBody GetPriceBaselineRequest request) {
        return priceAnalysisApplicationService.getPriceBaseline(request.toQuery());
    }

    /**
     * 比较当前商品价格与历史价格，返回价格判断结果。
     *
     * @param request 当前价格比较请求
     * @return 价格判断结果，包括当前单位价格、历史统计值和判断说明
     */
    @Operation(summary = "比较当前价格", description = "比较当前商品单位价格与本地历史价格，返回价格判断结果。")
    @PostMapping("/compare-price")
    public PriceDecisionResult comparePrice(@Valid @RequestBody ComparePriceRequest request) {
        return priceAnalysisApplicationService.comparePrice(request.toQuery());
    }

    /**
     * 生成指定月份的本地 Markdown 复购品价格报告。
     *
     * <p>报告文件会写入本地 reports 目录，统计口径由应用服务和仓储层统一控制。</p>
     *
     * @param request 价格报告请求
     * @return 报告生成结果，包括统计记录数、总金额和报告路径
     */
    @Operation(summary = "生成复购品价格报告", description = "根据指定月份生成 Markdown 价格报告。")
    @PostMapping("/generate-report")
    public PriceReportResult generateReport(@Valid @RequestBody GenerateReportRequest request) {
        return reportApplicationService.generatePriceReport(request.month());
    }

    /**
     * 查询当前待人工复核的异常记录。
     *
     * @return 待复核详情列表，包含复核原因和关联订单信息
     */
    @Operation(summary = "查看待复核记录", description = "查询当前待人工复核的异常订单记录，并返回关联订单的商品、金额、单价和来源文件等信息。")
    @GetMapping("/review-items")
    public List<ReviewItemDetail> listReviewItems() {
        return reviewApplicationService.listPending();
    }

    /**
     * 应用人工复核结果。
     *
     * <p>该接口会更新复核项状态，并同步更新关联购买记录的统计决策。</p>
     *
     * @param id      复核项 ID
     * @param request 复核动作请求
     * @return 复核应用结果
     */
    @Operation(summary = "应用复核结果", description = "将人工复核结果应用到待复核记录，并同步更新关联购买记录的统计决策。")
    @PostMapping("/review-items/{id}/apply")
    public ReviewApplyResult applyReview(@PathVariable long id, @Valid @RequestBody ReviewApplyRequest request) {
        return reviewApplicationService.apply(request.toCommand(id));
    }

    /**
     * 应用商品归一化复核动作。
     *
     * <p>该接口只处理 PRODUCT_NAME_NORMALIZATION_REVIEW 的确认、拒绝和忽略动作，
     * 普通 include/exclude 统计决策复核仍由 /review-items/{id}/apply 处理。</p>
     *
     * @param id      复核项 ID
     * @param request 归一化复核请求
     * @return 复核应用结果
     */
    @Operation(summary = "应用商品归一化复核", description = "统一处理商品归一化确认、拒绝或忽略动作，并沉淀正向/负向别名。")
    @PostMapping("/review-items/{id}/apply-normalization")
    public ReviewApplyResult applyNormalizationReview(@PathVariable long id,
                                                      @RequestBody(required = false) ApplyNormalizationReviewRequest request) {
        ApplyNormalizationReviewRequest body = request == null ? new ApplyNormalizationReviewRequest() : request;
        return reviewApplicationService.applyNormalization(body.toCommand(id));
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

    /**
     * 将业务参数错误转换为 400 响应，避免工具调用方收到不明确的 500 错误。
     *
     * @param exception 参数或状态异常
     * @return 错误信息
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public Map<String, String> handleBadRequest(RuntimeException exception) {
        return Map.of("error", exception.getMessage());
    }

    /**
     * 将归一化分析任务并发冲突转换为 409 响应，明确提示调用方稍后重试。
     *
     * @param exception 归一化分析任务冲突异常
     * @return 错误信息
     */
    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(NormalizationAnalysisTaskConflictException.class)
    public Map<String, String> handleConflict(RuntimeException exception) {
        return Map.of("error", exception.getMessage());
    }
}
