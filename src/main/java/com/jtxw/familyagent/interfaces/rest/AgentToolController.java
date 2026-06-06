package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.ImportApplicationService;
import com.jtxw.familyagent.application.PriceAnalysisApplicationService;
import com.jtxw.familyagent.application.RecordPurchaseApplicationService;
import com.jtxw.familyagent.application.ReportApplicationService;
import com.jtxw.familyagent.application.ReviewApplicationService;
import com.jtxw.familyagent.application.NormalizationSuggestionService;
import com.jtxw.familyagent.domain.model.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 17:58:26
 * @Description: REST Tool API 控制器，暴露导入、比价、报告和复核查询接口。
 */
@Tag(name = "Agent Tool API", description = "家庭复购品价格决策工具接口")
@RestController
@RequestMapping("/api/tools")
public class AgentToolController {
    private final ImportApplicationService importApplicationService;
    private final PriceAnalysisApplicationService priceAnalysisApplicationService;
    private final RecordPurchaseApplicationService recordPurchaseApplicationService;
    private final ReportApplicationService reportApplicationService;
    private final ReviewApplicationService reviewApplicationService;
    private final NormalizationSuggestionService normalizationSuggestionService;

    public AgentToolController(ImportApplicationService importApplicationService,
                               PriceAnalysisApplicationService priceAnalysisApplicationService,
                               RecordPurchaseApplicationService recordPurchaseApplicationService,
                               ReportApplicationService reportApplicationService,
                               ReviewApplicationService reviewApplicationService,
                               NormalizationSuggestionService normalizationSuggestionService) {
        this.importApplicationService = importApplicationService;
        this.priceAnalysisApplicationService = priceAnalysisApplicationService;
        this.recordPurchaseApplicationService = recordPurchaseApplicationService;
        this.reportApplicationService = reportApplicationService;
        this.reviewApplicationService = reviewApplicationService;
        this.normalizationSuggestionService = normalizationSuggestionService;
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
        return importApplicationService.importCsv(Path.of(request.filePath()), request.owner());
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
        return recordPurchaseApplicationService.record(request);
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
        return priceAnalysisApplicationService.getPriceBaseline(request.productName(), request.unit());
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
        return priceAnalysisApplicationService.comparePrice(request.productName(), request.price(), request.quantity(), request.unit());
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
        return reviewApplicationService.apply(id, request.action(), request.note());
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
        return reviewApplicationService.applyNormalization(id, body.action(), body.normalizedName(),
                body.targetUnit(), body.includeInBaseline(), body.rejectedNormalizedName(), body.note());
    }

    /**
     * 触发指定导入批次的 LLM 商品归一化建议分析。
     *
     * <p>该接口只分析 legacy_fallback 商品，并将 LLM 输出写入 normalization_suggestions；
     * 高置信 NORMALIZE 不会直接纳入价格基准。</p>
     *
     * @param batchId 导入批次 ID
     * @param request 分析控制参数，允许为空
     * @return 批次分析结果
     */
    @Operation(summary = "分析批次商品归一化建议", description = "对导入批次内 legacy_fallback 商品触发 LLM Advisor 分析，并保存建议审计记录。")
    @PostMapping("/import-batches/{batchId}/analyze-normalization")
    public NormalizationAnalyzeResult analyzeNormalization(@PathVariable long batchId,
                                                           @RequestBody(required = false) AnalyzeNormalizationRequest request) {
        AnalyzeNormalizationRequest body = request == null ? new AnalyzeNormalizationRequest() : request;
        return normalizationSuggestionService.analyzeBatch(batchId, body.limit(), body.forceReanalyze(),
                body.includeKeywords(), body.excludeKeywords(), body.onlyFailed());
    }

    /**
     * 查询指定批次的商品归一化建议。
     *
     * @param batchId 导入批次 ID
     * @return 当前批次的建议列表
     */
    @Operation(summary = "查询商品归一化建议", description = "查询指定导入批次的 normalization_suggestions 审计记录。")
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
        return normalizationSuggestionService.batchApply(request.batchId(), request.action(),
                request.minConfidence(), request.onlyStatus());
    }

    @Schema(description = "本地订单文件导入请求")
    public static class ImportFileRequest {
        /**
         * 本地订单文件路径
         */
        @Schema(description = "本地 CSV 或 Excel 订单文件路径", example = "examples/sample_orders.csv", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String filePath;
        /**
         * 导入时指定的订单归属人
         */
        @Schema(description = "导入时指定的订单归属人；为空时使用 CSV owner 字段或文件名后缀识别", example = "jtxw")
        private String owner;

        public ImportFileRequest() {
        }

        public String filePath() {
            return filePath;
        }

        public String owner() {
            return owner;
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }
    }

    @Schema(description = "当前价格比较请求")
    public static class ComparePriceRequest {
        /**
         * 原始商品名称
         */
        @Schema(description = "原始商品名称，会在服务端进行本地规则归一化", example = "猫砂", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String productName;
        /**
         * 当前总价
         */
        @Schema(description = "当前购买总价", example = "89", requiredMode = Schema.RequiredMode.REQUIRED)
        @Positive
        private double price;
        /**
         * 当前商品数量
         */
        @Schema(description = "当前购买数量", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        @Positive
        private double quantity;
        /**
         * 数量单位
         */
        @Schema(description = "数量单位，用于计算单位价格", example = "kg", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String unit;

        public ComparePriceRequest() {
        }

        public String productName() {
            return productName;
        }

        public double price() {
            return price;
        }

        public double quantity() {
            return quantity;
        }

        public String unit() {
            return unit;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public double getPrice() {
            return price;
        }

        public void setPrice(double price) {
            this.price = price;
        }

        public double getQuantity() {
            return quantity;
        }

        public void setQuantity(double quantity) {
            this.quantity = quantity;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }
    }

    @Schema(description = "历史价格基准线查询请求")
    public static class GetPriceBaselineRequest {
        /**
         * 原始商品名称
         */
        @Schema(description = "原始商品名称，会在服务端进行本地规则归一化", example = "纸巾", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String productName;

        /**
         * 可选统计单位
         */
        @Schema(description = "可选统计单位，例如 kg、抽、L；为空时使用商品规则中的标准单位", example = "抽")
        private String unit;

        public GetPriceBaselineRequest() {
        }

        public String productName() {
            return productName;
        }

        public String unit() {
            return unit;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }
    }

    @Schema(description = "复购品价格报告生成请求")
    public static class GenerateReportRequest {
        /**
         * 报告月份，格式为 yyyy-MM
         */
        @Schema(description = "报告月份，格式为 yyyy-MM", example = "2026-05", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String month;

        public GenerateReportRequest() {
        }

        public String month() {
            return month;
        }

        public String getMonth() {
            return month;
        }

        public void setMonth(String month) {
            this.month = month;
        }
    }

    @Schema(description = "人工复核应用请求")
    public static class ReviewApplyRequest {
        /**
         * 复核动作，取值 include 或 exclude
         */
        @Schema(description = "复核动作，include 表示纳入统计，exclude 表示排除统计", example = "include", allowableValues = {"include", "exclude"}, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String action;
        /**
         * 复核备注
         */
        @Schema(description = "人工复核备注", example = "确认是正常家庭消耗品购买记录")
        private String note;

        public ReviewApplyRequest() {
        }

        public String action() {
            return action;
        }

        public String note() {
            return note;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    @Schema(description = "批次商品归一化分析请求")
    public static class AnalyzeNormalizationRequest {
        /**
         * 最大分析候选数，默认 100。
         */
        @Schema(description = "最大分析候选数", example = "100")
        private int limit = 100;
        /**
         * 是否忽略同批次已有建议后重新分析。
         */
        @Schema(description = "是否强制重新分析", example = "false")
        private boolean forceReanalyze = false;
        /**
         * 包含关键词，命中商品名或 SKU 任一字段才进入候选；为空时不过滤。
         */
        @Schema(description = "包含关键词，命中商品名或 SKU 任一字段才进入候选")
        private List<String> includeKeywords = List.of();
        /**
         * 排除关键词，命中商品名或 SKU 任一字段时排除；为空时不过滤。
         */
        @Schema(description = "排除关键词，命中商品名或 SKU 任一字段时排除")
        private List<String> excludeKeywords = List.of();
        /**
         * 是否只重试已有 failed suggestion 对应的候选。
         */
        @Schema(description = "是否只重试已有 failed suggestion 对应的候选", example = "false")
        private boolean onlyFailed = false;

        public AnalyzeNormalizationRequest() {
        }

        public int limit() {
            return limit;
        }

        public boolean forceReanalyze() {
            return forceReanalyze;
        }

        public List<String> includeKeywords() {
            return includeKeywords;
        }

        public List<String> excludeKeywords() {
            return excludeKeywords;
        }

        public boolean onlyFailed() {
            return onlyFailed;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public boolean isForceReanalyze() {
            return forceReanalyze;
        }

        public void setForceReanalyze(boolean forceReanalyze) {
            this.forceReanalyze = forceReanalyze;
        }

        public List<String> getIncludeKeywords() {
            return includeKeywords;
        }

        public void setIncludeKeywords(List<String> includeKeywords) {
            this.includeKeywords = includeKeywords == null ? List.of() : includeKeywords;
        }

        public List<String> getExcludeKeywords() {
            return excludeKeywords;
        }

        public void setExcludeKeywords(List<String> excludeKeywords) {
            this.excludeKeywords = excludeKeywords == null ? List.of() : excludeKeywords;
        }

        public boolean isOnlyFailed() {
            return onlyFailed;
        }

        public void setOnlyFailed(boolean onlyFailed) {
            this.onlyFailed = onlyFailed;
        }
    }

    @Schema(description = "商品归一化建议批量应用请求")
    public static class BatchApplyNormalizationRequest {
        /**
         * 导入批次 ID。
         */
        @Schema(description = "导入批次 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        @Positive
        private long batchId;
        /**
         * 批量动作，当前仅支持 approve_normalize。
         */
        @Schema(description = "批量动作", example = "approve_normalize", allowableValues = {"approve_normalize"}, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        private String action;
        /**
         * 最小置信度阈值。
         */
        @Schema(description = "最小置信度阈值", example = "0.92")
        private double minConfidence = 0.92D;
        /**
         * 只应用的 suggestion 状态，默认 pending_batch_approval。
         */
        @Schema(description = "只应用的 suggestion 状态", example = "pending_batch_approval")
        private String onlyStatus = "pending_batch_approval";

        public BatchApplyNormalizationRequest() {
        }

        public long batchId() {
            return batchId;
        }

        public String action() {
            return action;
        }

        public double minConfidence() {
            return minConfidence;
        }

        public String onlyStatus() {
            return onlyStatus;
        }

        public long getBatchId() {
            return batchId;
        }

        public void setBatchId(long batchId) {
            this.batchId = batchId;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }

        public String getOnlyStatus() {
            return onlyStatus;
        }

        public void setOnlyStatus(String onlyStatus) {
            this.onlyStatus = onlyStatus;
        }
    }

    @Schema(description = "商品归一化复核应用请求")
    public static class ApplyNormalizationReviewRequest {
        /**
         * 归一化复核动作，取值 confirm、reject 或 ignore
         */
        @Schema(description = "归一化复核动作，confirm 确认、reject 拒绝、ignore 忽略",
                example = "confirm", allowableValues = {"confirm", "reject", "ignore"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String action;
        /**
         * 人工确认的标准品类
         */
        @Schema(description = "confirm 时人工确认的标准品类", example = "沐浴露")
        private String normalizedName;
        /**
         * 标准单位
         */
        @Schema(description = "confirm 时标准单位；为空时使用购买记录当前单位", example = "L")
        private String targetUnit;
        /**
         * 是否同步纳入价格基准
         */
        @Schema(description = "confirm 时是否同步将购买记录 decision 改为 include", example = "true")
        private boolean includeInBaseline;
        /**
         * 被拒绝的标准品类
         */
        @Schema(description = "reject 时被拒绝的标准品类；为空时使用购买记录当前 normalized_name", example = "猫砂")
        private String rejectedNormalizedName;
        /**
         * 复核备注
         */
        @Schema(description = "人工复核备注")
        private String note;

        public ApplyNormalizationReviewRequest() {
        }

        public String action() {
            return action;
        }

        public String normalizedName() {
            return normalizedName;
        }

        public String targetUnit() {
            return targetUnit;
        }

        public boolean includeInBaseline() {
            return includeInBaseline;
        }

        public String rejectedNormalizedName() {
            return rejectedNormalizedName;
        }

        public String note() {
            return note;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getNormalizedName() {
            return normalizedName;
        }

        public void setNormalizedName(String normalizedName) {
            this.normalizedName = normalizedName;
        }

        public String getTargetUnit() {
            return targetUnit;
        }

        public void setTargetUnit(String targetUnit) {
            this.targetUnit = targetUnit;
        }

        public boolean isIncludeInBaseline() {
            return includeInBaseline;
        }

        public void setIncludeInBaseline(boolean includeInBaseline) {
            this.includeInBaseline = includeInBaseline;
        }

        public String getRejectedNormalizedName() {
            return rejectedNormalizedName;
        }

        public void setRejectedNormalizedName(String rejectedNormalizedName) {
            this.rejectedNormalizedName = rejectedNormalizedName;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
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
}
