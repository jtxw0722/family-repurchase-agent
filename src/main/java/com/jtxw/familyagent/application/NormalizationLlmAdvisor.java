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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
     * LLM reasonCode 展示文案映射；只用于压缩后的原因入库，不参与业务分类决策。
     */
    private static final Map<String, String> REASON_CODE_MESSAGES = reasonCodeMessages();
    /**
     * compact schema 中 shortReason 的最大保存长度，避免长推理文本进入建议表。
     */
    private static final int SHORT_REASON_MAX_LENGTH = 24;
    /**
     * 兼容旧 schema 时 reason 的最大保存长度，避免旧模型输出长段解释。
     */
    private static final int LEGACY_REASON_MAX_LENGTH = 80;
    /**
     * LLM request body 扩展字段禁止覆盖的核心字段。
     */
    private static final Set<String> EXTRA_BODY_FORBIDDEN_TOP_LEVEL_KEYS = Set.of("model", "messages", "stream");
    /**
     * LLM request body 扩展字段中禁止出现的敏感字段名片段。
     */
    private static final List<String> SENSITIVE_EXTRA_BODY_KEY_PARTS = List.of(
            "authorization", "api_key", "apikey", "api-key", "access_token", "refresh_token", "id_token", "secret");
    /**
     * LLM request body 扩展字段中禁止出现的敏感值片段。
     */
    private static final List<String> SENSITIVE_EXTRA_BODY_VALUE_PARTS = List.of("bearer ", "sk-");
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
        return analyzeBatchWithObservation(requests).results();
    }

    /**
     * 批量调用 LLM 并返回观测指标。
     *
     * <p>该方法只负责 LLM 调用、模型输出抽取和结构化解析；业务状态、别名写入和复核项创建由上层服务处理。</p>
     *
     * @param requests 待分析商品列表
     * @return LLM 建议结果和本次调用的观测指标
     */
    public LlmBatchAnalysis analyzeBatchWithObservation(List<NormalizationAdvisorRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return new LlmBatchAnalysis(List.of(), LlmBatchObservation.empty());
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
        long totalStartNanos = System.nanoTime();
        long requestBuildStartNanos = totalStartNanos;
        long requestBuildElapsedMs = 0L;
        long llmHttpElapsedMs = 0L;
        long extractElapsedMs = 0L;
        long parseElapsedMs = 0L;
        boolean requestBuildMeasured = false;
        boolean llmHttpStarted = false;
        long httpStartNanos = 0L;
        LlmHttpResponse httpResponse = null;
        LlmRequestMetrics requestMetrics = null;
        String extractedContent = "";
        try {
            requestMetrics = requestMetrics(requests);
            requestBuildElapsedMs = elapsedMs(requestBuildStartNanos);
            requestBuildMeasured = true;
            httpStartNanos = System.nanoTime();
            llmHttpStarted = true;
            httpResponse = callOpenAi(requestMetrics.requestBody(), llm);
            llmHttpElapsedMs = elapsedMs(httpStartNanos);
            llmHttpStarted = false;
            long extractStartNanos = System.nanoTime();
            extractedContent = extractModelOutput(httpResponse.body());
            extractElapsedMs = elapsedMs(extractStartNanos);
            long parseStartNanos = System.nanoTime();
            List<NormalizationAdvisorResult> results = parseBatchContent(extractedContent, requests);
            parseElapsedMs = elapsedMs(parseStartNanos);
            return new LlmBatchAnalysis(results, new LlmBatchObservation(
                    requestMetrics.promptChars(), requestMetrics.requestBytes(), requestMetrics.requestBody(),
                    requestBuildElapsedMs, llmHttpElapsedMs, extractElapsedMs, parseElapsedMs, elapsedMs(totalStartNanos),
                    httpResponse.httpStatus(), httpResponse.contentType(), httpResponse.responseBytes(),
                    extractedContent.length(), results.size(), null, null, httpResponse.body(), extractedContent
            ));
        } catch (Exception e) {
            if (!requestBuildMeasured) {
                requestBuildElapsedMs = elapsedMs(requestBuildStartNanos);
            }
            if (llmHttpStarted) {
                llmHttpElapsedMs = elapsedMs(httpStartNanos);
            }
            List<NormalizationAdvisorResult> failedResults = requests.stream()
                    .map(request -> failedResult(request.productName(), request.sku(),
                            classifyException(e)))
                    .toList();
            return new LlmBatchAnalysis(failedResults, new LlmBatchObservation(
                    requestMetrics == null ? 0 : requestMetrics.promptChars(),
                    requestMetrics == null ? 0 : requestMetrics.requestBytes(),
                    requestMetrics == null ? null : requestMetrics.requestBody(),
                    requestBuildElapsedMs, llmHttpElapsedMs, extractElapsedMs, parseElapsedMs, elapsedMs(totalStartNanos),
                    httpStatus(e, httpResponse), contentType(e, httpResponse), responseBytes(e, httpResponse),
                    extractedContent.length(), 0, errorType(e), sanitizeError(errorMessage(e)),
                    responseBody(e, httpResponse), extractedContent
            ));
        }
    }

    /**
     * 构建 LLM 请求体并计算 prompt / request 体积指标。
     *
     * <p>日志预估和真实调用都复用该入口，确保 max_tokens 与 extraBodyJson 的校验、合并逻辑一致。</p>
     *
     * @param requests 待分析商品列表
     * @return 请求体字符串和体积指标
     * @throws JsonProcessingException extraBodyJson 或请求体序列化失败时抛出
     */
    public LlmRequestMetrics requestMetrics(List<NormalizationAdvisorRequest> requests) throws JsonProcessingException {
        NormalizationProperties.Llm llm = normalizationProperties.getLlm();
        String systemPrompt = systemPrompt();
        String userPrompt = userPrompt(requests);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", llm.getModel());
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        if (llm.getMaxTokens() > 0) {
            request.put("max_tokens", llm.getMaxTokens());
        }
        mergeExtraBodyJson(request, llm.getExtraBodyJson());
        String requestBody = objectMapper.writeValueAsString(request);
        return new LlmRequestMetrics(systemPrompt.length() + userPrompt.length(),
                requestBody.getBytes(StandardCharsets.UTF_8).length, requestBody);
    }

    private LlmHttpResponse callOpenAi(String requestBody,
                                       NormalizationProperties.Llm llm) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = Math.max(1, llm.getRequestTimeoutSeconds()) * 1000;
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        return restClient.post()
                .uri(endpointUrl(llm.getBaseUrl(), OPENAI_CHAT_COMPLETIONS_PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + llm.getApiKey())
                .body(requestBody)
                .exchange((clientRequest, clientResponse) -> {
                    byte[] responseBody = clientResponse.getBody().readAllBytes();
                    String responseText = new String(responseBody, StandardCharsets.UTF_8);
                    int httpStatus = clientResponse.getStatusCode().value();
                    String contentType = clientResponse.getHeaders().getContentType() == null
                            ? "" : clientResponse.getHeaders().getContentType().toString();
                    if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                        throw new LlmHttpException(httpStatus, contentType, responseBody.length, responseText,
                                "LLM HTTP 响应异常：" + httpStatus + "；响应：" + abbreviate(responseText));
                    }
                    return new LlmHttpResponse(httpStatus, contentType, responseBody.length, responseText(responseBody));
                });
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
        if (containsCompactIndex(arrayNode) || looksLikeCompactSchema(arrayNode)) {
            return parseIndexedResults(arrayNode, requests);
        }
        return parseSequentialResults(arrayNode, requests);
    }

    private List<NormalizationAdvisorResult> parseIndexedResults(JsonNode arrayNode,
                                                                 List<NormalizationAdvisorRequest> requests) {
        List<NormalizationAdvisorResult> results = new ArrayList<>();
        for (NormalizationAdvisorRequest request : requests) {
            results.add(null);
        }
        Set<Integer> usedIndexes = new HashSet<>();
        for (JsonNode itemNode : arrayNode) {
            int requestIndex = requestIndex(itemNode.path("index"));
            if (requestIndex < 0 || requestIndex >= requests.size() || usedIndexes.contains(requestIndex)) {
                continue;
            }
            // compact schema 必须由 index 回填原始商品，避免模型顺序漂移时把 A 商品建议写到 B 商品。
            usedIndexes.add(requestIndex);
            NormalizationAdvisorRequest request = requests.get(requestIndex);
            results.set(requestIndex, validate(itemNode, request.productName(), request.sku()));
        }
        for (int index = 0; index < requests.size(); index++) {
            if (results.get(index) == null) {
                NormalizationAdvisorRequest request = requests.get(index);
                results.set(index, failedResult(request.productName(), request.sku(), "LLM 未返回该商品的有效 index 结果"));
            }
        }
        return results;
    }

    private List<NormalizationAdvisorResult> parseSequentialResults(JsonNode arrayNode,
                                                                    List<NormalizationAdvisorRequest> requests) {
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

    private boolean containsCompactIndex(JsonNode arrayNode) {
        for (JsonNode itemNode : arrayNode) {
            if (!itemNode.path("index").isMissingNode()) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeCompactSchema(JsonNode arrayNode) {
        for (JsonNode itemNode : arrayNode) {
            if (!itemNode.path("normalizedName").isMissingNode()
                    || !itemNode.path("reasonCode").isMissingNode()
                    || !itemNode.path("shortReason").isMissingNode()) {
                return true;
            }
        }
        return false;
    }

    private int requestIndex(JsonNode indexNode) {
        if (!indexNode.canConvertToInt()) {
            return -1;
        }
        int oneBasedIndex = indexNode.asInt();
        return oneBasedIndex - 1;
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
                你是商品归一化分类器，只输出 JSON Array。
                不要解释推理过程，不要 Markdown，不要回显商品名和 SKU，不要 evidence，不要被拒品类字段，不要长 reason。
                每条结果必须包含 index，且 index 对应输入 items 的 index。
                输出字段：index, action, productType, normalizedName, targetUnit, unitFamily, confidence, reviewRequired, reasonCode, shortReason。
                action 只能是 NORMALIZE、EXCLUDE、NEW_CATEGORY、REVIEW。
                productType 只能是 REPURCHASE_CONSUMABLE、NON_REPURCHASE、DURABLE、COUPON_OR_DEPOSIT、UNKNOWN。
                unitFamily 只能是 WEIGHT、VOLUME、COUNT、PIECE、UNKNOWN。
                reasonCode 只能从输入 context.reasonCodes 选择；shortReason 最多 16 个中文字符。
                LLM 只生成建议，不直接 include，不写数据库。
                真实商品 + 预售/付定/定金 => REVIEW，不静默排除；纯券/定金/锁定权益且无真实商品 => EXCLUDE + COUPON_OR_DEPOSIT。
                猫主食罐、猫条、猫粮、猫零食、猫汤包不要混成同一个 normalizedName。
                食品不标 DURABLE，不确定则 REVIEW；色号强相关彩妆优先 REVIEW。
                包装/组合装/整箱/盒装/袋装不能单独作为 DURABLE 判断依据。
                targetUnit 只能是单位，不得是规格值，例如用 g/ml/片/罐/包，不要 240g/80g*4。
                """;
    }

    private String userPrompt(List<NormalizationAdvisorRequest> requests) throws JsonProcessingException {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("context", publicPromptContext(requests));
        input.put("items", compactPromptItems(requests));
        return "逐条输出 compact JSON Array：" + objectMapper.writeValueAsString(input);
    }

    private Map<String, Object> publicPromptContext(List<NormalizationAdvisorRequest> requests) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("rules", publicRules(requests));
        context.put("hints", publicHints());
        context.put("reasonCodes", REASON_CODE_MESSAGES.keySet());
        return context;
    }

    private List<String> publicRules(List<NormalizationAdvisorRequest> requests) {
        Set<String> rules = new LinkedHashSet<>();
        for (NormalizationAdvisorRequest request : requests) {
            NormalizationRagContext context = request.context();
            if (context == null || context.ruleSummaries() == null) {
                continue;
            }
            if (rules.size() >= 10) {
                break;
            }
            context.ruleSummaries().stream()
                    .map(this::compactRuleSummary)
                    .filter(rule -> !rule.isBlank())
                    .limit(10 - rules.size())
                    .forEach(rules::add);
        }
        return new ArrayList<>(rules);
    }

    private Map<String, List<String>> publicHints() {
        Map<String, List<String>> hints = new LinkedHashMap<>();
        hints.put("catMainFood", List.of("猫主食罐", "主食罐", "猫罐头", "湿粮", "餐盒", "一餐一杯"));
        hints.put("catSnack", List.of("猫条", "猫汤包", "咕噜酱", "补水零食", "猫咪零食", "零食罐"));
        hints.put("catFood", List.of("猫粮", "全价猫粮", "主粮", "干粮", "烘焙粮", "冻干主粮"));
        hints.put("personalCare", List.of("美瞳", "隐形眼镜", "日抛", "精华液", "爽肤水", "面霜", "防晒"));
        hints.put("colorCosmeticReview", List.of("粉底液", "遮瑕", "口红", "唇釉", "眉笔", "眼影", "腮红"));
        hints.put("durable", List.of("手机壳", "包", "衣服", "鞋", "饰品", "茶具", "猫砂盆", "储粮桶"));
        hints.put("deposit", List.of("预售", "付定", "定金", "锁定", "优惠券", "预定礼", "加赠"));
        return hints;
    }

    private List<Map<String, Object>> compactPromptItems(List<NormalizationAdvisorRequest> requests) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            NormalizationAdvisorRequest request = requests.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", index + 1);
            item.put("name", safeText(request.productName()));
            item.put("sku", safeText(request.sku()));
            item.put("category", safeText(request.category()));
            item.put("subCategory", safeText(request.subCategory()));
            addItemAliases(item, request.context());
            items.add(item);
        }
        return items;
    }

    private void addItemAliases(Map<String, Object> item, NormalizationRagContext context) {
        if (context == null) {
            return;
        }
        List<String> positiveAliases = compactAliases(context.positiveAliases(), true);
        if (!positiveAliases.isEmpty()) {
            item.put("positiveAliases", positiveAliases);
        }
        List<String> negativeAliases = compactAliases(context.negativeAliases(), false);
        if (!negativeAliases.isEmpty()) {
            item.put("negativeAliases", negativeAliases);
        }
    }

    private List<String> compactAliases(List<String> aliases, boolean positive) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        return aliases.stream()
                .map(alias -> positive ? compactPositiveAlias(alias) : compactNegativeAlias(alias))
                .filter(alias -> !alias.isBlank())
                .limit(3)
                .toList();
    }

    private String compactPositiveAlias(String alias) {
        String text = safeText(alias).replace("正向别名：", "");
        String normalizedName = between(text, "=>", "，targetUnit=");
        String targetUnit = after(text, "，targetUnit=");
        String aliasName = before(text, "=>");
        if (aliasName.isBlank() || normalizedName.isBlank()) {
            return truncate(text, LEGACY_REASON_MAX_LENGTH);
        }
        return aliasName.trim() + "=>" + normalizedName.trim() + "/" + targetUnit.trim();
    }

    private String compactNegativeAlias(String alias) {
        String text = safeText(alias).replace("负向别名：", "");
        String aliasName = before(text, "，拒绝品类=");
        String rejectedName = between(text, "，拒绝品类=", "，reason=");
        if (aliasName.isBlank() || rejectedName.isBlank()) {
            return truncate(text, LEGACY_REASON_MAX_LENGTH);
        }
        return aliasName.trim() + "!=>" + rejectedName.trim();
    }

    private String compactRuleSummary(String ruleSummary) {
        String text = safeText(ruleSummary).replace("规则：", "");
        String ruleId = before(text, "，");
        String normalizedName = between(text, "normalizedName=", "，standardUnit=");
        String standardUnit = between(text, "standardUnit=", "，unitFamily=");
        String unitFamily = between(text, "unitFamily=", "，");
        if (ruleId.isBlank() || normalizedName.isBlank()) {
            return truncate(text, LEGACY_REASON_MAX_LENGTH);
        }
        return ruleId.trim() + "=" + normalizedName.trim() + "/" + standardUnit.trim() + "/" + unitFamily.trim();
    }

    private NormalizationAdvisorResult validate(JsonNode root, String fallbackProductName, String fallbackSku) {
        String rawProductName = text(root, "rawProductName", fallbackProductName);
        String sku = text(root, "sku", fallbackSku);
        String action = allowed(text(root, "action", "REVIEW"), ALLOWED_ACTIONS, "REVIEW");
        String productType = allowed(text(root, "productType", "UNKNOWN"), ALLOWED_PRODUCT_TYPES, "UNKNOWN");
        String unitFamily = allowed(text(root, "unitFamily", "UNKNOWN"), ALLOWED_UNIT_FAMILIES, "UNKNOWN");
        double confidence = confidence(root.path("confidence"));
        boolean reviewRequired = root.path("reviewRequired").isMissingNode() || root.path("reviewRequired").asBoolean(true);
        String suggestedNormalizedName = firstText(root, "normalizedName", "suggestedNormalizedName");
        String rejectedNormalizedName = nullableText(root, "rejectedNormalizedName");
        String reasonCode = nullableText(root, "reasonCode");
        String shortReason = nullableText(root, "shortReason");
        String reason = displayReason(root);
        List<String> evidence = List.of();

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
                reviewRequired, reason, evidence, reasonCode, shortReason, false);
    }

    private String displayReason(JsonNode root) {
        String reasonCode = nullableText(root, "reasonCode");
        String shortReason = nullableText(root, "shortReason");
        if (!isBlank(reasonCode)) {
            String message = REASON_CODE_MESSAGES.get(reasonCode.trim().toUpperCase(Locale.ROOT));
            if (message != null) {
                return message;
            }
            return isBlank(shortReason) ? "需要人工复核" : truncate(shortReason, SHORT_REASON_MAX_LENGTH);
        }
        if (!isBlank(shortReason)) {
            return truncate(shortReason, SHORT_REASON_MAX_LENGTH);
        }
        String legacyReason = nullableText(root, "reason");
        if (!isBlank(legacyReason)) {
            return truncate(legacyReason, LEGACY_REASON_MAX_LENGTH);
        }
        return "需要人工复核";
    }

    private NormalizationAdvisorResult failedResult(String productName, String sku, String reason) {
        return new NormalizationAdvisorResult(productName, sku, "REVIEW", null, null, "UNKNOWN",
                null, "UNKNOWN", 0.5D, true, reason, List.of(reason), true);
    }

    private String classifyException(Exception e) {
        String message = errorMessage(e);
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
        if (e instanceof IOException || lowerMessage.contains("i/o") || lowerMessage.contains("io error")) {
            return "io_error：" + sanitizeError(message);
        }
        return "unknown_error：" + sanitizeError(message);
    }

    private String errorType(Exception e) {
        String classified = classifyException(e);
        int splitIndex = classified.indexOf('：');
        return splitIndex <= 0 ? "unknown_error" : classified.substring(0, splitIndex);
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private int httpStatus(Exception e, LlmHttpResponse httpResponse) {
        if (e instanceof LlmHttpException httpException) {
            return httpException.httpStatus();
        }
        return httpResponse == null ? 0 : httpResponse.httpStatus();
    }

    private String contentType(Exception e, LlmHttpResponse httpResponse) {
        if (e instanceof LlmHttpException httpException) {
            return httpException.contentType();
        }
        return httpResponse == null ? "" : httpResponse.contentType();
    }

    private int responseBytes(Exception e, LlmHttpResponse httpResponse) {
        if (e instanceof LlmHttpException httpException) {
            return httpException.responseBytes();
        }
        return httpResponse == null ? 0 : httpResponse.responseBytes();
    }

    private String responseBody(Exception e, LlmHttpResponse httpResponse) {
        if (e instanceof LlmHttpException httpException) {
            return httpException.responseBody();
        }
        return httpResponse == null ? null : httpResponse.body();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String sanitizeError(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer ***")
                .replaceAll("sk-[A-Za-z0-9_\\-]+", "sk-***");
    }

    private void mergeExtraBodyJson(Map<String, Object> request, String extraBodyJson) throws JsonProcessingException {
        if (extraBodyJson == null || extraBodyJson.isBlank()) {
            return;
        }
        JsonNode extraRoot;
        try {
            extraRoot = objectMapper.readTree(extraBodyJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("NORMALIZATION_LLM_EXTRA_BODY_JSON 不是合法 JSON object", e);
        }
        if (!extraRoot.isObject()) {
            throw new IllegalArgumentException("NORMALIZATION_LLM_EXTRA_BODY_JSON 必须是 JSON object");
        }
        validateExtraBodyJson(extraRoot);
        Iterator<Map.Entry<String, JsonNode>> fields = extraRoot.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if (EXTRA_BODY_FORBIDDEN_TOP_LEVEL_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("NORMALIZATION_LLM_EXTRA_BODY_JSON 不能覆盖核心字段：" + key);
            }
            // 扩展字段允许 provider 个性化参数，但不能携带鉴权信息，避免 debug dump 写出密钥。
            request.put(key, field.getValue());
        }
    }

    private void validateExtraBodyJson(JsonNode root) {
        if (root.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String normalizedKey = field.getKey().toLowerCase(Locale.ROOT);
                for (String sensitiveKey : SENSITIVE_EXTRA_BODY_KEY_PARTS) {
                    if (normalizedKey.contains(sensitiveKey)) {
                        throw new IllegalArgumentException("NORMALIZATION_LLM_EXTRA_BODY_JSON 不能包含密钥或鉴权字段");
                    }
                }
                validateExtraBodyJson(field.getValue());
            }
            return;
        }
        if (root.isArray()) {
            for (JsonNode itemNode : root) {
                validateExtraBodyJson(itemNode);
            }
            return;
        }
        if (root.isTextual()) {
            String normalizedValue = root.asText("").toLowerCase(Locale.ROOT);
            for (String sensitiveValue : SENSITIVE_EXTRA_BODY_VALUE_PARTS) {
                if (normalizedValue.contains(sensitiveValue)) {
                    throw new IllegalArgumentException("NORMALIZATION_LLM_EXTRA_BODY_JSON 不能包含密钥或鉴权值");
                }
            }
        }
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

    private String firstText(JsonNode root, String firstFieldName, String secondFieldName) {
        String firstValue = nullableText(root, firstFieldName);
        return isBlank(firstValue) ? nullableText(root, secondFieldName) : firstValue;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private String before(String text, String delimiter) {
        String safeText = safeText(text);
        int index = safeText.indexOf(delimiter);
        return index < 0 ? safeText : safeText.substring(0, index);
    }

    private String after(String text, String delimiter) {
        String safeText = safeText(text);
        int index = safeText.indexOf(delimiter);
        return index < 0 ? "" : safeText.substring(index + delimiter.length());
    }

    private String between(String text, String startDelimiter, String endDelimiter) {
        String afterStart = after(text, startDelimiter);
        return afterStart.isBlank() ? "" : before(afterStart, endDelimiter);
    }

    private String truncate(String text, int maxLength) {
        String safeText = safeText(text).trim();
        if (safeText.length() <= maxLength) {
            return safeText;
        }
        return safeText.substring(0, maxLength);
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

    private static Map<String, String> reasonCodeMessages() {
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("CAT_MAIN_FOOD", "猫主食罐消耗品");
        messages.put("CAT_SNACK", "猫零食消耗品");
        messages.put("CAT_SOUP_AMBIGUOUS", "猫汤包归类需复核");
        messages.put("PERSONAL_CARE", "个人护理消耗品");
        messages.put("COLOR_COSMETIC_REVIEW", "色号彩妆需复核");
        messages.put("FOOD_REVIEW", "食品是否纳入需复核");
        messages.put("DURABLE_CLOTHING", "服饰耐用品排除");
        messages.put("DURABLE_ACCESSORY", "饰品耐用品排除");
        messages.put("DURABLE_GOODS", "耐用品排除");
        messages.put("COUPON_OR_DEPOSIT", "支付权益类排除");
        messages.put("REAL_PRODUCT_WITH_DEPOSIT", "真实商品含预售付定需复核");
        messages.put("UNIT_UNSAFE", "单位不安全需复核");
        messages.put("UNKNOWN_REVIEW", "无法判断需复核");
        return Map.copyOf(messages);
    }

    private record ClassificationCorrection(String action, String productType, boolean reviewRequired, String reason) {
    }

    public record LlmRequestMetrics(int promptChars, int requestBytes, String requestBody) {
    }

    public record LlmBatchAnalysis(List<NormalizationAdvisorResult> results, LlmBatchObservation observation) {
    }

    public record LlmBatchObservation(int promptChars,
                                      int requestBytes,
                                      String requestBody,
                                      long requestBuildElapsedMs,
                                      long llmHttpElapsedMs,
                                      long extractElapsedMs,
                                      long parseElapsedMs,
                                      long totalElapsedMs,
                                      int httpStatus,
                                      String contentType,
                                      int responseBytes,
                                      int extractedContentChars,
                                      int parsedItems,
                                      String errorType,
                                      String errorMessage,
                                      String responseBody,
                                      String extractedContent) {
        public static LlmBatchObservation empty() {
            return new LlmBatchObservation(0, 0, null, 0L, 0L, 0L, 0L, 0L,
                    0, "", 0, 0, 0, null, null, null, null);
        }
    }

    private record LlmHttpResponse(int httpStatus, String contentType, int responseBytes, String body) {
    }

    private static class LlmHttpException extends RuntimeException {
        private final int httpStatus;
        private final String contentType;
        private final int responseBytes;
        private final String responseBody;

        LlmHttpException(int httpStatus, String contentType, int responseBytes, String responseBody, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.contentType = contentType;
            this.responseBytes = responseBytes;
            this.responseBody = responseBody;
        }

        int httpStatus() {
            return httpStatus;
        }

        String contentType() {
            return contentType;
        }

        int responseBytes() {
            return responseBytes;
        }

        String responseBody() {
            return responseBody;
        }
    }
}
