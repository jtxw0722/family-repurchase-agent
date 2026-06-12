package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.*;
import com.jtxw.familyagent.application.command.AnalyzeNormalizationCommand;
import com.jtxw.familyagent.application.command.ApplyNormalizationReviewCommand;
import com.jtxw.familyagent.application.command.RecordPurchaseCommand;
import com.jtxw.familyagent.application.query.ComparePriceQuery;
import com.jtxw.familyagent.application.query.GetPriceBaselineQuery;
import com.jtxw.familyagent.application.query.ReviewItemQuery;
import com.jtxw.familyagent.domain.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
 * @Description: REST Tool API 控制器测试，覆盖工具接口响应结构和异常映射。
 */
@WebMvcTest({
        ImportToolController.class,
        RecordPurchaseToolController.class,
        PriceToolController.class,
        ReportToolController.class,
        ReviewToolController.class,
        NormalizationToolController.class,
        PurchaseRecordSearchController.class
})
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
    @MockitoBean
    private PurchaseRecordSearchService purchaseRecordSearchService;

    @Test
    void searchPurchaseRecordsShouldReturnRecordsWithFamilyScope() throws Exception {
        when(purchaseRecordSearchService.search(any()))
                .thenReturn(searchPurchaseRecordsResult("FAMILY", null, 1));

        mockMvc.perform(post("/api/tools/purchase-records/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "猫砂",
                                  "limit": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyword").value("猫砂"))
                .andExpect(jsonPath("$.scope").value("FAMILY"))
                .andExpect(jsonPath("$.owner").doesNotExist())
                .andExpect(jsonPath("$.matchedCount").value(1))
                .andExpect(jsonPath("$.returnedCount").value(1))
                .andExpect(jsonPath("$.records[0].recordId").value(123))
                .andExpect(jsonPath("$.records[0].productName").value("名创优品猫砂"))
                .andExpect(jsonPath("$.records[0].sku").value("混合猫砂 40kg"))
                .andExpect(jsonPath("$.records[0].normalizedName").value("猫砂"))
                .andExpect(jsonPath("$.warnings[0]").exists());
    }

    @Test
    void searchPurchaseRecordsShouldReturnOwnerScopeWhenOwnerProvided() throws Exception {
        when(purchaseRecordSearchService.search(any()))
                .thenReturn(searchPurchaseRecordsResult("OWNER", "jtxw", 1));

        mockMvc.perform(post("/api/tools/purchase-records/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "猫砂",
                                  "owner": "jtxw",
                                  "limit": 10
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("OWNER"))
                .andExpect(jsonPath("$.owner").value("jtxw"));
    }

    @Test
    void searchPurchaseRecordsShouldReturnBadRequestWhenKeywordMissing() throws Exception {
        mockMvc.perform(post("/api/tools/purchase-records/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "limit": 10
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchPurchaseRecordsShouldReturnEmptyRecordsWhenNoMatch() throws Exception {
        when(purchaseRecordSearchService.search(any()))
                .thenReturn(searchPurchaseRecordsResult("FAMILY", null, 0));

        mockMvc.perform(post("/api/tools/purchase-records/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "keyword": "不存在"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedCount").value(0))
                .andExpect(jsonPath("$.records").isArray())
                .andExpect(jsonPath("$.records").isEmpty());
    }

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
    void listReviewItemsShouldPassQueryParameters() throws Exception {
        when(reviewApplicationService.listReviewItems(any(ReviewItemQuery.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/tools/review-items")
                        .param("status", "pending")
                        .param("batchId", "3")
                        .param("owner", "jtxw")
                        .param("reasonCode", "QUANTITY_UNIT_PARSE_REVIEW")
                        .param("decision", "exclude")
                        .param("sourceFile", "order-sample-2.xlsx")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<ReviewItemQuery> captor = ArgumentCaptor.forClass(ReviewItemQuery.class);
        verify(reviewApplicationService).listReviewItems(captor.capture());
        ReviewItemQuery query = captor.getValue();
        assertThat(query.status()).isEqualTo("pending");
        assertThat(query.batchId()).isEqualTo(3L);
        assertThat(query.owner()).isEqualTo("jtxw");
        assertThat(query.reasonCode()).isEqualTo("QUANTITY_UNIT_PARSE_REVIEW");
        assertThat(query.decision()).isEqualTo("exclude");
        assertThat(query.sourceFile()).isEqualTo("order-sample-2.xlsx");
        assertThat(query.page()).isEqualTo(2);
        assertThat(query.size()).isEqualTo(10);
    }

    @Test
    void listReviewItemsShouldUseDefaultPageAndSize() throws Exception {
        when(reviewApplicationService.listReviewItems(any(ReviewItemQuery.class)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/tools/review-items"))
                .andExpect(status().isOk());

        verify(reviewApplicationService).listReviewItems(any(ReviewItemQuery.class));
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
    void listAutoExcludedNormalizationSuggestionsShouldUseDefaultMinConfidence() throws Exception {
        when(normalizationSuggestionService.listAutoExcluded(1L, null))
                .thenReturn(autoExcludedResult(0.9D));

        mockMvc.perform(get("/api/tools/normalization-suggestions/auto-excluded")
                        .param("batchId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(1))
                .andExpect(jsonPath("$.minConfidence").value(0.9))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.typeCounts[0].productType").value("DURABLE"))
                .andExpect(jsonPath("$.typeCounts[0].count").value(1))
                .andExpect(jsonPath("$.items[0].suggestionId").value(11))
                .andExpect(jsonPath("$.items[0].rawProductName").value("猫砂盆大号"))
                .andExpect(jsonPath("$.items[0].sku").value("默认"))
                .andExpect(jsonPath("$.items[0].aliasKey").value("maoshapendahao"))
                .andExpect(jsonPath("$.items[0].action").value("EXCLUDE"))
                .andExpect(jsonPath("$.items[0].productType").value("DURABLE"))
                .andExpect(jsonPath("$.items[0].confidence").value(0.96))
                .andExpect(jsonPath("$.items[0].reviewRequired").value(false))
                .andExpect(jsonPath("$.items[0].status").value("auto_excluded"))
                .andExpect(jsonPath("$.items[0].reason").value("后处理修正：高置信耐用品，自动排除"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-06-12T10:00:00"));
    }

    @Test
    void listAutoExcludedNormalizationSuggestionsShouldPassCustomMinConfidence() throws Exception {
        when(normalizationSuggestionService.listAutoExcluded(1L, 0.95D))
                .thenReturn(autoExcludedResult(0.95D));

        mockMvc.perform(get("/api/tools/normalization-suggestions/auto-excluded")
                        .param("batchId", "1")
                        .param("minConfidence", "0.95"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minConfidence").value(0.95));
    }

    @Test
    void listAutoExcludedNormalizationSuggestionsShouldReturnBadRequestForInvalidBatchId() throws Exception {
        when(normalizationSuggestionService.listAutoExcluded(0L, null))
                .thenThrow(new IllegalArgumentException("batchId 必须大于 0"));

        mockMvc.perform(get("/api/tools/normalization-suggestions/auto-excluded")
                        .param("batchId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("batchId 必须大于 0"));
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

    private AutoExcludedNormalizationSuggestionResult autoExcludedResult(double minConfidence) {
        return new AutoExcludedNormalizationSuggestionResult(
                1L,
                minConfidence,
                1,
                List.of(new AutoExcludedNormalizationSuggestionResult.TypeCount("DURABLE", 1L)),
                List.of(new AutoExcludedNormalizationSuggestionResult.Item(
                        11L,
                        "猫砂盆大号",
                        "默认",
                        "maoshapendahao",
                        "EXCLUDE",
                        "DURABLE",
                        0.96D,
                        false,
                        "auto_excluded",
                        "后处理修正：高置信耐用品，自动排除",
                        "2026-06-12T10:00:00"
                ))
        );
    }

    private SearchPurchaseRecordsResult searchPurchaseRecordsResult(String scope, String owner, int matchedCount) {
        List<SearchPurchaseRecordsResult.Item> records = matchedCount == 0 ? List.of() : List.of(
                new SearchPurchaseRecordsResult.Item(
                        123L,
                        "2026-05-21 10:30:00",
                        "淘宝",
                        "jtxw",
                        "名创优品猫砂",
                        "混合猫砂 40kg",
                        "宠物用品",
                        "猫砂",
                        40D,
                        "kg",
                        119.30D,
                        "CNY",
                        "猫砂",
                        2.9825D
                )
        );
        return new SearchPurchaseRecordsResult(
                "猫砂",
                scope,
                owner,
                matchedCount,
                records.size(),
                records,
                List.of("该结果来自原始订单记录检索，不代表已完成商品归一化。")
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
