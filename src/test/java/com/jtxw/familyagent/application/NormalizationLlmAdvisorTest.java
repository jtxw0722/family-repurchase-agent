package com.jtxw.familyagent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorRequest;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorResult;
import com.jtxw.familyagent.domain.model.NormalizationRagContext;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 17:58:26
 * @Description: Normalization LLM Advisor 批量 JSON 解析和分类纠偏测试。
 */
class NormalizationLlmAdvisorTest {
    /**
     * 测试用 Advisor 实例，不启用真实网络调用，仅复用批量 JSON 解析与分类纠偏逻辑。
     */
    private final NormalizationLlmAdvisor advisor = new NormalizationLlmAdvisor(
            new NormalizationProperties(), new ObjectMapper());
    /**
     * 测试用 JSON 组件，用于读取 request body 和构造 mock 响应。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldParseJsonArrayForMultipleProducts() throws Exception {
        List<NormalizationAdvisorResult> results = advisor.parseBatchContent("""
                [
                  {"rawProductName":"猫条三文鱼口味","sku":"默认","action":"NORMALIZE","suggestedNormalizedName":"猫条","productType":"REPURCHASE_CONSUMABLE","targetUnit":"条","unitFamily":"COUNT","confidence":0.95,"reviewRequired":true,"reason":"猫零食","evidence":["猫条"]},
                  {"rawProductName":"手机壳透明款","sku":"默认","action":"EXCLUDE","productType":"DURABLE","unitFamily":"UNKNOWN","confidence":0.96,"reviewRequired":false,"reason":"耐用品","evidence":["手机壳"]}
                ]
                """, requests("猫条三文鱼口味", "手机壳透明款"));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).productType()).isEqualTo("REPURCHASE_CONSUMABLE");
        assertThat(results.get(1).action()).isEqualTo("EXCLUDE");
    }

    @Test
    void shouldParseCompactSchemaAndBackfillRawProductNameAndSku() throws Exception {
        List<NormalizationAdvisorRequest> requests = List.of(
                request("猫条三文鱼口味", "15g*4"),
                request("透明手机壳", "iPhone"));

        List<NormalizationAdvisorResult> results = advisor.parseBatchContent("""
                [
                  {"index":2,"action":"EXCLUDE","productType":"DURABLE","unitFamily":"UNKNOWN","confidence":0.96,"reviewRequired":false,"reasonCode":"DURABLE_GOODS","shortReason":"耐用品"},
                  {"index":1,"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"猫条","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reviewRequired":false,"reasonCode":"CAT_SNACK","shortReason":"猫零食"}
                ]
                """, requests);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).rawProductName()).isEqualTo("猫条三文鱼口味");
        assertThat(results.get(0).sku()).isEqualTo("15g*4");
        assertThat(results.get(0).suggestedNormalizedName()).isEqualTo("猫条");
        assertThat(results.get(0).evidence()).isEmpty();
        assertThat(results.get(1).rawProductName()).isEqualTo("透明手机壳");
        assertThat(results.get(1).sku()).isEqualTo("iPhone");
        assertThat(results.get(1).action()).isEqualTo("EXCLUDE");
    }

    @Test
    void shouldNotMismatchWhenCompactIndexIsMissingDuplicatedOrOutOfRange() throws Exception {
        List<NormalizationAdvisorResult> results = advisor.parseBatchContent("""
                [
                  {"index":1,"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"猫条","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reviewRequired":false,"reasonCode":"CAT_SNACK"},
                  {"index":1,"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"错误归并","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reviewRequired":false,"reasonCode":"CAT_SNACK"},
                  {"index":9,"action":"EXCLUDE","productType":"DURABLE","unitFamily":"UNKNOWN","confidence":0.95,"reviewRequired":false,"reasonCode":"DURABLE_GOODS"},
                  {"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"缺少 index","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reviewRequired":false,"reasonCode":"CAT_SNACK"}
                ]
                """, requests("猫条三文鱼口味", "修护精华液", "透明手机壳"));

        assertThat(results).hasSize(3);
        assertThat(results.get(0).suggestedNormalizedName()).isEqualTo("猫条");
        assertThat(results.get(1).rawProductName()).isEqualTo("修护精华液");
        assertThat(results.get(1).failed()).isTrue();
        assertThat(results.get(1).suggestedNormalizedName()).isNull();
        assertThat(results.get(2).rawProductName()).isEqualTo("透明手机壳");
        assertThat(results.get(2).failed()).isTrue();
    }

    @Test
    void shouldFailCompactResultWithoutIndexInsteadOfSequentialMatching() throws Exception {
        List<NormalizationAdvisorResult> results = advisor.parseBatchContent("""
                [
                  {"action":"NORMALIZE","productType":"REPURCHASE_CONSUMABLE","normalizedName":"猫条","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.95,"reviewRequired":false,"reasonCode":"CAT_SNACK"}
                ]
                """, requests("猫条三文鱼口味"));

        assertThat(results.get(0).failed()).isTrue();
        assertThat(results.get(0).suggestedNormalizedName()).isNull();
    }

    @Test
    void shouldUseReasonCodeMappingWithoutChangingDecisionFields() throws Exception {
        NormalizationAdvisorResult result = advisor.parseBatchContent("""
                [
                  {"index":1,"action":"REVIEW","productType":"UNKNOWN","normalizedName":"模型原样名称","targetUnit":"盒","unitFamily":"COUNT","confidence":0.7,"reviewRequired":true,"reasonCode":"CAT_MAIN_FOOD","shortReason":"不应反推"}
                ]
                """, requests("无明显关键词商品")).get(0);

        assertThat(result.action()).isEqualTo("REVIEW");
        assertThat(result.productType()).isEqualTo("UNKNOWN");
        assertThat(result.suggestedNormalizedName()).isEqualTo("模型原样名称");
        assertThat(result.targetUnit()).isEqualTo("盒");
        assertThat(result.reason()).isEqualTo("猫主食罐消耗品");
    }

    @Test
    void shouldFallbackToTruncatedShortReasonWhenReasonCodeIsInvalid() throws Exception {
        NormalizationAdvisorResult result = advisor.parseBatchContent("""
                [
                  {"index":1,"action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.7,"reviewRequired":true,"reasonCode":"BAD_CODE","shortReason":"这是一段超过二十四个字符的模型短原因用于截断验证"}
                ]
                """, requests("无明显关键词商品")).get(0);

        assertThat(result.reason()).startsWith("这是一段超过二十四个字符");
        assertThat(result.reason()).hasSizeLessThanOrEqualTo(24);
    }

    @Test
    void shouldUseDefaultReasonWhenReasonCodeAndShortReasonAreMissing() throws Exception {
        NormalizationAdvisorResult result = advisor.parseBatchContent("""
                [
                  {"index":1,"action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.7,"reviewRequired":true}
                ]
                """, requests("无明显关键词商品")).get(0);

        assertThat(result.reason()).isEqualTo("需要人工复核");
    }

    @Test
    void shouldTruncateLegacyReasonAndIgnoreEvidence() throws Exception {
        NormalizationAdvisorResult result = advisor.parseBatchContent("""
                [
                  {"rawProductName":"无明显关键词商品","sku":"默认","action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.7,"reviewRequired":true,"reason":"这是一段很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长很长的旧原因","evidence":["不再保存"]}
                ]
                """, requests("无明显关键词商品")).get(0);

        assertThat(result.reason()).hasSizeLessThanOrEqualTo(80);
        assertThat(result.evidence()).isEmpty();
    }

    @Test
    void shouldReadApplicationJsonChatCompletionResponse() throws Exception {
        LlmHttpResult result = analyzeWithMockResponse("application/json",
                chatCompletionResponse(normalizeArrayContent()).getBytes(StandardCharsets.UTF_8));

        assertThat(result.results()).hasSize(1);
        assertThat(result.results().get(0).failed()).isFalse();
        assertThat(result.results().get(0).suggestedNormalizedName()).isEqualTo("猫条");
        assertThat(result.contentType()).contains("application/json");
        assertThat(result.accept()).contains("application/json");
        assertThat(result.requestBody()).doesNotContain("Authorization", "Bearer", "test-key", "sk-");
    }

    @Test
    void shouldReadDirectJsonArrayResponse() throws Exception {
        LlmHttpResult result = analyzeWithMockResponse("application/json",
                normalizeArrayContent().getBytes(StandardCharsets.UTF_8));

        assertThat(result.results().get(0).failed()).isFalse();
        assertThat(result.results().get(0).suggestedNormalizedName()).isEqualTo("猫条");
    }

    @Test
    void shouldReadSuggestionsWrapperResponse() throws Exception {
        String response = """
                {"suggestions": %s}
                """.formatted(normalizeArrayContent());

        LlmHttpResult result = analyzeWithMockResponse("application/json", response.getBytes(StandardCharsets.UTF_8));

        assertThat(result.results().get(0).failed()).isFalse();
        assertThat(result.results().get(0).suggestedNormalizedName()).isEqualTo("猫条");
    }

    @Test
    void shouldReadOctetStreamUtf8JsonResponse() throws Exception {
        LlmHttpResult result = analyzeWithMockResponse("application/octet-stream",
                chatCompletionResponse(normalizeArrayContent()).getBytes(StandardCharsets.UTF_8));

        assertThat(result.results().get(0).failed()).isFalse();
        assertThat(result.results().get(0).suggestedNormalizedName()).isEqualTo("猫条");
    }

    @Test
    void shouldReadTextEventStreamDataJsonResponse() throws Exception {
        String response = "data: " + chatCompletionResponse(normalizeArrayContent()) + "\n\n"
                + "data: [DONE]\n\n";

        LlmHttpResult result = analyzeWithMockResponse("text/event-stream",
                response.getBytes(StandardCharsets.UTF_8));

        assertThat(result.results().get(0).failed()).isFalse();
        assertThat(result.results().get(0).suggestedNormalizedName()).isEqualTo("猫条");
    }

    @Test
    void shouldReadResponsesApiOutputTextResponse() throws Exception {
        String response = new ObjectMapper().writeValueAsString(Map.of("output_text", normalizeArrayContent()));

        LlmHttpResult result = analyzeWithMockResponse("application/json", response.getBytes(StandardCharsets.UTF_8));

        assertThat(result.results().get(0).failed()).isFalse();
        assertThat(result.results().get(0).suggestedNormalizedName()).isEqualTo("猫条");
    }

    @Test
    void shouldReadResponsesApiOutputContentResponse() throws Exception {
        String response = objectMapper.writeValueAsString(Map.of(
                "output", List.of(Map.of("content", List.of(Map.of("type", "output_text",
                        "text", normalizeArrayContent()))))
        ));

        LlmHttpResult result = analyzeWithMockResponse("application/json", response.getBytes(StandardCharsets.UTF_8));

        assertThat(result.results().get(0).failed()).isFalse();
        assertThat(result.results().get(0).suggestedNormalizedName()).isEqualTo("猫条");
    }

    @Test
    void shouldReturnClearFailedResultWhenResponseBodyIsEmpty() throws Exception {
        LlmHttpResult result = analyzeWithMockResponse("application/json", new byte[0]);

        assertThat(result.results().get(0).failed()).isTrue();
        assertThat(result.results().get(0).reason())
                .contains("empty_response")
                .contains("LLM 返回空响应");
    }

    @Test
    void shouldReturnHttpErrorBodyWhenStatusIsNot2xx() throws Exception {
        LlmHttpResult result = analyzeWithMockResponse("application/octet-stream",
                "{\"error\":\"quota exceeded\"}".getBytes(StandardCharsets.UTF_8), 429);

        assertThat(result.results().get(0).failed()).isTrue();
        assertThat(result.results().get(0).reason()).contains("http_error");
        assertThat(result.results().get(0).reason()).contains("LLM HTTP 响应异常：429");
        assertThat(result.results().get(0).reason()).contains("quota exceeded");
    }

    @Test
    void shouldParseFencedJsonContent() throws Exception {
        String response = chatCompletionResponse("""
                ```json
                %s
                ```
                """.formatted(normalizeArrayContent()));

        LlmHttpResult result = analyzeWithMockResponse("application/json", response.getBytes(StandardCharsets.UTF_8));

        assertThat(result.results().get(0).failed()).isFalse();
        assertThat(result.results().get(0).suggestedNormalizedName()).isEqualTo("猫条");
    }

    @Test
    void shouldDegradeToFailedResultWhenResponseJsonIsInvalid() throws Exception {
        LlmHttpResult result = analyzeWithMockResponse("text/plain",
                "not-json".getBytes(StandardCharsets.UTF_8));

        assertThat(result.results().get(0).failed()).isTrue();
        assertThat(result.results().get(0).reason()).contains("json_parse_error");
    }

    @Test
    void shouldRejectInvalidJsonArray() {
        assertThatThrownBy(() -> advisor.parseBatchContent("{bad-json", requests("坏 JSON 商品")))
                .isInstanceOf(Exception.class);
    }

    @Test
    void beautyConsumableShouldNotBeDurable() throws Exception {
        List<NormalizationAdvisorResult> results = advisor.parseBatchContent("""
                [
                  {"rawProductName":"修护精华液","sku":"30ml","action":"EXCLUDE","productType":"DURABLE","unitFamily":"VOLUME","confidence":0.91,"reviewRequired":false,"reason":"误判耐用品","evidence":["精华液"]}
                  ,{"rawProductName":"保湿爽肤水","sku":"150ml","action":"EXCLUDE","productType":"DURABLE","unitFamily":"VOLUME","confidence":0.91,"reviewRequired":false,"reason":"误判耐用品","evidence":["爽肤水"]}
                  ,{"rawProductName":"修护乳液","sku":"100ml","action":"EXCLUDE","productType":"DURABLE","unitFamily":"VOLUME","confidence":0.91,"reviewRequired":false,"reason":"误判耐用品","evidence":["乳液"]}
                  ,{"rawProductName":"美瞳日抛","sku":"10片","action":"EXCLUDE","productType":"DURABLE","unitFamily":"COUNT","confidence":0.91,"reviewRequired":false,"reason":"误判耐用品","evidence":["美瞳"]}
                ]
                """, requests("修护精华液", "保湿爽肤水", "修护乳液", "美瞳日抛"));

        assertThat(results)
                .allSatisfy(result -> {
                    assertThat(result.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
                    assertThat(result.action()).isEqualTo("REVIEW");
                });
    }

    @Test
    void colorMakeupShouldBeReviewConsumableInsteadOfDurable() throws Exception {
        List<NormalizationAdvisorResult> results = advisor.parseBatchContent("""
                [
                  {"rawProductName":"持妆粉底液","sku":"1C0","action":"NORMALIZE","suggestedNormalizedName":"粉底液","productType":"DURABLE","unitFamily":"VOLUME","confidence":0.96,"reviewRequired":false,"reason":"误判耐用品","evidence":["粉底液"]},
                  {"rawProductName":"自然眉笔","sku":"灰棕色","action":"NORMALIZE","suggestedNormalizedName":"眉笔","productType":"DURABLE","unitFamily":"COUNT","confidence":0.96,"reviewRequired":false,"reason":"误判耐用品","evidence":["眉笔"]},
                  {"rawProductName":"哑光口红","sku":"豆沙色","action":"NORMALIZE","suggestedNormalizedName":"口红","productType":"DURABLE","unitFamily":"COUNT","confidence":0.96,"reviewRequired":false,"reason":"误判耐用品","evidence":["口红"]}
                ]
                """, requests("持妆粉底液", "自然眉笔", "哑光口红"));

        assertThat(results)
                .allSatisfy(result -> {
                    assertThat(result.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
                    assertThat(result.action()).isEqualTo("REVIEW");
                    assertThat(result.reviewRequired()).isTrue();
                });
    }

    @Test
    void catCanShouldBeRepurchaseConsumableAndNotExclude() throws Exception {
        NormalizationAdvisorResult result = advisor.parseBatchContent("""
                [
                  {"rawProductName":"猫主食罐鸡肉味","sku":"85g*6","action":"EXCLUDE","productType":"DURABLE","unitFamily":"COUNT","confidence":0.92,"reviewRequired":false,"reason":"误判","evidence":["主食罐"]}
                ]
                """, requests("猫主食罐鸡肉味")).get(0);

        assertThat(result.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
        assertThat(result.action()).isEqualTo("REVIEW");
    }

    @Test
    void humanFoodShouldNotBeDurable() throws Exception {
        NormalizationAdvisorResult result = advisor.parseBatchContent("""
                [
                  {"rawProductName":"卡尔顿蛋皮吐司面包早餐整箱","sku":"组合装","action":"EXCLUDE","productType":"DURABLE","unitFamily":"COUNT","confidence":0.92,"reviewRequired":false,"reason":"误判耐用品","evidence":["整箱"]}
                ]
                """, requests("卡尔顿蛋皮吐司面包早餐整箱")).get(0);

        assertThat(result.productType()).isEqualTo("UNKNOWN");
        assertThat(result.action()).isEqualTo("REVIEW");
        assertThat(result.reviewRequired()).isTrue();
    }

    @Test
    void packagingWordsAloneShouldNotTriggerDurable() throws Exception {
        NormalizationAdvisorResult result = advisor.parseBatchContent("""
                [
                  {"rawProductName":"组合装整箱盒装袋装套装","sku":"默认","action":"EXCLUDE","productType":"DURABLE","unitFamily":"COUNT","confidence":0.92,"reviewRequired":false,"reason":"误判包装为耐用品","evidence":["组合装"]}
                ]
                """, requests("组合装整箱盒装袋装套装")).get(0);

        assertThat(result.productType()).isEqualTo("UNKNOWN");
        assertThat(result.action()).isEqualTo("REVIEW");
    }

    @Test
    void couponAndDepositShouldBeExcluded() throws Exception {
        NormalizationAdvisorResult result = advisor.parseBatchContent("""
                [
                  {"rawProductName":"618预售券定金锁定权益","sku":"默认","action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.94,"reviewRequired":true,"reason":"权益类","evidence":["预售券"]}
                ]
                """, requests("618预售券定金锁定权益")).get(0);

        assertThat(result.productType()).isEqualTo("COUPON_OR_DEPOSIT");
        assertThat(result.action()).isEqualTo("EXCLUDE");
    }

    @Test
    void purePaymentRightsShouldRemainCouponOrDepositExcluded() throws Exception {
        List<NormalizationAdvisorResult> results = advisor.parseBatchContent("""
                [
                  {"rawProductName":"0.01元锁定30元","sku":"默认","action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.94,"reviewRequired":true,"reason":"锁定权益","evidence":["锁定"]},
                  {"rawProductName":"1元预定礼","sku":"默认","action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.94,"reviewRequired":true,"reason":"预定礼","evidence":["预定礼"]}
                ]
                """, requests("0.01元锁定30元", "1元预定礼"));

        assertThat(results)
                .allSatisfy(result -> {
                    assertThat(result.productType()).isEqualTo("COUPON_OR_DEPOSIT");
                    assertThat(result.action()).isEqualTo("EXCLUDE");
                    assertThat(result.reviewRequired()).isFalse();
                });
    }

    @Test
    void realProductsWithPresaleOrDepositShouldBeReviewConsumable() throws Exception {
        List<NormalizationAdvisorResult> results = advisor.parseBatchContent("""
                [
                  {"rawProductName":"【双11预售立即付定】尾巴生活彩虹泥主食餐盒一餐一杯猫罐头","sku":"默认","action":"EXCLUDE","suggestedNormalizedName":"猫主食罐","productType":"COUPON_OR_DEPOSIT","targetUnit":"罐","unitFamily":"COUNT","confidence":0.96,"reviewRequired":false,"reason":"标题含预售付定","evidence":["预售","猫罐头"]},
                  {"rawProductName":"【双11预售】地狱厨房咕噜酱猫零食营养补水增肥酱包","sku":"默认","action":"EXCLUDE","suggestedNormalizedName":"猫零食","productType":"COUPON_OR_DEPOSIT","targetUnit":"g","unitFamily":"WEIGHT","confidence":0.96,"reviewRequired":false,"reason":"标题含预售","evidence":["预售","猫零食"]}
                ]
                """, requests("【双11预售立即付定】尾巴生活彩虹泥主食餐盒一餐一杯猫罐头",
                "【双11预售】地狱厨房咕噜酱猫零食营养补水增肥酱包"));

        assertThat(results)
                .allSatisfy(result -> {
                    assertThat(result.productType()).isEqualTo("REPURCHASE_CONSUMABLE");
                    assertThat(result.action()).isEqualTo("REVIEW");
                    assertThat(result.reviewRequired()).isTrue();
                    assertThat(result.reason()).contains("需人工确认是否为定金订单");
                });
        assertThat(results.get(0).suggestedNormalizedName()).isEqualTo("猫主食罐");
        assertThat(results.get(1).suggestedNormalizedName()).isEqualTo("猫零食");
    }

    @Test
    void durableProductsShouldBeExcluded() throws Exception {
        List<NormalizationAdvisorResult> results = advisor.parseBatchContent("""
                [
                  {"rawProductName":"透明手机壳","sku":"默认","action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.9,"reviewRequired":true,"reason":"不确定","evidence":["手机壳"]},
                  {"rawProductName":"通勤包","sku":"默认","action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.9,"reviewRequired":true,"reason":"不确定","evidence":["包"]},
                  {"rawProductName":"运动鞋","sku":"默认","action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.9,"reviewRequired":true,"reason":"不确定","evidence":["鞋"]},
                  {"rawProductName":"珍珠耳环饰品","sku":"默认","action":"REVIEW","productType":"UNKNOWN","unitFamily":"UNKNOWN","confidence":0.9,"reviewRequired":true,"reason":"不确定","evidence":["饰品"]}
                ]
                """, requests("透明手机壳", "通勤包", "运动鞋", "珍珠耳环饰品"));

        assertThat(results)
                .allSatisfy(result -> {
                    assertThat(result.productType()).isEqualTo("DURABLE");
                    assertThat(result.action()).isEqualTo("EXCLUDE");
                });
    }

    @Test
    void shouldBuildCompressedPromptWithoutRepeatedItemHints() throws Exception {
        NormalizationProperties properties = new NormalizationProperties();
        NormalizationLlmAdvisor promptAdvisor = new NormalizationLlmAdvisor(properties, objectMapper);
        NormalizationRagContext context = new NormalizationRagContext(
                List.of("正向别名：尾巴生活主食餐盒 => 猫主食罐，targetUnit=g"),
                List.of("负向别名：猫粮勺，拒绝品类=猫粮，reason=耐用品"),
                List.of("规则：cat_main_can，normalizedName=猫主食罐，standardUnit=g，unitFamily=WEIGHT，includeKeywords=[主食罐]"),
                List.of("这类长 categoryHints 不应在每个 item 中重复"));
        List<NormalizationAdvisorRequest> requests = List.of(
                new NormalizationAdvisorRequest("尾巴生活主食餐盒", "80g*6", "宠物", "猫罐头", context),
                new NormalizationAdvisorRequest("地狱厨房咕噜酱", "40g*7", "宠物", "猫零食", context));

        JsonNode requestBody = objectMapper.readTree(promptAdvisor.requestMetrics(requests).requestBody());
        String userPrompt = requestBody.path("messages").path(1).path("content").asText();
        JsonNode promptInput = objectMapper.readTree(userPrompt.substring(userPrompt.indexOf('：') + 1));

        assertThat(requestBody.toString()).doesNotContain("rawProductName", "productName", "ragEvidence",
                "rejectedNormalizedName");
        assertThat(promptInput.path("context").path("hints").isObject()).isTrue();
        assertThat(promptInput.path("context").path("rules")).hasSize(1);
        assertThat(promptInput.path("items")).hasSize(2);
        assertThat(promptInput.path("items").path(0).has("categoryHints")).isFalse();
        assertThat(promptInput.path("items").path(0).has("positiveAliases")).isTrue();
        assertThat(promptInput.path("items").path(0).path("positiveAliases").path(0).asText())
                .isEqualTo("尾巴生活主食餐盒=>猫主食罐/g");
        assertThat(promptInput.path("items").path(0).path("negativeAliases").path(0).asText())
                .isEqualTo("猫粮勺!=>猫粮");
    }

    @Test
    void shouldWriteMaxTokensAndMergeExtraBodyJson() throws Exception {
        NormalizationProperties properties = new NormalizationProperties();
        properties.getLlm().setMaxTokens(321);
        properties.getLlm().setExtraBodyJson("{\"enable_thinking\":false,\"temperature\":0}");
        NormalizationLlmAdvisor requestAdvisor = new NormalizationLlmAdvisor(properties, objectMapper);

        JsonNode requestBody = objectMapper.readTree(requestAdvisor.requestMetrics(requests("猫条三文鱼口味")).requestBody());

        assertThat(requestBody.path("max_tokens").asInt()).isEqualTo(321);
        assertThat(requestBody.path("enable_thinking").asBoolean()).isFalse();
        assertThat(requestBody.path("temperature").asInt()).isZero();
    }

    @Test
    void shouldNotWriteMaxTokensWhenMaxTokensIsZero() throws Exception {
        NormalizationProperties properties = new NormalizationProperties();
        properties.getLlm().setMaxTokens(0);
        properties.getLlm().setExtraBodyJson("{\"enable_thinking\":false}");
        NormalizationLlmAdvisor requestAdvisor = new NormalizationLlmAdvisor(properties, objectMapper);

        JsonNode requestBody = objectMapper.readTree(requestAdvisor.requestMetrics(requests("猫条三文鱼口味")).requestBody());

        assertThat(requestBody.has("max_tokens")).isFalse();
        assertThat(requestBody.path("enable_thinking").asBoolean()).isFalse();
    }

    @Test
    void shouldAllowNullEmptyAndBlankExtraBodyJsonWithoutAppendingFields() throws Exception {
        assertExtraBodyJsonIsIgnored(null);
        assertExtraBodyJsonIsIgnored("");
        assertExtraBodyJsonIsIgnored("   ");
    }

    @Test
    void shouldNotRequireThinkingDisabledWhenMimoModelUsesEmptyExtraBodyJson() throws Exception {
        NormalizationProperties properties = new NormalizationProperties();
        properties.getLlm().setModel("mimo-v2.5-pro");
        properties.getLlm().setMaxTokens(0);
        properties.getLlm().setExtraBodyJson("");
        NormalizationLlmAdvisor requestAdvisor = new NormalizationLlmAdvisor(properties, objectMapper);

        JsonNode requestBody = objectMapper.readTree(requestAdvisor.requestMetrics(requests("猫条三文鱼口味")).requestBody());

        assertThat(requestBody.path("model").asText()).isEqualTo("mimo-v2.5-pro");
        assertThat(requestBody.has("thinking")).isFalse();
        assertThat(requestBody.has("max_completion_tokens")).isFalse();
        assertThat(requestBody.has("max_tokens")).isFalse();
    }

    @Test
    void shouldMergeMimoExtraBodyJsonWithoutMaxTokensWhenMaxTokensIsZero() throws Exception {
        NormalizationProperties properties = new NormalizationProperties();
        properties.getLlm().setMaxTokens(0);
        properties.getLlm().setExtraBodyJson("""
                {"max_completion_tokens":1024,"temperature":1.0,"top_p":0.95,"thinking":{"type":"disabled"}}
                """);
        NormalizationLlmAdvisor requestAdvisor = new NormalizationLlmAdvisor(properties, objectMapper);

        JsonNode requestBody = objectMapper.readTree(requestAdvisor.requestMetrics(requests("猫条三文鱼口味")).requestBody());

        assertThat(requestBody.has("max_tokens")).isFalse();
        assertThat(requestBody.path("max_completion_tokens").asInt()).isEqualTo(1024);
        assertThat(requestBody.path("temperature").asDouble()).isEqualTo(1.0D);
        assertThat(requestBody.path("top_p").asDouble()).isEqualTo(0.95D);
        assertThat(requestBody.path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    @Test
    void shouldRejectExtraBodyJsonWhenItOverridesCoreFields() {
        NormalizationProperties properties = new NormalizationProperties();
        for (String fieldName : List.of("model", "messages", "stream")) {
            properties.getLlm().setExtraBodyJson("{\"" + fieldName + "\":\"override\"}");
            NormalizationLlmAdvisor requestAdvisor = new NormalizationLlmAdvisor(properties, objectMapper);

            assertThatThrownBy(() -> requestAdvisor.requestMetrics(requests("猫条三文鱼口味")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能覆盖核心字段：" + fieldName);
        }
    }

    @Test
    void shouldRejectInvalidExtraBodyJsonWithClearMessage() {
        NormalizationProperties properties = new NormalizationProperties();
        properties.getLlm().setExtraBodyJson("{bad-json");
        NormalizationLlmAdvisor requestAdvisor = new NormalizationLlmAdvisor(properties, objectMapper);

        assertThatThrownBy(() -> requestAdvisor.requestMetrics(requests("猫条三文鱼口味")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NORMALIZATION_LLM_EXTRA_BODY_JSON 不是合法 JSON object");
    }

    @Test
    void shouldRejectSensitiveExtraBodyJsonBeforeDebugDumpCanRecordIt() {
        NormalizationProperties properties = new NormalizationProperties();
        List<String> sensitiveFields = List.of("authorization", "api_key", "apikey", "api-key",
                "access_token", "refresh_token", "id_token", "secret");
        for (String fieldName : sensitiveFields) {
            properties.getLlm().setExtraBodyJson("{\"" + fieldName + "\":\"value\"}");
            NormalizationLlmAdvisor requestAdvisor = new NormalizationLlmAdvisor(properties, objectMapper);

            assertThatThrownBy(() -> requestAdvisor.requestMetrics(requests("猫条三文鱼口味")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能包含密钥或鉴权字段");
        }
        for (String sensitiveValue : List.of("Bearer test-token", "sk-test")) {
            properties.getLlm().setExtraBodyJson("{\"provider_option\":\"" + sensitiveValue + "\"}");
            NormalizationLlmAdvisor requestAdvisor = new NormalizationLlmAdvisor(properties, objectMapper);

            assertThatThrownBy(() -> requestAdvisor.requestMetrics(requests("猫条三文鱼口味")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能包含密钥或鉴权值");
        }
    }

    private void assertExtraBodyJsonIsIgnored(String extraBodyJson) throws Exception {
        NormalizationProperties properties = new NormalizationProperties();
        properties.getLlm().setMaxTokens(0);
        properties.getLlm().setExtraBodyJson(extraBodyJson);
        NormalizationLlmAdvisor requestAdvisor = new NormalizationLlmAdvisor(properties, objectMapper);

        JsonNode requestBody = objectMapper.readTree(requestAdvisor.requestMetrics(requests("猫条三文鱼口味")).requestBody());

        assertThat(requestBody.has("model")).isTrue();
        assertThat(requestBody.has("messages")).isTrue();
        assertThat(requestBody.has("max_tokens")).isFalse();
        assertThat(requestBody.size()).isEqualTo(2);
    }

    @Test
    void shouldMeasureHttpElapsedWhenReadTimeoutHappens() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(1500L);
                byte[] responseBody = "{\"choices\":[{\"message\":{\"content\":\"[]\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBody.length);
                exchange.getResponseBody().write(responseBody);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            NormalizationProperties properties = new NormalizationProperties();
            properties.getLlm().setEnabled(true);
            properties.getLlm().setApiKey("test-key");
            properties.getLlm().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getLlm().setRequestTimeoutSeconds(1);
            NormalizationLlmAdvisor timeoutAdvisor = new NormalizationLlmAdvisor(properties, objectMapper);

            NormalizationLlmAdvisor.LlmBatchAnalysis analysis =
                    timeoutAdvisor.analyzeBatchWithObservation(requests("猫条三文鱼口味"));

            assertThat(analysis.results().get(0).failed()).isTrue();
            assertThat(analysis.observation().errorType()).isEqualTo("timeout_error");
            assertThat(analysis.observation().llmHttpElapsedMs()).isGreaterThan(0L);
            assertThat(analysis.observation().requestBuildElapsedMs())
                    .isLessThan(analysis.observation().llmHttpElapsedMs());
            assertThat(analysis.observation().totalElapsedMs())
                    .isGreaterThanOrEqualTo(analysis.observation().llmHttpElapsedMs());
        } finally {
            server.stop(0);
        }
    }

    private List<NormalizationAdvisorRequest> requests(String... productNames) {
        NormalizationRagContext context = new NormalizationRagContext(List.of(), List.of(), List.of(), List.of());
        return java.util.Arrays.stream(productNames)
                .map(productName -> new NormalizationAdvisorRequest(productName, "默认", "测试分类", "测试子类", context))
                .toList();
    }

    private NormalizationAdvisorRequest request(String productName, String sku) {
        NormalizationRagContext context = new NormalizationRagContext(List.of(), List.of(), List.of(), List.of());
        return new NormalizationAdvisorRequest(productName, sku, "测试分类", "测试子类", context);
    }

    private LlmHttpResult analyzeWithMockResponse(String responseContentType, byte[] responseBody) throws Exception {
        return analyzeWithMockResponse(responseContentType, responseBody, 200);
    }

    private LlmHttpResult analyzeWithMockResponse(String responseContentType,
                                                  byte[] responseBody,
                                                  int statusCode) throws Exception {
        AtomicReference<String> requestContentType = new AtomicReference<>("");
        AtomicReference<String> requestAccept = new AtomicReference<>("");
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", responseContentType);
            exchange.sendResponseHeaders(statusCode, responseBody.length);
            try (exchange; var response = exchange.getResponseBody()) {
                response.write(responseBody);
            }
        });
        server.start();
        try {
            NormalizationProperties properties = new NormalizationProperties();
            properties.getLlm().setEnabled(true);
            properties.getLlm().setApiKey("test-key");
            properties.getLlm().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            NormalizationLlmAdvisor httpAdvisor = new NormalizationLlmAdvisor(properties, new ObjectMapper());
            List<NormalizationAdvisorResult> results = httpAdvisor.analyzeBatch(requests("猫条三文鱼口味"));
            return new LlmHttpResult(results, requestContentType.get(), requestAccept.get(), requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    private String chatCompletionResponse(String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("content", content)))
        ));
    }

    private String normalizeArrayContent() {
        return """
                [
                  {"rawProductName":"猫条三文鱼口味","sku":"默认","action":"NORMALIZE","suggestedNormalizedName":"猫条","productType":"REPURCHASE_CONSUMABLE","targetUnit":"条","unitFamily":"COUNT","confidence":0.95,"reviewRequired":true,"reason":"猫零食","evidence":["猫条"]}
                ]
                """;
    }

    private record LlmHttpResult(List<NormalizationAdvisorResult> results,
                                 String contentType,
                                 String accept,
                                 String requestBody) {
    }
}
