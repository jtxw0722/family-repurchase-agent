package com.jtxw.familyagent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorRequest;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorResult;
import com.jtxw.familyagent.domain.model.NormalizationRagContext;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 15:22:31
 * @Description: LLM 商品归一化输出解析器测试，验证 DTO 解析、index 对齐、基础校验和结果级降级。
 */
class LlmNormalizationOutputParserTest {
    /**
     * 测试用输出解析器。
     */
    private final LlmNormalizationOutputParser parser = new LlmNormalizationOutputParser(
            new ObjectMapper(), new LlmNormalizationItemValidator(new NormalizationProperties()));

    @Test
    void shouldParseCompactSchemaByIndex() throws Exception {
        List<NormalizationAdvisorResult> results = parser.parseBatchContent("""
                [
                  {"index":1,"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"猫条","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reviewRequired":false,"reasonCode":"CAT_SNACK"}
                ]
                """, requests("猫条三文鱼口味"));

        assertThat(results.get(0).rawProductName()).isEqualTo("猫条三文鱼口味");
        assertThat(results.get(0).suggestedNormalizedName()).isEqualTo("猫条");
        assertThat(results.get(0).reason()).isEqualTo("猫零食消耗品");
    }

    @Test
    void shouldBackfillCompactSchemaByIndexWhenOutputIsOutOfOrder() throws Exception {
        List<NormalizationAdvisorResult> results = parser.parseBatchContent("""
                [
                  {"index":2,"action":"EXCLUDE","productType":"DURABLE","unitFamily":"UNKNOWN","confidence":0.95,"reviewRequired":false,"reasonCode":"DURABLE_GOODS"},
                  {"index":1,"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"猫条","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reviewRequired":false,"reasonCode":"CAT_SNACK"}
                ]
                """, requests("猫条三文鱼口味", "透明手机壳"));

        assertThat(results.get(0).suggestedNormalizedName()).isEqualTo("猫条");
        assertThat(results.get(1).action()).isEqualTo("EXCLUDE");
        assertThat(results.get(1).productType()).isEqualTo("DURABLE");
    }

    @Test
    void shouldFailCompactRequestWhenIndexIsMissing() throws Exception {
        List<NormalizationAdvisorResult> results = parser.parseBatchContent("""
                [
                  {"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"猫条","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reasonCode":"CAT_SNACK"}
                ]
                """, requests("猫条三文鱼口味"));

        assertThat(results.get(0).failed()).isTrue();
        assertThat(results.get(0).reason()).contains("有效 index");
    }

    @Test
    void shouldNotOverrideUsedRequestWhenCompactIndexIsDuplicated() throws Exception {
        List<NormalizationAdvisorResult> results = parser.parseBatchContent("""
                [
                  {"index":1,"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"猫条","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reasonCode":"CAT_SNACK"},
                  {"index":1,"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"错误归并","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reasonCode":"CAT_SNACK"}
                ]
                """, requests("猫条三文鱼口味", "透明手机壳"));

        assertThat(results.get(0).suggestedNormalizedName()).isEqualTo("猫条");
        assertThat(results.get(1).failed()).isTrue();
    }

    @Test
    void shouldIgnoreOutOfRangeCompactIndexAndFailMissingRequest() throws Exception {
        List<NormalizationAdvisorResult> results = parser.parseBatchContent("""
                [
                  {"index":9,"action":"EXCLUDE","productType":"DURABLE","unitFamily":"UNKNOWN","confidence":0.95,"reasonCode":"DURABLE_GOODS"}
                ]
                """, requests("透明手机壳"));

        assertThat(results.get(0).failed()).isTrue();
        assertThat(results.get(0).reason()).contains("有效 index");
    }

    @Test
    void shouldFailCompactRequestWhenIndexIsNotInteger() throws Exception {
        List<NormalizationAdvisorResult> results = parser.parseBatchContent("""
                [
                  {"index":1.5,"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"猫条","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reasonCode":"CAT_SNACK"}
                ]
                """, requests("猫条三文鱼口味"));

        assertThat(results.get(0).failed()).isTrue();
        assertThat(results.get(0).reason()).contains("有效 index");
    }

    @Test
    void shouldFailPositionWhenCompactItemIsNotObject() throws Exception {
        List<NormalizationAdvisorResult> results = parser.parseBatchContent("""
                [
                  "bad-item",
                  {"index":2,"action":"EXCLUDE","productType":"DURABLE","unitFamily":"UNKNOWN","confidence":0.95,"reasonCode":"DURABLE_GOODS"}
                ]
                """, requests("猫条三文鱼口味", "透明手机壳"));

        assertThat(results.get(0).failed()).isTrue();
        assertThat(results.get(0).reason()).contains("不是 JSON object");
        assertThat(results.get(1).action()).isEqualTo("EXCLUDE");
    }

    @Test
    void shouldParseLegacySchemaSequentially() throws Exception {
        List<NormalizationAdvisorResult> results = parser.parseBatchContent("""
                [
                  {"rawProductName":"猫条三文鱼口味","sku":"默认","action":"NORMALIZE","suggestedNormalizedName":"猫条","productType":"REPURCHASE_CONSUMABLE","targetUnit":"条","unitFamily":"COUNT","confidence":0.95,"reviewRequired":true,"reason":"猫零食"}
                ]
                """, requests("猫条三文鱼口味"));

        assertThat(results.get(0).rawProductName()).isEqualTo("猫条三文鱼口味");
        assertThat(results.get(0).suggestedNormalizedName()).isEqualTo("猫条");
        assertThat(results.get(0).reason()).isEqualTo("猫零食");
    }

    @Test
    void shouldFailMissingLegacySequentialResult() throws Exception {
        List<NormalizationAdvisorResult> results = parser.parseBatchContent("""
                [
                  {"rawProductName":"猫条三文鱼口味","sku":"默认","action":"NORMALIZE","suggestedNormalizedName":"猫条","productType":"REPURCHASE_CONSUMABLE","targetUnit":"条","unitFamily":"COUNT","confidence":0.95,"reviewRequired":true,"reason":"猫零食"}
                ]
                """, requests("猫条三文鱼口味", "透明手机壳"));

        assertThat(results.get(0).failed()).isFalse();
        assertThat(results.get(1).failed()).isTrue();
        assertThat(results.get(1).reason()).contains("输出数量少于请求数量");
    }

    @Test
    void shouldParseSupportedWrappers() throws Exception {
        assertThat(parser.parseBatchContent("{\"results\":" + normalizeArray() + "}", requests("猫条")).get(0).failed())
                .isFalse();
        assertThat(parser.parseBatchContent("{\"items\":" + normalizeArray() + "}", requests("猫条")).get(0).failed())
                .isFalse();
        assertThat(parser.parseBatchContent("{\"suggestions\":" + normalizeArray() + "}", requests("猫条")).get(0).failed())
                .isFalse();
    }

    @Test
    void shouldFallbackIllegalActionToReview() throws Exception {
        NormalizationAdvisorResult result = parser.parseBatchContent("""
                [{"index":1,"action":"BAD","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.8,"reasonCode":"UNKNOWN_REVIEW"}]
                """, requests("未知商品")).get(0);

        assertThat(result.action()).isEqualTo("REVIEW");
    }

    @Test
    void shouldFallbackIllegalProductTypeToUnknown() throws Exception {
        NormalizationAdvisorResult result = parser.parseBatchContent("""
                [{"index":1,"action":"REVIEW","productType":"BAD","unitFamily":"UNKNOWN","confidence":0.8,"reasonCode":"UNKNOWN_REVIEW"}]
                """, requests("未知商品")).get(0);

        assertThat(result.productType()).isEqualTo("UNKNOWN");
    }

    @Test
    void shouldFallbackIllegalUnitFamilyToUnknown() throws Exception {
        NormalizationAdvisorResult result = parser.parseBatchContent("""
                [{"index":1,"action":"REVIEW","productType":"UNKNOWN","unitFamily":"BAD","confidence":0.8,"reasonCode":"UNKNOWN_REVIEW"}]
                """, requests("未知商品")).get(0);

        assertThat(result.unitFamily()).isEqualTo("UNKNOWN");
    }

    @Test
    void shouldFallbackIllegalConfidenceToDefaultValue() throws Exception {
        NormalizationAdvisorResult result = parser.parseBatchContent("""
                [{"index":1,"action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":"bad","reasonCode":"UNKNOWN_REVIEW"}]
                """, requests("未知商品")).get(0);

        assertThat(result.confidence()).isEqualTo(0.5D);
    }

    @Test
    void shouldDowngradeNormalizeWithoutNormalizedNameToReview() throws Exception {
        NormalizationAdvisorResult result = parser.parseBatchContent("""
                [{"index":1,"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","unitFamily":"COUNT","confidence":0.95,"reasonCode":"CAT_SNACK"}]
                """, requests("猫条三文鱼口味")).get(0);

        assertThat(result.action()).isEqualTo("REVIEW");
        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.reason()).contains("NORMALIZE 缺少 suggestedNormalizedName");
    }

    @Test
    void shouldKeepReasonCodeMappingAndShortReasonTruncation() throws Exception {
        NormalizationAdvisorResult mappedResult = parser.parseBatchContent("""
                [{"index":1,"action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.8,"reasonCode":"CAT_SNACK","shortReason":"不应使用"}]
                """, requests("未知商品")).get(0);
        NormalizationAdvisorResult fallbackResult = parser.parseBatchContent("""
                [{"index":1,"action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.8,"reasonCode":"BAD_CODE","shortReason":"这是一段超过二十四个字符的模型短原因用于截断验证"}]
                """, requests("未知商品")).get(0);

        assertThat(mappedResult.reason()).isEqualTo("猫零食消耗品");
        assertThat(fallbackResult.reason()).hasSizeLessThanOrEqualTo(24);
    }

    @Test
    void shouldKeepLegacyReasonTruncation() throws Exception {
        NormalizationAdvisorResult result = parser.parseBatchContent("""
                [{"rawProductName":"未知商品","sku":"默认","action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.8,"reason":"这是一段很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长的旧原因"}]
                """, requests("未知商品")).get(0);

        assertThat(result.reason()).hasSizeLessThanOrEqualTo(80);
    }

    @Test
    void shouldThrowWhenJsonSyntaxIsInvalid() {
        assertThatThrownBy(() -> parser.parseBatchContent("{bad-json", requests("坏 JSON 商品")))
                .isInstanceOf(JsonProcessingException.class);
    }

    private List<NormalizationAdvisorRequest> requests(String... productNames) {
        NormalizationRagContext context = new NormalizationRagContext(List.of(), List.of(), List.of(), List.of());
        return java.util.Arrays.stream(productNames)
                .map(productName -> new NormalizationAdvisorRequest(productName, "默认", "测试分类", "测试子类", context))
                .toList();
    }

    private String normalizeArray() {
        return """
                [
                  {"index":1,"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"猫条","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reviewRequired":false,"reasonCode":"CAT_SNACK"}
                ]
                """;
    }
}
