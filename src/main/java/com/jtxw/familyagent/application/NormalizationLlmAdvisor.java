package com.jtxw.familyagent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorResult;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorRequest;
import com.jtxw.familyagent.domain.model.NormalizationRagContext;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 17:58:26
 * @Description: 商品归一化 LLM Advisor，负责批量调用 LLM 并校验结构化建议结果。
 */
@Service
public class NormalizationLlmAdvisor {
    /**
     * LLM 允许返回的动作集合，其他值会被兜底为 REVIEW。
     */
    private static final Set<String> ALLOWED_ACTIONS = Set.of("NORMALIZE", "EXCLUDE", "NEW_CATEGORY", "REVIEW");
    /**
     * LLM 允许返回的商品类型集合，其他值会被兜底为 UNKNOWN。
     */
    private static final Set<String> ALLOWED_PRODUCT_TYPES = Set.of(
            "REPURCHASE_CONSUMABLE", "NON_REPURCHASE", "DURABLE", "COUPON_OR_DEPOSIT", "UNKNOWN");
    /**
     * LLM 允许返回的单位族集合，其他值会被兜底为 UNKNOWN。
     */
    private static final Set<String> ALLOWED_UNIT_FAMILIES = Set.of("WEIGHT", "VOLUME", "COUNT", "PIECE", "UNKNOWN");
    /**
     * OpenAI 兼容 Chat Completions endpoint 路径，baseUrl 由配置提供。
     */
    private static final String OPENAI_CHAT_COMPLETIONS_PATH = "/chat/completions";
    /**
     * 宠物食品关键词，用于防止猫食品被误判为耐用品或非复购品。
     */
    private static final List<String> CAT_FOOD_KEYWORDS = List.of("猫主食罐", "主食罐", "猫罐头", "湿粮", "餐盒",
            "一餐一杯", "奶猫罐", "幼猫罐", "成猫罐", "猫条", "猫汤包", "咕噜酱", "补水零食", "猫零食", "猫咪零食",
            "猫粮", "全价猫粮", "主粮", "干粮", "烘焙粮", "冻干主粮", "生骨肉主粮", "冻干");
    /**
     * 普通人类食品关键词，用于防止面包、早餐、零食等被包装词或“包”误判为 DURABLE。
     */
    private static final List<String> HUMAN_FOOD_KEYWORDS = List.of("食品", "零食", "面包", "吐司", "早餐",
            "蛋糕", "饼干", "巧克力", "饮料", "牛奶", "酸奶", "咖啡", "茶饮", "麦片", "燕麦", "坚果",
            "糖果", "果汁", "可乐", "薯片", "辣条", "火腿", "香肠", "泡面", "方便面", "包子");
    /**
     * 包装规格词，本身不代表商品是长期耐用品。
     */
    private static final List<String> PACKAGING_KEYWORDS = List.of("包装", "组合装", "整箱", "套装", "盒装",
            "袋装", "罐装", "瓶装");
    /**
     * 美妆护肤消耗品关键词，用于兼容上一版分类纠偏测试和基础护肤品识别。
     */
    private static final List<String> BEAUTY_CONSUMABLE_KEYWORDS = List.of("精华液", "防晒", "粉底液", "洗面奶",
            "面霜", "卸妆", "面膜");
    /**
     * 个人长期消耗品关键词，用于禁止 LLM 将美瞳、护肤品等误标为 DURABLE。
     */
    private static final List<String> PERSONAL_CARE_KEYWORDS = List.of("美瞳", "隐形眼镜", "日抛", "月抛", "彩片",
            "精华液", "精华", "兰花油", "爽肤水", "化妆水", "乳液", "面霜", "防晒", "洗面奶", "洁面",
            "卸妆", "面膜");
    /**
     * 色号强相关彩妆关键词，第一阶段只能进入 REVIEW，避免不同色号混入同一价格基准。
     */
    private static final List<String> COLOR_MAKEUP_KEYWORDS = List.of("粉底液", "遮瑕", "口红", "唇釉", "眉笔",
            "眼影", "腮红", "眼线笔");
    /**
     * 券、定金和权益类关键词；仅在没有明确商品本体时才可直接排除。
     */
    private static final List<String> COUPON_OR_DEPOSIT_KEYWORDS = List.of("预售", "预定", "预售券", "定金", "付定",
            "锁定", "加赠", "优惠券", "预定礼", "服务券");
    /**
     * 长期使用耐用品关键词，用于兜底排除明显不适合作为消耗品价格基准的商品。
     */
    private static final List<String> DURABLE_KEYWORDS = List.of("手机壳", "衣服", "T恤", "裤子", "外套", "内裤",
            "文胸", "包", "鞋", "饰品", "耳环", "项链", "戒指", "相机配件", "茶具", "猫砂盆", "储粮桶", "猫粮勺");

    /**
     * 归一化配置，包含 LLM 开关、模型、baseUrl、超时和阈值等。
     */
    private final NormalizationProperties normalizationProperties;
    /**
     * JSON 序列化和 LLM 响应解析组件。
     */
    private final ObjectMapper objectMapper;

    public NormalizationLlmAdvisor(NormalizationProperties normalizationProperties, ObjectMapper objectMapper) {
        this.normalizationProperties = normalizationProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 LLM 分析单个 legacy_fallback 商品。
     *
     * <p>传入 LLM 的字段严格限制为商品名、SKU、电商分类和 RAG 证据，
     * 不包含 owner、时间、金额、店铺、来源文件或 sourceText。</p>
     *
     * @param productName 原始商品名称
     * @param sku         商品规格或 SKU
     * @param category    电商一级分类
     * @param subCategory 电商二级分类
     * @param context     本地轻量 RAG 证据
     * @return 结构化归一化建议；单条失败时返回 failed=true 的兜底结果
     */
    public NormalizationAdvisorResult analyze(String productName,
                                              String sku,
                                              String category,
                                              String subCategory,
                                              NormalizationRagContext context) {
        List<NormalizationAdvisorResult> results = analyzeBatch(List.of(new NormalizationAdvisorRequest(
                productName, sku, category, subCategory, context)));
        return results.get(0);
    }

    /**
     * 批量调用 LLM 分析多个 legacy_fallback 商品。
     *
     * <p>一次请求返回 JSON Array，结果按输入顺序回填；若整个批次调用或解析失败，
     * 会为批次内每个商品生成 failed=true 的兜底结果，不影响其他批次继续分析。</p>
     *
     * @param requests 待分析商品列表，只包含允许传给 LLM 的隐私安全字段
     * @return 与输入顺序一致的结构化归一化建议列表
     */
    public List<NormalizationAdvisorResult> analyzeBatch(List<NormalizationAdvisorRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        NormalizationProperties.Llm llm = normalizationProperties.getLlm();
        if (!llm.isEnabled()) {
            throw new IllegalStateException("LLM normalization advisor 未启用");
        }
        if (!isOpenAiCompatibleProvider(llm.getProvider())) {
            throw new IllegalStateException("暂不支持的 LLM provider：" + llm.getProvider());
        }
        if (llm.getApiKey() == null || llm.getApiKey().isBlank()) {
            throw new IllegalStateException("LLM normalization advisor 缺少 API Key");
        }
        if (llm.getBaseUrl() == null || llm.getBaseUrl().isBlank()) {
            throw new IllegalStateException("LLM normalization advisor 缺少 baseUrl");
        }
        try {
            String content = callOpenAi(requests, llm);
            return parseBatchContent(content, requests);
        } catch (Exception e) {
            return requests.stream()
                    .map(request -> failedResult(request.productName(), request.sku(),
                            classifyException(e)))
                    .toList();
        }
    }

    private String callOpenAi(List<NormalizationAdvisorRequest> requests,
                              NormalizationProperties.Llm llm) throws JsonProcessingException {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = Math.max(1, llm.getRequestTimeoutSeconds()) * 1000;
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        Map<String, Object> request = Map.of(
                "model", llm.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt()),
                        Map.of("role", "user", "content", userPrompt(requests))
                )
        );
        String response = restClient.post()
                .uri(endpointUrl(llm.getBaseUrl(), OPENAI_CHAT_COMPLETIONS_PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + llm.getApiKey())
                .body(request)
                .exchange((clientRequest, clientResponse) -> {
                    byte[] responseBody = clientResponse.getBody().readAllBytes();
                    String responseText = new String(responseBody, StandardCharsets.UTF_8);
                    if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("LLM HTTP 响应异常："
                                + clientResponse.getStatusCode().value() + "；响应：" + abbreviate(responseText));
                    }
                    return responseText(responseBody);
                });
        return extractModelOutput(response);
    }

    private String responseText(byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            throw new IllegalStateException("LLM 返回空响应");
        }
        String responseText = new String(responseBody, StandardCharsets.UTF_8);
        if (responseText.isBlank()) {
            throw new IllegalStateException("LLM 返回空响应");
        }
        return responseText;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String normalizedText = text.replaceAll("\\s+", " ").trim();
        int maxLength = 500;
        return normalizedText.length() <= maxLength ? normalizedText : normalizedText.substring(0, maxLength) + "...";
    }

    /**
     * 从 OpenAI-compatible 原始响应中提取模型输出文本。
     *
     * <p>兼容 Chat Completions、Responses API、SSE data 行，以及直接返回 JSON Array 的代理服务。</p>
     *
     * @param responseText HTTP 原始响应文本，已按 UTF-8 解码
     * @return 模型输出的 JSON Array 文本
     * @throws JsonProcessingException 响应 JSON 结构非法时抛出，由批量入口降级为 failed suggestion
     */
    String extractModelOutput(String responseText) throws JsonProcessingException {
        if (responseText == null || responseText.isBlank()) {
            throw new IllegalStateException("LLM 返回空响应");
        }
        String trimmedResponse = stripJsonFence(responseText.trim());
        if (hasSseData(trimmedResponse)) {
            return extractSseModelOutput(trimmedResponse);
        }
        JsonNode root = objectMapper.readTree(trimmedResponse);
        if (isDirectModelOutput(root)) {
            return trimmedResponse;
        }
        String chatContent = chatCompletionContent(root);
        if (!chatContent.isBlank()) {
            return chatContent;
        }
        String responsesContent = responsesApiContent(root);
        if (!responsesContent.isBlank()) {
            return responsesContent;
        }
        throw new IllegalStateException("LLM 响应缺少模型输出内容");
    }

    private String stripJsonFence(String content) {
        String trimmedContent = content.trim();
        if (!trimmedContent.startsWith("```")) {
            return trimmedContent;
        }
        String withoutOpeningFence = trimmedContent.replaceFirst("^```[a-zA-Z]*\\s*", "");
        return withoutOpeningFence.replaceFirst("\\s*```$", "").trim();
    }

    private boolean hasSseData(String responseText) {
        for (String line : responseText.split("\\R")) {
            if (line.trim().startsWith("data:")) {
                return true;
            }
        }
        return false;
    }

    private String extractSseModelOutput(String responseText) throws JsonProcessingException {
        StringBuilder streamContent = new StringBuilder();
        String lastFullContent = "";
        for (String line : responseText.split("\\R")) {
            String trimmedLine = line.trim();
            if (!trimmedLine.startsWith("data:")) {
                continue;
            }
            String data = trimmedLine.substring("data:".length()).trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }
            data = stripJsonFence(data);
            JsonNode root = objectMapper.readTree(data);
            if (isDirectModelOutput(root)) {
                lastFullContent = data;
                continue;
            }
            String chatContent = chatCompletionContent(root);
            if (!chatContent.isBlank()) {
                lastFullContent = chatContent;
                continue;
            }
            String deltaContent = chatCompletionDeltaContent(root);
            if (!deltaContent.isBlank()) {
                streamContent.append(deltaContent);
                continue;
            }
            String responsesContent = responsesApiContent(root);
            if (!responsesContent.isBlank()) {
                lastFullContent = responsesContent;
            }
        }
        if (!streamContent.isEmpty()) {
            return streamContent.toString();
        }
        if (!lastFullContent.isBlank()) {
            return lastFullContent;
        }
        throw new IllegalStateException("LLM SSE 响应缺少 data JSON 内容");
    }

    private boolean isDirectModelOutput(JsonNode root) {
        return root.isArray() || root.path("results").isArray()
                || root.path("items").isArray() || root.path("suggestions").isArray();
    }

    private String chatCompletionContent(JsonNode root) {
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        return content.isTextual() ? content.asText() : "";
    }

    private String chatCompletionDeltaContent(JsonNode root) {
        JsonNode content = root.path("choices").path(0).path("delta").path("content");
        return content.isTextual() ? content.asText() : "";
    }

    private String responsesApiContent(JsonNode root) {
        JsonNode outputText = root.path("output_text");
        if (outputText.isTextual() && !outputText.asText().isBlank()) {
            return outputText.asText();
        }
        JsonNode output = root.path("output");
        if (!output.isArray()) {
            return "";
        }
        StringBuilder contentBuilder = new StringBuilder();
        for (JsonNode outputItem : output) {
            JsonNode content = outputItem.path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode contentItem : content) {
                String contentText = responseContentText(contentItem);
                if (!contentText.isBlank()) {
                    contentBuilder.append(contentText);
                }
            }
        }
        return contentBuilder.toString();
    }

    private String responseContentText(JsonNode contentItem) {
        JsonNode text = contentItem.path("text");
        if (text.isTextual()) {
            return text.asText();
        }
        JsonNode outputText = contentItem.path("output_text");
        if (outputText.isTextual()) {
            return outputText.asText();
        }
        return "";
    }

    /**
     * 解析 LLM 返回的批量 JSON Array。
     *
     * <p>返回结果按输入顺序对齐；当 LLM 返回数量少于请求数量时，缺失位置会补 failed 结果，
     * 避免单个批次的局部异常影响后续持久化流程。</p>
     *
     * @param content  LLM message.content 中的 JSON Array 文本
     * @param requests 当前批次输入商品列表
     * @return 与请求顺序一致的建议结果列表
     * @throws JsonProcessingException JSON 语法非法时抛出，由批量调用入口统一降级为 failed
     */
    List<NormalizationAdvisorResult> parseBatchContent(String content, List<NormalizationAdvisorRequest> requests)
            throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripJsonFence(content));
        JsonNode arrayNode = resultArray(root);
        if (!arrayNode.isArray()) {
            throw new IllegalStateException("LLM 输出不是 JSON Array");
        }
        List<NormalizationAdvisorResult> results = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            NormalizationAdvisorRequest request = requests.get(index);
            if (index >= arrayNode.size()) {
                results.add(failedResult(request.productName(), request.sku(), "LLM 输出数量少于请求数量"));
                continue;
            }
            results.add(validate(arrayNode.get(index), request.productName(), request.sku()));
        }
        return results;
    }

    private JsonNode resultArray(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        if (root.path("results").isArray()) {
            return root.path("results");
        }
        if (root.path("items").isArray()) {
            return root.path("items");
        }
        if (root.path("suggestions").isArray()) {
            return root.path("suggestions");
        }
        return root;
    }

    private String endpointUrl(String baseUrl, String endpointPath) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedEndpointPath = endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;
        return normalizedBaseUrl + normalizedEndpointPath;
    }

    private boolean isOpenAiCompatibleProvider(String provider) {
        return "openai".equalsIgnoreCase(provider) || "openai-compatible".equalsIgnoreCase(provider);
    }

    private String systemPrompt() {
        return """
                你是家庭 / 个人长期复购消耗品归一化 Advisor。只判断商品是否适合进入本地复购价格基准。
                你必须只输出一个 JSON Array，不要输出 Markdown。数组长度必须与输入 items 数量一致，顺序必须一致。
                允许 action：NORMALIZE、EXCLUDE、NEW_CATEGORY、REVIEW。
                允许 productType：REPURCHASE_CONSUMABLE、NON_REPURCHASE、DURABLE、COUPON_OR_DEPOSIT、UNKNOWN。
                REPURCHASE_CONSUMABLE：家庭 / 个人长期复购消耗品，会被持续消耗，并且用户可能周期性重复购买、适合建立本地价格基准的商品。
                REPURCHASE_CONSUMABLE 包括家庭日用品、宠物消耗品、美瞳、隐形眼镜、日抛、精华液、爽肤水、乳液、面霜、防晒、洗面奶、卸妆用品、面膜等。
                NON_REPURCHASE：不适合作为本地复购价格基准的普通商品或服务，例如酒店住宿、一次性礼品、临时购买品、服务类订单、偶发性商品等。
                DURABLE：长期使用的耐用品，例如手机壳、包、衣服、鞋、饰品、相机配件、茶具、猫砂盆、储粮桶、猫粮勺。
                COUPON_OR_DEPOSIT：预售券、定金、锁定权益、加赠权益、优惠券、服务券等。
                先识别是否存在明确商品本体，再识别预售/定金词。标题只有支付权益、券、定金且没有明确真实商品本体时，才可 action=EXCLUDE 且 productType=COUPON_OR_DEPOSIT。
                标题同时包含真实商品本体和“双11预售 / 预售 / 付定 / 定金”等交易词时，不要静默排除；应保留商品本体判断，action=REVIEW，productType=REPURCHASE_CONSUMABLE，reviewRequired=true，并说明需人工确认是否为定金订单。
                UNKNOWN：无法判断。
                LLM 不能决定 include，不能写数据库，只能给建议。
                精华液、爽肤水、乳液、美瞳等个人长期消耗品不应标为 DURABLE；不确定是否纳入价格基准时 action=REVIEW。
                粉底液、遮瑕、口红、唇釉、眉笔、眼影、腮红、眼线笔也可能复购，但色号差异明显，第一阶段应优先 REVIEW。
                猫主食罐、猫罐头、湿粮、猫条、猫汤包、冻干主粮、猫粮等应倾向 REPURCHASE_CONSUMABLE。
                猫主食罐、猫条、猫粮、猫零食、猫汤包不要混成同一个 normalizedName。
                面包、吐司、早餐、蛋糕、饼干、巧克力、饮料等食品不应标为 DURABLE；不确定是否适合作为本地价格基准时优先 REVIEW。
                包装、组合装、整箱、套装、盒装、袋装、罐装、瓶装不能单独作为 DURABLE 判断依据。
                手机壳、衣服、包、鞋、饰品等耐用品或非复购品通常应 EXCLUDE。
                """;
    }

    private String userPrompt(List<NormalizationAdvisorRequest> requests) throws JsonProcessingException {
        List<Map<String, Object>> items = requests.stream()
                .map(request -> Map.<String, Object>of(
                        "productName", safeText(request.productName()),
                        "sku", safeText(request.sku()),
                        "category", safeText(request.category()),
                        "subCategory", safeText(request.subCategory()),
                        "ragEvidence", request.context()
                ))
                .toList();
        Map<String, Object> input = Map.of("items", items);
        return """
                请基于以下输入逐条判断商品归一化建议。只允许使用输入中的商品字段和 RAG 证据。
                输出必须是 JSON Array，每个元素结构必须为：
                [{
                  "rawProductName": "string",
                  "sku": "string",
                  "action": "NORMALIZE | EXCLUDE | NEW_CATEGORY | REVIEW",
                  "suggestedNormalizedName": "string or null",
                  "rejectedNormalizedName": "string or null",
                  "productType": "REPURCHASE_CONSUMABLE | NON_REPURCHASE | DURABLE | COUPON_OR_DEPOSIT | UNKNOWN",
                  "targetUnit": "string or null",
                  "unitFamily": "WEIGHT | VOLUME | COUNT | PIECE | UNKNOWN",
                  "confidence": 0.0,
                  "reviewRequired": true,
                  "reason": "string",
                  "evidence": ["string"]
                }]
                输入：
                """ + objectMapper.writeValueAsString(input);
    }

    private NormalizationAdvisorResult validate(JsonNode root, String fallbackProductName, String fallbackSku) {
        String rawProductName = text(root, "rawProductName", fallbackProductName);
        String sku = text(root, "sku", fallbackSku);
        String action = allowed(text(root, "action", "REVIEW"), ALLOWED_ACTIONS, "REVIEW");
        String productType = allowed(text(root, "productType", "UNKNOWN"), ALLOWED_PRODUCT_TYPES, "UNKNOWN");
        String unitFamily = allowed(text(root, "unitFamily", "UNKNOWN"), ALLOWED_UNIT_FAMILIES, "UNKNOWN");
        double confidence = confidence(root.path("confidence"));
        boolean reviewRequired = root.path("reviewRequired").isMissingNode() || root.path("reviewRequired").asBoolean(true);
        String suggestedNormalizedName = nullableText(root, "suggestedNormalizedName");
        String rejectedNormalizedName = nullableText(root, "rejectedNormalizedName");
        String reason = text(root, "reason", "LLM 未提供明确原因");
        List<String> evidence = evidence(root.path("evidence"));

        ClassificationCorrection correction = correction(rawProductName, sku, suggestedNormalizedName,
                action, productType, reviewRequired, reason);
        action = correction.action();
        productType = correction.productType();
        reviewRequired = correction.reviewRequired();
        reason = correction.reason();

        if ("NORMALIZE".equals(action) && isBlank(suggestedNormalizedName)) {
            action = "REVIEW";
            reviewRequired = true;
            reason = reason + "；NORMALIZE 缺少 suggestedNormalizedName，已降级 REVIEW";
        }
        if ("EXCLUDE".equals(action) && "UNKNOWN".equals(productType)
                && confidence < normalizationProperties.getLlm().getReviewConfidenceThreshold()) {
            action = "REVIEW";
            reviewRequired = true;
            reason = reason + "；EXCLUDE 商品类型未知且置信度不足，已降级 REVIEW";
        }
        return new NormalizationAdvisorResult(rawProductName, sku, action, suggestedNormalizedName,
                rejectedNormalizedName, productType, nullableText(root, "targetUnit"), unitFamily, confidence,
                reviewRequired, reason, evidence, false);
    }

    private NormalizationAdvisorResult failedResult(String productName, String sku, String reason) {
        return new NormalizationAdvisorResult(productName, sku, "REVIEW", null, null, "UNKNOWN",
                null, "UNKNOWN", 0.5D, true, reason, List.of(reason), true);
    }

    private String classifyException(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String lowerMessage = message.toLowerCase(Locale.ROOT);
        if (lowerMessage.contains("read timed out") || lowerMessage.contains("timed out")
                || lowerMessage.contains("timeout")) {
            return "timeout_error：" + sanitizeError(message);
        }
        if (lowerMessage.contains("llm http 响应异常") || lowerMessage.contains("http 响应异常")) {
            return "http_error：" + sanitizeError(message);
        }
        if (lowerMessage.contains("空响应")) {
            return "empty_response：" + sanitizeError(message);
        }
        if (e instanceof JsonProcessingException || lowerMessage.contains("json")) {
            return "json_parse_error：" + sanitizeError(message);
        }
        return "llm_error：" + sanitizeError(message);
    }

    private String sanitizeError(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer ***");
    }

    private String allowed(String value, Set<String> allowedValues, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return allowedValues.contains(normalized) ? normalized : fallback;
    }

    private double confidence(JsonNode node) {
        if (!node.isNumber()) {
            return 0.5D;
        }
        double value = node.asDouble();
        return value < 0D || value > 1D ? 0.5D : value;
    }

    private List<String> evidence(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.asText("").isBlank()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private String text(JsonNode root, String fieldName, String fallback) {
        String value = nullableText(root, fieldName);
        return isBlank(value) ? safeText(fallback) : value;
    }

    private String nullableText(JsonNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private ClassificationCorrection correction(String productName,
                                                String sku,
                                                String suggestedNormalizedName,
                                                String action,
                                                String productType,
                                                boolean reviewRequired,
                                                String reason) {
        String rawText = safeText(productName) + " " + safeText(sku);
        String allText = rawText + " " + safeText(suggestedNormalizedName);
        if (containsAny(rawText, COUPON_OR_DEPOSIT_KEYWORDS)) {
            if (containsRepurchaseConsumableSignal(allText)) {
                return new ClassificationCorrection("REVIEW", "REPURCHASE_CONSUMABLE", true,
                        reason + "；商品本体是复购品，但标题含预售/付定/定金，需人工确认是否为定金订单，不能静默进入价格基准");
            }
            return new ClassificationCorrection("EXCLUDE", "COUPON_OR_DEPOSIT", false,
                    reason + "；命中券/定金类关键词且未识别到明确商品本体，按 COUPON_OR_DEPOSIT 排除");
        }
        if (containsAny(allText, CAT_FOOD_KEYWORDS)) {
            String correctedAction = "EXCLUDE".equals(action) ? "REVIEW" : action;
            return new ClassificationCorrection(correctedAction, "REPURCHASE_CONSUMABLE", reviewRequired,
                    reason + "；命中猫食品消耗品关键词，不应按 EXCLUDE 或 DURABLE 处理");
        }
        if (containsAny(rawText, HUMAN_FOOD_KEYWORDS)) {
            if ("DURABLE".equals(productType)) {
                return new ClassificationCorrection("REVIEW", "UNKNOWN", true,
                        reason + "；命中食品关键词，不应标记为 DURABLE，第一阶段进入 REVIEW");
            }
            return new ClassificationCorrection(action, productType, reviewRequired,
                    reason + "；命中食品关键词，包装或整箱等词不作为 DURABLE 依据");
        }
        if (containsAny(allText, COLOR_MAKEUP_KEYWORDS)) {
            return new ClassificationCorrection("REVIEW", "REPURCHASE_CONSUMABLE", true,
                    reason + "；命中色号强相关彩妆关键词，第一阶段需按色号差异进入 REVIEW");
        }
        if (containsAny(allText, PERSONAL_CARE_KEYWORDS) || containsAny(allText, BEAUTY_CONSUMABLE_KEYWORDS)) {
            String correctedAction = "EXCLUDE".equals(action) ? "REVIEW" : action;
            return new ClassificationCorrection(correctedAction, "REPURCHASE_CONSUMABLE", reviewRequired,
                    reason + "；命中个人护理或美妆护肤消耗品关键词，不应标记为 DURABLE");
        }
        if ("DURABLE".equals(productType) && containsAny(rawText, PACKAGING_KEYWORDS)
                && !containsDurableSignal(rawText)) {
            return new ClassificationCorrection("REVIEW", "UNKNOWN", true,
                    reason + "；仅命中包装规格词，不能作为 DURABLE 依据");
        }
        if (containsDurableSignal(rawText)) {
            return new ClassificationCorrection("EXCLUDE", "DURABLE", false,
                    reason + "；命中耐用品关键词，按 DURABLE 排除");
        }
        return new ClassificationCorrection(action, productType, reviewRequired, reason);
    }

    private boolean containsRepurchaseConsumableSignal(String text) {
        return containsAny(text, CAT_FOOD_KEYWORDS)
                || containsAny(text, PERSONAL_CARE_KEYWORDS)
                || containsAny(text, BEAUTY_CONSUMABLE_KEYWORDS)
                || containsAny(text, COLOR_MAKEUP_KEYWORDS);
    }

    private boolean containsDurableSignal(String text) {
        for (String keyword : DURABLE_KEYWORDS) {
            if (!text.contains(keyword)) {
                continue;
            }
            if ("包".equals(keyword) && (containsAny(text, PACKAGING_KEYWORDS) || text.contains("面包"))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private record ClassificationCorrection(String action, String productType, boolean reviewRequired, String reason) {
    }
}
