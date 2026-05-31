package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.ImportApplicationService;
import com.jtxw.familyagent.application.PriceAnalysisApplicationService;
import com.jtxw.familyagent.application.ReportApplicationService;
import com.jtxw.familyagent.application.ReviewApplicationService;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @Author: jtxw
 * @Date: 2026/05/28/22:02
 * @Description: Agent Tool REST API tests
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
    private ReportApplicationService reportApplicationService;
    @MockitoBean
    private ReviewApplicationService reviewApplicationService;

    @Test
    void comparePriceShouldReturnEvidenceStructure() throws Exception {
        when(priceAnalysisApplicationService.comparePrice(anyString(), anyDouble(), anyDouble(), anyString()))
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
