package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.*;
import com.jtxw.familyagent.application.command.ApplyNormalizationReviewCommand;
import com.jtxw.familyagent.application.command.NormalizationLibraryOperationCommand;
import com.jtxw.familyagent.application.command.RecordPurchaseCommand;
import com.jtxw.familyagent.application.query.ComparePriceQuery;
import com.jtxw.familyagent.application.query.ReviewItemQuery;
import com.jtxw.familyagent.domain.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
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
    private NormalizationLlmTaskService normalizationLlmTaskService;
    @MockitoBean
    private NormalizationRuleSuggestionService normalizationRuleSuggestionService;
    @MockitoBean
    private NormalizationLibraryService normalizationLibraryService;
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
    void listNormalizationLibraryShouldReturnRuleKeywordsAndSampleCount() throws Exception {
        when(normalizationLibraryService.listLibraryItems())
                .thenReturn(List.of(new NormalizationLibraryItem(
                        "cat_litter",
                        "猫砂",
                        "宠物用品",
                        "kg",
                        "weight",
                        List.of("猫砂", "豆腐砂"),
                        List.of("猫砂盆"),
                        3,
                        100,
                        true,
                        "system"
                )));

        mockMvc.perform(get("/api/tools/normalization-library"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleCode").value("cat_litter"))
                .andExpect(jsonPath("$[0].normalizedName").value("猫砂"))
                .andExpect(jsonPath("$[0].category").value("宠物用品"))
                .andExpect(jsonPath("$[0].standardUnit").value("kg"))
                .andExpect(jsonPath("$[0].unitFamily").value("weight"))
                .andExpect(jsonPath("$[0].keywords[0]").value("猫砂"))
                .andExpect(jsonPath("$[0].excludeKeywords[0]").value("猫砂盆"))
                .andExpect(jsonPath("$[0].sampleCount").value(3));
    }

    @Test
    void createNormalizationRuleShouldUseUnifiedOperationEntry() throws Exception {
        when(normalizationLibraryService.operate(any(NormalizationLibraryOperationCommand.class)))
                .thenReturn(NormalizationLibraryOperationResult.success(
                        "create_rule", "归一化规则已新增", "body_wash", "沐浴露", 1));

        mockMvc.perform(post("/api/tools/normalization-library")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "create_rule",
                                  "ruleCode": "body_wash",
                                  "normalizedName": "沐浴露",
                                  "category": "个护清洁",
                                  "standardUnit": "L",
                                  "unitFamily": "volume",
                                  "priority": 80,
                                  "keywords": ["沐浴露"],
                                  "excludeKeywords": ["沐浴露瓶"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.ruleCode").value("body_wash"))
                .andExpect(jsonPath("$.normalizedName").value("沐浴露"))
                .andExpect(jsonPath("$.action").value("create_rule"))
                .andExpect(jsonPath("$.message").value("归一化规则已新增"))
                .andExpect(jsonPath("$.affectedRows").value(1))
                .andExpect(jsonPath("$.warnings").isArray());

        ArgumentCaptor<NormalizationLibraryOperationCommand> captor =
                ArgumentCaptor.forClass(NormalizationLibraryOperationCommand.class);
        verify(normalizationLibraryService).operate(captor.capture());
        NormalizationLibraryOperationCommand command = captor.getValue();
        assertThat(command.action()).isEqualTo("create_rule");
        assertThat(command.ruleCode()).isEqualTo("body_wash");
        assertThat(command.keywords()).containsExactly("沐浴露");
        assertThat(command.excludeKeywords()).containsExactly("沐浴露瓶");
    }

    @Test
    void updateNormalizationRuleShouldUseUnifiedOperationEntry() throws Exception {
        when(normalizationLibraryService.operate(any(NormalizationLibraryOperationCommand.class)))
                .thenReturn(NormalizationLibraryOperationResult.success(
                        "update_rule", "归一化规则已更新", "body_wash", "沐浴露", 1));

        mockMvc.perform(post("/api/tools/normalization-library")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "update_rule",
                                  "ruleCode": "body_wash",
                                  "normalizedName": "沐浴露",
                                  "category": "个护清洁",
                                  "standardUnit": "L",
                                  "unitFamily": "volume",
                                  "priority": 90,
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value("body_wash"))
                .andExpect(jsonPath("$.action").value("update_rule"));

        ArgumentCaptor<NormalizationLibraryOperationCommand> captor =
                ArgumentCaptor.forClass(NormalizationLibraryOperationCommand.class);
        verify(normalizationLibraryService).operate(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("update_rule");
        assertThat(captor.getValue().ruleCode()).isEqualTo("body_wash");
        assertThat(captor.getValue().priority()).isEqualTo(90);
        assertThat(captor.getValue().enabled()).isTrue();
    }

    @Test
    void addNormalizationRuleKeywordShouldUseUnifiedOperationEntry() throws Exception {
        when(normalizationLibraryService.operate(any(NormalizationLibraryOperationCommand.class)))
                .thenReturn(NormalizationLibraryOperationResult.success(
                        "add_keyword", "归一化关键词已新增", "body_wash", "沐浴露", 1));

        mockMvc.perform(post("/api/tools/normalization-library")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "add_keyword",
                                  "ruleCode": "body_wash",
                                  "keyword": "沐浴露",
                                  "matchType": "include",
                                  "priority": 100
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value("body_wash"))
                .andExpect(jsonPath("$.action").value("add_keyword"))
                .andExpect(jsonPath("$.affectedRows").value(1));

        ArgumentCaptor<NormalizationLibraryOperationCommand> captor =
                ArgumentCaptor.forClass(NormalizationLibraryOperationCommand.class);
        verify(normalizationLibraryService).operate(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("add_keyword");
        assertThat(captor.getValue().ruleCode()).isEqualTo("body_wash");
        assertThat(captor.getValue().keyword()).isEqualTo("沐浴露");
        assertThat(captor.getValue().matchType()).isEqualTo("include");
    }

    @Test
    void disableNormalizationRuleKeywordShouldUseUnifiedOperationEntry() throws Exception {
        when(normalizationLibraryService.operate(any(NormalizationLibraryOperationCommand.class)))
                .thenReturn(NormalizationLibraryOperationResult.success(
                        "disable_keyword", "归一化关键词已禁用", "body_wash", "沐浴露", 1));

        mockMvc.perform(post("/api/tools/normalization-library")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "disable_keyword",
                                  "ruleCode": "body_wash",
                                  "keyword": "沐浴露瓶",
                                  "matchType": "exclude"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value("body_wash"))
                .andExpect(jsonPath("$.action").value("disable_keyword"));

        ArgumentCaptor<NormalizationLibraryOperationCommand> captor =
                ArgumentCaptor.forClass(NormalizationLibraryOperationCommand.class);
        verify(normalizationLibraryService).operate(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("disable_keyword");
        assertThat(captor.getValue().ruleCode()).isEqualTo("body_wash");
        assertThat(captor.getValue().keyword()).isEqualTo("沐浴露瓶");
        assertThat(captor.getValue().matchType()).isEqualTo("exclude");
    }

    @Test
    void disableNormalizationRuleShouldUseUnifiedOperationEntry() throws Exception {
        when(normalizationLibraryService.operate(any(NormalizationLibraryOperationCommand.class)))
                .thenReturn(NormalizationLibraryOperationResult.success(
                        "disable_rule", "归一化规则已禁用", "body_wash", "沐浴露", 1));

        mockMvc.perform(post("/api/tools/normalization-library")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "disable_rule",
                                  "ruleCode": "body_wash"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value("body_wash"))
                .andExpect(jsonPath("$.normalizedName").value("沐浴露"))
                .andExpect(jsonPath("$.action").value("disable_rule"))
                .andExpect(jsonPath("$.message").value("归一化规则已禁用"));

        ArgumentCaptor<NormalizationLibraryOperationCommand> captor =
                ArgumentCaptor.forClass(NormalizationLibraryOperationCommand.class);
        verify(normalizationLibraryService).operate(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("disable_rule");
        assertThat(captor.getValue().ruleCode()).isEqualTo("body_wash");
    }

    @Test
    void applyRuleToRecordsShouldUseUnifiedOperationEntry() throws Exception {
        when(normalizationLibraryService.operate(any(NormalizationLibraryOperationCommand.class)))
                .thenReturn(new NormalizationApplyRuleToRecordsResult(
                        "apply_rule_to_records",
                        true,
                        "contact_lenses",
                        "美瞳",
                        true,
                        1,
                        1,
                        1,
                        0,
                        0,
                        0,
                        List.of("dryRun=true，仅返回预览，不写入 purchase_records"),
                        List.of(new NormalizationApplyRuleToRecordsItem(
                                22L,
                                "美瞳日抛 10片",
                                "自然棕",
                                "applicable",
                                new NormalizationApplyRuleRecordSnapshot(
                                        "美瞳日抛 10片",
                                        BigDecimal.ONE,
                                        "件",
                                        BigDecimal.valueOf(33.79D),
                                        "exclude",
                                        "legacy_fallback"
                                ),
                                new NormalizationApplyRuleRecordSnapshot(
                                        "美瞳",
                                        BigDecimal.TEN,
                                        "片",
                                        BigDecimal.valueOf(3.379D),
                                        "include",
                                        "contact_lenses"
                                ),
                                List.of()
                        ))
                ));

        mockMvc.perform(post("/api/tools/normalization-library")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "apply_rule_to_records",
                                  "ruleCode": "contact_lenses",
                                  "onlyLegacyFallback": true,
                                  "onlyExcluded": true,
                                  "dryRun": true,
                                  "limit": 50
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("apply_rule_to_records"))
                .andExpect(jsonPath("$.ruleCode").value("contact_lenses"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.items[0].status").value("applicable"))
                .andExpect(jsonPath("$.items[0].after.normalizedName").value("美瞳"));

        ArgumentCaptor<NormalizationLibraryOperationCommand> captor =
                ArgumentCaptor.forClass(NormalizationLibraryOperationCommand.class);
        verify(normalizationLibraryService).operate(captor.capture());
        NormalizationLibraryOperationCommand command = captor.getValue();
        assertThat(command.action()).isEqualTo("apply_rule_to_records");
        assertThat(command.ruleCode()).isEqualTo("contact_lenses");
        assertThat(command.batchId()).isNull();
        assertThat(command.owner()).isNull();
        assertThat(command.onlyLegacyFallback()).isTrue();
        assertThat(command.onlyExcluded()).isTrue();
        assertThat(command.dryRun()).isTrue();
        assertThat(command.limit()).isEqualTo(50);
    }

    @Test
    void comparePriceShouldReturnCompareModeEvidenceStructure() throws Exception {
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
                .andExpect(jsonPath("$.mode").value("compare"))
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
    void comparePriceShouldReturnBaselineOnlyResult() throws Exception {
        when(priceAnalysisApplicationService.comparePrice(any(ComparePriceQuery.class)))
                .thenReturn(priceBaselineOnlyResult());

        mockMvc.perform(post("/api/tools/compare-price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "productName": "纸巾"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("baseline_only"))
                .andExpect(jsonPath("$.productName").value("纸巾"))
                .andExpect(jsonPath("$.normalizedName").value("纸巾"))
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.decision").doesNotExist())
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
    void comparePriceShouldRejectPartialPriceArguments() throws Exception {
        mockMvc.perform(post("/api/tools/compare-price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "productName": "纸巾",
                              "price": 39.9
                            }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("price、quantity、unit 必须同时提供，或同时省略。"));

        mockMvc.perform(post("/api/tools/compare-price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "productName": "纸巾",
                              "price": 39.9,
                              "quantity": 3120
                            }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("price、quantity、unit 必须同时提供，或同时省略。"));

        mockMvc.perform(post("/api/tools/compare-price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "productName": "纸巾",
                              "quantity": 3120,
                              "unit": "抽"
                            }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("price、quantity、unit 必须同时提供，或同时省略。"));
    }

    @Test
    void comparePriceShouldRejectInvalidPriceSample() throws Exception {
        mockMvc.perform(post("/api/tools/compare-price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "productName": "纸巾",
                              "price": 0,
                              "quantity": 3120,
                              "unit": "抽"
                            }
                            """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/tools/compare-price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "productName": "纸巾",
                              "price": 39.9,
                              "quantity": 0,
                              "unit": "抽"
                            }
                            """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/tools/compare-price")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "productName": "纸巾",
                              "price": 39.9,
                              "quantity": 3120,
                              "unit": " "
                            }
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unit 不能为空"));
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
                                      "normalizedName": "猫砂",
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

        ArgumentCaptor<RecordPurchaseCommand> captor = ArgumentCaptor.forClass(RecordPurchaseCommand.class);
        verify(recordPurchaseApplicationService).record(captor.capture());
        assertThat(captor.getValue().records().get(0).normalizedName()).isEqualTo("猫砂");
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
    void getNormalizationLlmTaskShouldReturnStatus() throws Exception {
        when(normalizationLlmTaskService.get(99L))
                .thenReturn(new NormalizationLlmTask(99L, "rule_suggestion", "running", 7L,
                        "jtxw", false, false, "legacy_fallback", 10, 3, 1,
                        0, 0, 0, List.of(), null, null,
                        "2026-06-07 10:00:00", "2026-06-07 10:00:02"));

        mockMvc.perform(get("/api/tools/normalization-llm-tasks/99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.taskType").value("rule_suggestion"))
                .andExpect(jsonPath("$.batchId").value(7))
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.analyzedCount").value(1));
    }

    @Test
    void createNormalizationRuleSuggestionTaskShouldUseDedicatedEntry() throws Exception {
        when(normalizationRuleSuggestionService.create(any()))
                .thenReturn(new NormalizationLlmTaskCreateResult(101L, "rule_suggestion", "pending",
                        "normalization rule suggestion task created"));

        mockMvc.perform(post("/api/tools/normalization-rule-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "batchId": 1,
                                  "apply": false,
                                  "candidateMode": "legacy_fallback",
                                  "limit": 100
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(101))
                .andExpect(jsonPath("$.taskType").value("rule_suggestion"))
                .andExpect(jsonPath("$.status").value("pending"));

        verify(normalizationRuleSuggestionService).create(any());
    }

    @Test
    void oldEndpointsShouldReturnNotFound() throws Exception {
        mockMvc.perform(post("/api/tools/import-batches/7/" + "analyze-" + "normalization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tools/normalization-" + "suggestions")
                        .param("batchId", "7"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tools/normalization-" + "suggestions/" + "auto-" + "excluded")
                        .param("batchId", "7"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/tools/normalization-" + "suggestions/batch-apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tools/normalization-" + "analysis-" + "tasks/99"))
                .andExpect(status().isNotFound());
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

    private PriceDecisionResult priceBaselineOnlyResult() {
        return new PriceDecisionResult(
                PriceDecisionResult.MODE_BASELINE_ONLY,
                "纸巾",
                "纸巾",
                null,
                new PriceDecisionResult.Baseline(2, "抽", 0.0125, 0.01375, 0.01375,
                        new PriceDecisionResult.DateRange("2026-04-01", "2026-05-01")),
                null,
                new PriceDecisionResult.Evidence("local_purchase_history", List.of(
                        new PriceDecisionResult.SourceRecord(201L, "historical_min", "2026-04-01",
                                "维达超韧抽纸 3层130抽*4包", 39.0, 3120.0, "抽",
                                0.0125, "抽", 3120.0, "抽")
                ), 0, List.of(), List.of()),
                List.of()
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

