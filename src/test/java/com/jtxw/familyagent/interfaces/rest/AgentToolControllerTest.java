package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.ImportApplicationService;
import com.jtxw.familyagent.application.NormalizationAnalysisTaskConflictException;
import com.jtxw.familyagent.application.NormalizationAnalysisTaskService;
import com.jtxw.familyagent.application.NormalizationSuggestionService;
import com.jtxw.familyagent.application.PriceAnalysisApplicationService;
import com.jtxw.familyagent.application.RecordPurchaseApplicationService;
import com.jtxw.familyagent.application.ReportApplicationService;
import com.jtxw.familyagent.application.ReviewApplicationService;
import com.jtxw.familyagent.application.command.AnalyzeNormalizationCommand;
import com.jtxw.familyagent.application.command.ApplyNormalizationReviewCommand;
import com.jtxw.familyagent.application.command.ApplyReviewCommand;
import com.jtxw.familyagent.application.command.BatchApplyNormalizationCommand;
import com.jtxw.familyagent.application.command.ImportFileCommand;
import com.jtxw.familyagent.application.command.RecordPurchaseCommand;
import com.jtxw.familyagent.application.query.ComparePriceQuery;
import com.jtxw.familyagent.application.query.GetPriceBaselineQuery;
import com.jtxw.familyagent.domain.model.NormalizationAnalysisTask;
import com.jtxw.familyagent.domain.model.NormalizationAnalysisTaskCreateResult;
import com.jtxw.familyagent.domain.model.PriceBaselineResult;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.RecordPurchaseResult;
import com.jtxw.familyagent.domain.model.ReviewApplyResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 17:58:26
 * @Description: Agent Tool REST API 控制器测试，覆盖工具接口响应结构和异常映射。
 */
@WebMvcTest(AgentToolController.class)
class AgentToolControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImportApplicationService importApplicationService;
    @MockitoBean
    private PriceAnalysisApplicationService priceAnalysisApplicationService;
    @MockitoBean
    private RecordPurchaseApplicationService recordPurchaseApplicationService;
    @MockitoBean
    private ReportApplicationService reportApplicationService;
    @MockitoBean
    private ReviewApplicationService reviewApplicationService;
    @MockitoBean
    private NormalizationSuggestionService normalizationSuggestionService;
    @MockitoBean
    private NormalizationAnalysisTaskService normalizationAnalysisTaskService;

    @Test
    void comparePriceShouldReturnEvidenceStructure() throws Exception {
        when(priceAnalysisApplicationService.comparePrice(any(ComparePriceQuery.class)))
                .thenReturn(priceDecisionResult());

        mockMvc.perform(post("/api/tools/compare-price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productName": "cat litter",
                                  "price": 119.3,
                                  "quantity": 40,
                                  "unit": "kg"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.unitPrice").value(2.9825))
                .andExpect(jsonPath("$.baseline.sampleSize").value(23))
                .andExpect(jsonPath("$.baseline.unit").value("kg"))
                .andExpect(jsonPath("$.baseline.dateRange.from").value("2025-11-01"))
                .andExpect(jsonPath("$.decision.code").value("good_price"))
                .andExpect(jsonPath("$.evidence.source").value("local_purchase_history"))
                .andExpect(jsonPath("$.evidence.sourceRecords[0].role").value("historical_min"))
                .andExpect(jsonPath("$.evidence.sourceRecords[0].unitPriceUnit").value("kg"))
                .andExpect(jsonPath("$.evidence.sourceRecords[0].originalUnit").value("kg"))
                .andExpect(jsonPath("$.evidence.sourceRecords[0].originalQuantity").value(10.0))
                .andExpect(jsonPath("$.evidence.outliers").isArray())
                .andExpect(jsonPath("$.warnings[0]").exists());
    }

    /**
     * 验证非法报告月份会通过 REST Tool API 返回 400，而不是暴露底层文件路径异常。
     */
    @Test
    void generateReportShouldReturnBadRequestForInvalidMonthFormat() throws Exception {
        when(reportApplicationService.generatePriceReport("2026/05"))
                .thenThrow(new IllegalArgumentException("报告月份格式错误，请使用 yyyy-MM，例如 2026-05。"));

        mockMvc.perform(post("/api/tools/generate-report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "month": "2026/05"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("报告月份格式错误，请使用 yyyy-MM，例如 2026-05。"));
    }

    @Test
    void getPriceBaselineShouldReturnBaselineEvidenceAndWarnings() throws Exception {
        when(priceAnalysisApplicationService.getPriceBaseline(any(GetPriceBaselineQuery.class)))
                .thenReturn(priceBaselineResult());

        mockMvc.perform(post("/api/tools/get-price-baseline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "productName": "纸巾"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("纸巾"))
                .andExpect(jsonPath("$.normalizedName").value("纸巾"))
                .andExpect(jsonPath("$.baseline.sampleSize").value(2))
                .andExpect(jsonPath("$.baseline.unit").value("抽"))
                .andExpect(jsonPath("$.baseline.historicalMin").value(0.0125))
                .andExpect(jsonPath("$.baseline.historicalMedian").value(0.01375))
                .andExpect(jsonPath("$.baseline.dateRange.from").value("2026-04-01"))
                .andExpect(jsonPath("$.evidence.source").value("local_purchase_history"))
                .andExpect(jsonPath("$.evidence.sourceRecords[0].role").value("historical_min"))
                .andExpect(jsonPath("$.warnings").isArray());
    }

    @Test
    void recordPurchaseShouldReturnRecordResult() throws Exception {
        when(recordPurchaseApplicationService.record(any(RecordPurchaseCommand.class)))
                .thenReturn(new RecordPurchaseResult(false, 1, 0, List.of(
                        new RecordPurchaseResult.RecordResult(1001L, "猫砂", "猫砂", 109.9D,
                                24D, "kg", 4.579167D, "include", false, List.of())
                )));

        mockMvc.perform(post("/api/tools/record-purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dryRun": false,
                                  "records": [
                                    {
                                      "productName": "猫砂",
                                      "price": 109.9,
                                      "quantity": 24,
                                      "unit": "kg",
                                      "platform": "JD",
                                      "purchaseDate": "2026-06-04",
                                      "owner": "jtxw",
                                      "shopName": "京东自营",
                                      "sku": "6kg*4包",
                                      "note": "手动录入",
                                      "sourceText": "昨天在京东买了猫砂，109.9 元，6kg*4 包，京东自营。"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.savedCount").value(1))
                .andExpect(jsonPath("$.reviewCount").value(0))
                .andExpect(jsonPath("$.records[0].recordId").value(1001))
                .andExpect(jsonPath("$.records[0].normalizedName").value("猫砂"))
                .andExpect(jsonPath("$.records[0].unitPrice").value(4.579167))
                .andExpect(jsonPath("$.records[0].decision").value("include"));
    }

    @Test
    void recordPurchaseShouldRejectEmptyRecords() throws Exception {
        mockMvc.perform(post("/api/tools/record-purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dryRun": false,
                                  "records": []
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordPurchaseShouldReturnBadRequestForInvalidPurchaseDate() throws Exception {
        when(recordPurchaseApplicationService.record(any(RecordPurchaseCommand.class)))
                .thenThrow(new IllegalArgumentException("purchaseDate 格式错误，请使用 yyyy-MM-dd，例如 2026-06-04。"));

        mockMvc.perform(post("/api/tools/record-purchase")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dryRun": false,
                                  "records": [
                                    {
                                      "productName": "猫砂",
                                      "price": 109.9,
                                      "quantity": 24,
                                      "unit": "kg",
                                      "purchaseDate": "2021-02-30 13:45:20"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("purchaseDate 格式错误，请使用 yyyy-MM-dd，例如 2026-06-04。"));
    }

    @Test
    void applyNormalizationShouldConfirmReview() throws Exception {
        when(reviewApplicationService.applyNormalization(any(ApplyNormalizationReviewCommand.class)))
                .thenReturn(new ReviewApplyResult(12L, 100L, "confirm_normalization", "include",
                        "resolved", "归一化已确认"));

        mockMvc.perform(post("/api/tools/review-items/12/apply-normalization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "confirm",
                                  "normalizedName": "沐浴露",
                                  "targetUnit": "L",
                                  "includeInBaseline": true,
                                  "note": "确认"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("confirm_normalization"))
                .andExpect(jsonPath("$.decision").value("include"));
    }

    @Test
    void applyNormalizationShouldRejectReview() throws Exception {
        when(reviewApplicationService.applyNormalization(any(ApplyNormalizationReviewCommand.class)))
                .thenReturn(new ReviewApplyResult(13L, 101L, "reject_normalization", "exclude",
                        "resolved", "归一化已拒绝"));

        mockMvc.perform(post("/api/tools/review-items/13/apply-normalization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "reject",
                                  "rejectedNormalizedName": "猫砂",
                                  "note": "误判"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("reject_normalization"))
                .andExpect(jsonPath("$.decision").value("exclude"));
    }

    @Test
    void applyNormalizationShouldIgnoreReview() throws Exception {
        when(reviewApplicationService.applyNormalization(any(ApplyNormalizationReviewCommand.class)))
                .thenReturn(new ReviewApplyResult(14L, 102L, "ignore_normalization", "exclude",
                        "resolved", "归一化复核已忽略"));

        mockMvc.perform(post("/api/tools/review-items/14/apply-normalization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "ignore",
                                  "note": "暂不学习"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("ignore_normalization"));
    }

    @Test
    void analyzeNormalizationShouldPassKeywordFilters() throws Exception {
        when(normalizationAnalysisTaskService.create(any(AnalyzeNormalizationCommand.class)))
                .thenReturn(new NormalizationAnalysisTaskCreateResult(99L, 7L, "pending",
                        "归一化建议分析任务已创建"));

        mockMvc.perform(post("/api/tools/import-batches/7/analyze-normalization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "limit": 10,
                                  "forceReanalyze": false,
                                  "includeKeywords": ["主食罐"],
                                  "excludeKeywords": ["试吃"],
                                  "onlyFailed": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(99))
                .andExpect(jsonPath("$.batchId").value(7))
                .andExpect(jsonPath("$.status").value("pending"));

        verify(normalizationAnalysisTaskService).create(any(AnalyzeNormalizationCommand.class));
    }

    @Test
    void getNormalizationAnalysisTaskShouldReturnStatus() throws Exception {
        when(normalizationAnalysisTaskService.get(99L))
                .thenReturn(new NormalizationAnalysisTask(99L, 7L, "running", 10,
                        false, List.of("cat"), List.of(), true, 3, 1,
                        0, 1, 0, 0, 1, 2,
                        null, null, "2026-06-07 10:00:00", "2026-06-07 10:00:01",
                        null, "2026-06-07 10:00:02"));

        mockMvc.perform(get("/api/tools/normalization-analysis-tasks/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(99))
                .andExpect(jsonPath("$.batchId").value(7))
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.analyzedCount").value(1));
    }

    @Test
    void analyzeNormalizationShouldReturnConflictWhenActiveTaskExists() throws Exception {
        when(normalizationAnalysisTaskService.create(any(AnalyzeNormalizationCommand.class)))
                .thenThrow(new NormalizationAnalysisTaskConflictException("已有归一化建议分析任务正在执行，请稍后再试"));

        mockMvc.perform(post("/api/tools/import-batches/7/analyze-normalization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("已有归一化建议分析任务正在执行，请稍后再试"));
    }

    @Test
    void applyNormalizationShouldRejectInvalidAction() throws Exception {
        when(reviewApplicationService.applyNormalization(any(ApplyNormalizationReviewCommand.class)))
                .thenThrow(new IllegalArgumentException("不支持的商品归一化复核动作：learn"));

        mockMvc.perform(post("/api/tools/review-items/15/apply-normalization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "learn",
                                  "note": "无效动作"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("不支持的商品归一化复核动作：learn"));
    }

    private PriceBaselineResult priceBaselineResult() {
        return new PriceBaselineResult(
                "纸巾",
                "纸巾",
                new PriceDecisionResult.Baseline(2, "抽", 0.0125, 0.01375, 0.01375,
                        new PriceDecisionResult.DateRange("2026-04-01", "2026-05-01")),
                new PriceDecisionResult.Evidence("local_purchase_history", List.of(
                        new PriceDecisionResult.SourceRecord(201L, "historical_min", "2026-04-01",
                                "维达超韧抽纸 3层130抽×24包", 39.0, 3120.0, "抽",
                                0.0125, "抽", 3120.0, "抽")
                ), 0, List.of(), List.of()),
                List.of()
        );
    }

    private PriceDecisionResult priceDecisionResult() {
        return new PriceDecisionResult(
                "cat litter",
                "cat litter",
                new PriceDecisionResult.Current(119.3, 40, "kg", 2.9825, "119.3 / 40 = 2.9825"),
                new PriceDecisionResult.Baseline(23, "kg", 6.8, 15.9, 56.73797101449276,
                        new PriceDecisionResult.DateRange("2025-11-01", "2026-05-20")),
                new PriceDecisionResult.Decision("good_price", "good price",
                        "Current unit price 2.98 CNY/kg is lower than the historical minimum and median.",
                        "medium"),
                new PriceDecisionResult.Evidence("local_purchase_history", List.of(
                        new PriceDecisionResult.SourceRecord(101L, "historical_min", "2026-04-18",
                                "cat litter 10kg", 68.0, 10.0, "kg", 6.8, "kg", 10.0, "kg")
                ), 0, List.of(), List.of()),
                List.of("Historical average is much higher than median.")
        );
    }
}
