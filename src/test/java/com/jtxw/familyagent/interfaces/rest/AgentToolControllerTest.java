package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.*;
import com.jtxw.familyagent.application.command.ApplyNormalizationReviewCommand;
import com.jtxw.familyagent.application.command.NormalizationLibraryOperationCommand;
import com.jtxw.familyagent.application.command.ParseOrderImageCommand;
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
 * @Date: 2026/06/25 19:14:00
 * @Description: REST Tool API 控制器测试，覆盖工具接口响应结构和异常映射。
 */
@WebMvcTest({
        ImportToolController.class,
        RecordPurchaseToolController.class,
        PriceToolController.class,
        ReportToolController.class,
        ReviewToolController.class,
        NormalizationToolController.class,
        PurchaseRecordSearchController.class,
        ParseOrderImageToolController.class
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
    @MockitoBean
    private ParseOrderImageApplicationService parseOrderImageApplicationService;

    @Test
    void parseOrderImageShouldReturnCandidates() throws Exception {
        ParsedPurchaseCandidate candidate = new ParsedPurchaseCandidate(
                "合成测试纸巾", "100抽", 12.5D, 100D, "抽", "pdd", "jtxw",
                "2026-06-18", "合成测试旗舰店", "OCR 识别候选，需用户确认后再入库",
                "合成 OCR 文本", 0.9D, List.of());
        when(parseOrderImageApplicationService.parse(any()))
                .thenReturn(new ParseOrderImageResult(true, "data/inbox/order.png", "order_screenshot",
                        1, List.of(candidate), List.of("只返回候选样本"), "合成 OCR 文本"));

        mockMvc.perform(post("/api/tools/parse-order-image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imagePath": "data/inbox/order.png",
                                  "owner": "jtxw",
                                  "platform": "pdd",
                                  "dryRun": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andExpect(jsonPath("$.candidates[0].productName").value("合成测试纸巾"))
                .andExpect(jsonPath("$.rawText").value("合成 OCR 文本"));
    }

    @Test
    void parseOrderImageShouldAcceptBase64FieldsWithoutImagePath() throws Exception {
        ParsedPurchaseCandidate candidate = new ParsedPurchaseCandidate(
                "合成测试咖啡", "268ml", 12.5D, 268D, "ml", "tmall", "jtxw",
                "2026-06-24", "合成测试旗舰店", "OCR 识别候选，需用户确认后再入库",
                "合成 OCR 文本", 0.9D, List.of());
        when(parseOrderImageApplicationService.parse(any()))
                .thenReturn(new ParseOrderImageResult(true, "order.jpg", "order_screenshot",
                        1, List.of(candidate), List.of("只返回候选样本"), "合成 OCR 文本"));

        mockMvc.perform(post("/api/tools/parse-order-image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageBase64": "data:image/jpeg;base64,AQID",
                                  "imageFileName": "order.jpg",
                                  "imageMimeType": "image/jpeg",
                                  "owner": "jtxw",
                                  "platform": "tmall",
                                  "dryRun": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imagePath").value("order.jpg"))
                .andExpect(jsonPath("$.candidateCount").value(1))
                .andExpect(jsonPath("$.candidates[0].productName").value("合成测试咖啡"));

        ArgumentCaptor<ParseOrderImageCommand> captor = ArgumentCaptor.forClass(ParseOrderImageCommand.class);
        verify(parseOrderImageApplicationService).parse(captor.capture());
        ParseOrderImageCommand command = captor.getValue();
        assertThat(command.imagePath()).isNull();
        assertThat(command.imageBase64()).isEqualTo("data:image/jpeg;base64,AQID");
        assertThat(command.imageFileName()).isEqualTo("order.jpg");
        assertThat(command.imageMimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void parseOrderImageShouldRejectWhenImagePathAndBase64BothMissing() throws Exception {
        when(parseOrderImageApplicationService.parse(any()))
                .thenThrow(new IllegalArgumentException("imageBase64 和 imagePath 至少需要提供一个"));

        mockMvc.perform(post("/api/tools/parse-order-image")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"owner\":\"jtxw\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("imageBase64 和 imagePath 至少需要提供一个"));
    }

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
    void createNormalizationRuleShouldAcceptCountUnitFromUnifiedOperationEntry() throws Exception {
        when(normalizationLibraryService.operate(any(NormalizationLibraryOperationCommand.class)))
                .thenReturn(NormalizationLibraryOperationResult.success(
                        "create_rule", "归一化规则已新增", "dry_cell", "干电池", 1));

        mockMvc.perform(post("/api/tools/normalization-library")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "create_rule",
                                  "ruleCode": "dry_cell",
                                  "normalizedName": "干电池",
                                  "category": "电子",
                                  "standardUnit": "粒",
                                  "unitFamily": "count",
                                  "priority": 80,
                                  "enabled": true,
                                  "keywords": ["干电池", "电池"],
                                  "excludeKeywords": ["充电宝", "电池盒", "收纳"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.ruleCode").value("dry_cell"))
                .andExpect(jsonPath("$.normalizedName").value("干电池"));

        ArgumentCaptor<NormalizationLibraryOperationCommand> captor =
                ArgumentCaptor.forClass(NormalizationLibraryOperationCommand.class);
        verify(normalizationLibraryService).operate(captor.capture());
        NormalizationLibraryOperationCommand command = captor.getValue();
        assertThat(command.action()).isEqualTo("create_rule");
        assertThat(command.ruleCode()).isEqualTo("dry_cell");
        assertThat(command.normalizedName()).isEqualTo("干电池");
        assertThat(command.standardUnit()).isEqualTo("粒");
        assertThat(command.unitFamily()).isEqualTo("count");
        assertThat(command.keywords()).containsExactly("干电池", "电池");
        assertThat(command.excludeKeywords()).containsExactly("充电宝", "电池盒", "收纳");
    }

    @Test
    void createNormalizationRuleShouldReturnBadRequestWhenNormalizedNameIsExcluded() throws Exception {
        when(normalizationLibraryService.operate(any(NormalizationLibraryOperationCommand.class)))
                .thenThrow(new IllegalArgumentException("normalizedName cannot be both include and exclude"));

        mockMvc.perform(post("/api/tools/normalization-library")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "create_rule",
                                  "ruleCode": "body_wash",
                                  "normalizedName": "body wash",
                                  "category": "personal care",
                                  "standardUnit": "L",
                                  "unitFamily": "volume",
                                  "keywords": ["shower gel"],
                                  "excludeKeywords": ["body wash"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("normalizedName cannot be both include and exclude"));
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
    void updateNormalizationRuleShouldPassKeywordSnapshots() throws Exception {
        when(normalizationLibraryService.operate(any(NormalizationLibraryOperationCommand.class)))
                .thenReturn(NormalizationLibraryOperationResult.success(
                        "update_rule", "归一化规则已更新", "cat_food", "猫粮", 1));

        mockMvc.perform(post("/api/tools/normalization-library")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "update_rule",
                                  "ruleCode": "cat_food",
                                  "normalizedName": "猫粮",
                                  "category": "宠物用品",
                                  "standardUnit": "kg",
                                  "unitFamily": "weight",
                                  "priority": 90,
                                  "enabled": true,
                                  "keywords": ["猫粮", "幼猫粮"],
                                  "excludeKeywords": ["猫粮勺", "冻干"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value("cat_food"))
                .andExpect(jsonPath("$.action").value("update_rule"));

        ArgumentCaptor<NormalizationLibraryOperationCommand> captor =
                ArgumentCaptor.forClass(NormalizationLibraryOperationCommand.class);
        verify(normalizationLibraryService).operate(captor.capture());
        NormalizationLibraryOperationCommand command = captor.getValue();
        assertThat(command.action()).isEqualTo("update_rule");
        assertThat(command.ruleCode()).isEqualTo("cat_food");
        assertThat(command.keywords()).containsExactly("猫粮", "幼猫粮");
        assertThat(command.excludeKeywords()).containsExactly("猫粮勺", "冻干");
    }

    @Test
    void applyRuleToRecordsShouldUseUnifiedOperationEntry() throws Exception {
        when(normalizationLibraryService.operate(any(NormalizationLibraryOperationCommand.class)))
                .thenReturn(new NormalizationApplyRuleToRecordsResult(
                        "apply_rule_to_records",
                        true,
                        "cat_wet_food",
                        "cat wet food",
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
                                "cat wet food 600g",
                                "600g",
                                "applicable",
                                new NormalizationApplyRuleRecordSnapshot(
                                        "cat wet food 600g",
                                        BigDecimal.ONE,
                                        "件",
                                        BigDecimal.valueOf(31.28D),
                                        "exclude",
                                        "legacy_fallback"
                                ),
                                new NormalizationApplyRuleRecordSnapshot(
                                        "cat wet food",
                                        BigDecimal.valueOf(600D),
                                        "g",
                                        BigDecimal.valueOf(0.052133333333D),
                                        "include",
                                        "cat_wet_food"
                                ),
                                List.of("数量来源：sku", "规格解析：600g => 600g")
                        ))
                ));

        mockMvc.perform(post("/api/tools/normalization-library")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "apply_rule_to_records",
                                  "ruleCode": "cat_wet_food",
                                  "onlyLegacyFallback": true,
                                  "onlyExcluded": true,
                                  "dryRun": true,
                                  "limit": 50
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("apply_rule_to_records"))
                .andExpect(jsonPath("$.ruleCode").value("cat_wet_food"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.items[0].status").value("applicable"))
                .andExpect(jsonPath("$.items[0].after.normalizedName").value("cat wet food"))
                .andExpect(jsonPath("$.items[0].after.quantity").value(600))
                .andExpect(jsonPath("$.items[0].after.unit").value("g"))
                .andExpect(jsonPath("$.items[0].after.unitPrice").value(0.052133333333D))
                .andExpect(jsonPath("$.items[0].warnings[0]").value("数量来源：sku"));

        ArgumentCaptor<NormalizationLibraryOperationCommand> captor =
                ArgumentCaptor.forClass(NormalizationLibraryOperationCommand.class);
        verify(normalizationLibraryService).operate(captor.capture());
        NormalizationLibraryOperationCommand command = captor.getValue();
        assertThat(command.action()).isEqualTo("apply_rule_to_records");
        assertThat(command.ruleCode()).isEqualTo("cat_wet_food");
        assertThat(command.batchId()).isNull();
        assertThat(command.owner()).isNull();
        assertThat(command.onlyLegacyFallback()).isTrue();
        assertThat(command.onlyExcluded()).isTrue();
        assertThat(command.dryRun()).isTrue();
        assertThat(command.limit()).isEqualTo(50);
    }

    @Test
    void recheckRuleRecordsShouldUseUnifiedOperationEntry() throws Exception {
        when(normalizationLibraryService.operate(any(NormalizationLibraryOperationCommand.class)))
                .thenReturn(new NormalizationRecheckRuleRecordsResult(
                        "recheck_rule_records",
                        true,
                        "cat_food",
                        "猫粮",
                        true,
                        1,
                        1,
                        1,
                        1,
                        0,
                        0,
                        0,
                        0,
                        List.of("dryRun=true，仅返回预览，不写入 purchase_records"),
                        List.of(new NormalizationRecheckRuleRecordsItem(
                                22L,
                                "某品牌冻干猫粮",
                                "1kg",
                                "would_reset",
                                "冻干",
                                new NormalizationApplyRuleRecordSnapshot(
                                        "猫粮",
                                        BigDecimal.ONE,
                                        "kg",
                                        BigDecimal.valueOf(39.9D),
                                        "include",
                                        "cat_food"
                                ),
                                new NormalizationApplyRuleRecordSnapshot(
                                        "某品牌冻干猫粮",
                                        BigDecimal.ONE,
                                        "kg",
                                        BigDecimal.valueOf(39.9D),
                                        "include",
                                        "legacy_fallback"
                                ),
                                List.of("命中当前规则排除关键词：冻干，预览从当前规则解绑")
                        ))
                ));

        mockMvc.perform(post("/api/tools/normalization-library")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "recheck_rule_records",
                                  "ruleCode": "cat_food",
                                  "owner": "jtxw",
                                  "batchId": 9001,
                                  "dryRun": true,
                                  "limit": 500
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("recheck_rule_records"))
                .andExpect(jsonPath("$.ruleCode").value("cat_food"))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.items[0].status").value("would_reset"))
                .andExpect(jsonPath("$.items[0].matchedExcludeKeyword").value("冻干"));

        ArgumentCaptor<NormalizationLibraryOperationCommand> captor =
                ArgumentCaptor.forClass(NormalizationLibraryOperationCommand.class);
        verify(normalizationLibraryService).operate(captor.capture());
        NormalizationLibraryOperationCommand command = captor.getValue();
        assertThat(command.action()).isEqualTo("recheck_rule_records");
        assertThat(command.ruleCode()).isEqualTo("cat_food");
        assertThat(command.owner()).isEqualTo("jtxw");
        assertThat(command.batchId()).isEqualTo(9001L);
        assertThat(command.dryRun()).isTrue();
        assertThat(command.limit()).isEqualTo(500);
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
    void applyReviewShouldConfirmNormalizationViaUnifiedEndpoint() throws Exception {
        when(reviewApplicationService.applyNormalization(any(ApplyNormalizationReviewCommand.class)))
                .thenReturn(new ReviewApplyResult(12L, 100L, "confirm_normalization", "include",
                        "resolved", "归一化已确认"));

        mockMvc.perform(post("/api/tools/review-items/12/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "confirm_normalization",
                                  "normalizedName": "沐浴露",
                                  "targetUnit": "L",
                                  "includeInBaseline": true,
                                  "note": "确认"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("confirm_normalization"))
                .andExpect(jsonPath("$.decision").value("include"));

        verify(reviewApplicationService).applyNormalization(any(ApplyNormalizationReviewCommand.class));
    }

    @Test
    void applyReviewShouldIgnoreNormalizationViaUnifiedEndpoint() throws Exception {
        when(reviewApplicationService.applyNormalization(any(ApplyNormalizationReviewCommand.class)))
                .thenReturn(new ReviewApplyResult(13L, 101L, "ignore_normalization", "exclude",
                        "resolved", "归一化复核已忽略"));

        mockMvc.perform(post("/api/tools/review-items/13/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "action": "ignore_normalization",
                                  "note": "暂不处理"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("ignore_normalization"))
                .andExpect(jsonPath("$.status").value("resolved"));

        verify(reviewApplicationService).applyNormalization(any(ApplyNormalizationReviewCommand.class));
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
                        "归一化规则建议任务已创建"));

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
    void applyReviewShouldRejectInvalidAction() throws Exception {
        when(reviewApplicationService.applyNormalization(any(ApplyNormalizationReviewCommand.class)))
                .thenThrow(new IllegalArgumentException("不支持的商品归一化复核动作：learn"));

        mockMvc.perform(post("/api/tools/review-items/15/apply")
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

