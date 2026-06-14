package com.jtxw.familyagent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorRequest;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorResult;
import com.jtxw.familyagent.domain.model.NormalizationRagContext;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 17:58:26
 * @Description: 商品归一化 LLM Advisor，负责构建请求、调用 LLM、提取模型输出并协调结构化建议解析。
 */
@Service
public class NormalizationLlmAdvisor implements ProductNormalizationAdvisor {
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
    /**
     * prompt 资源加载器，从 classpath 加载版本化 prompt 文件。
     */
    private final NormalizationPromptRenderer promptRenderer;
    /**
     * LLM 原始调用客户端，负责发送 HTTP 请求并返回未解析响应。
     */
    private final LlmClient llmClient;
    /**
     * LLM 输出解析器，负责 JSON Array、DTO 转换和基础 schema-like 校验。
     */
    private final LlmNormalizationOutputParser outputParser;

    /**
     * 构造 LLM Advisor，使用指定的 prompt 资源加载器和 LLM 客户端。
     *
     * @param normalizationProperties 归一化配置
     * @param objectMapper            JSON 序列化组件
     * @param promptRenderer          prompt 资源加载器
     * @param llmClient               LLM 原始调用客户端
     * @param outputParser            LLM 输出解析器
     */
    @Autowired
    public NormalizationLlmAdvisor(NormalizationProperties normalizationProperties,
                                   ObjectMapper objectMapper,
                                   NormalizationPromptRenderer promptRenderer,
                                   LlmClient llmClient,
                                   LlmNormalizationOutputParser outputParser) {
        this.normalizationProperties = normalizationProperties;
        this.objectMapper = objectMapper;
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer must not be null");
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser must not be null");
    }

    /**
     * 构造 LLM Advisor，使用指定的 prompt 资源加载器和 LLM 客户端。
     *
     * <p>仅供单元测试显式注入测试客户端时使用。</p>
     *
     * @param normalizationProperties 归一化配置
     * @param objectMapper            JSON 序列化组件
     * @param promptRenderer          prompt 资源加载器
     * @param llmClient               LLM 原始调用客户端
     */
    public NormalizationLlmAdvisor(NormalizationProperties normalizationProperties,
                                   ObjectMapper objectMapper,
                                   NormalizationPromptRenderer promptRenderer,
                                   LlmClient llmClient) {
        this(normalizationProperties, objectMapper, promptRenderer, llmClient,
                new LlmNormalizationOutputParser(objectMapper,
                        new LlmNormalizationItemValidator(normalizationProperties)));
    }

    /**
     * 构造 LLM Advisor，使用默认 prompt 资源加载器。
     *
     * <p>仅供测试和不依赖自定义 prompt 资源路径的场景使用。</p>
     *
     * @param normalizationProperties 归一化配置
     * @param objectMapper            JSON 序列化组件
     */
    public NormalizationLlmAdvisor(NormalizationProperties normalizationProperties, ObjectMapper objectMapper) {
        this(normalizationProperties, objectMapper, new NormalizationPromptRenderer(normalizationProperties),
                request -> {
                    throw new IllegalStateException("LLM client 未配置");
                },
                new LlmNormalizationOutputParser(objectMapper,
                        new LlmNormalizationItemValidator(normalizationProperties)));
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
    @Override
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
    @Override
    public NormalizationAdviceBatchAnalysis analyzeBatchWithObservation(List<NormalizationAdvisorRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return new NormalizationAdviceBatchAnalysis(List.of(), NormalizationAdviceObservation.empty());
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
        LlmClientResponse clientResponse = null;
        NormalizationAdviceRequestMetrics requestMetrics = null;
        String extractedContent = "";
        try {
            requestMetrics = requestMetrics(requests);
            requestBuildElapsedMs = elapsedMs(requestBuildStartNanos);
            requestBuildMeasured = true;
            httpStartNanos = System.nanoTime();
            llmHttpStarted = true;
            clientResponse = llmClient.chatCompletion(new LlmClientRequest(
                    llm.getBaseUrl(),
                    llm.getApiKey(),
                    llm.getRequestTimeoutSeconds(),
                    requestMetrics.requestBody()
            ));
            llmHttpElapsedMs = elapsedMs(httpStartNanos);
            llmHttpStarted = false;
            long extractStartNanos = System.nanoTime();
            extractedContent = extractModelOutput(clientResponse.body());
            extractElapsedMs = elapsedMs(extractStartNanos);
            long parseStartNanos = System.nanoTime();
            List<NormalizationAdvisorResult> results = parseBatchContent(extractedContent, requests);
            parseElapsedMs = elapsedMs(parseStartNanos);
            return new NormalizationAdviceBatchAnalysis(results, new NormalizationAdviceObservation(
                    requestMetrics.promptChars(), requestMetrics.requestBytes(), requestMetrics.requestBody(),
                    requestBuildElapsedMs, llmHttpElapsedMs, extractElapsedMs, parseElapsedMs, elapsedMs(totalStartNanos),
                    clientResponse.httpStatus(), clientResponse.contentType(), clientResponse.responseBytes(),
                    extractedContent.length(), results.size(), null, null, clientResponse.body(), extractedContent
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
            return new NormalizationAdviceBatchAnalysis(failedResults, new NormalizationAdviceObservation(
                    requestMetrics == null ? 0 : requestMetrics.promptChars(),
                    requestMetrics == null ? 0 : requestMetrics.requestBytes(),
                    requestMetrics == null ? null : requestMetrics.requestBody(),
                    requestBuildElapsedMs, llmHttpElapsedMs, extractElapsedMs, parseElapsedMs, elapsedMs(totalStartNanos),
                    httpStatus(e, clientResponse), contentType(e, clientResponse), responseBytes(e, clientResponse),
                    extractedContent.length(), 0, errorType(e), sanitizeError(errorMessage(e)),
                    responseBody(e, clientResponse), extractedContent
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
    @Override
    public NormalizationAdviceRequestMetrics requestMetrics(List<NormalizationAdvisorRequest> requests)
            throws JsonProcessingException {
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
        return new NormalizationAdviceRequestMetrics(systemPrompt.length() + userPrompt.length(),
                requestBody.getBytes(StandardCharsets.UTF_8).length, requestBody);
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

    /**
     * 移除模型或代理服务包裹在 JSON 外层的 Markdown 代码块标记。
     *
     * @param content 原始模型输出或 SSE data 内容
     * @return 去除代码块围栏后的文本；未使用代码块时原样返回 trim 后内容
     */
    private String stripJsonFence(String content) {
        String trimmedContent = content.trim();
        if (!trimmedContent.startsWith("```")) {
            return trimmedContent;
        }
        String withoutOpeningFence = trimmedContent.replaceFirst("^```[a-zA-Z]*\\s*", "");
        return withoutOpeningFence.replaceFirst("\\s*```$", "").trim();
    }

    /**
     * 判断原始响应是否包含 SSE data 行。
     *
     * @param responseText HTTP 原始响应文本
     * @return 只要任意一行以 data: 开头即返回 true
     */
    private boolean hasSseData(String responseText) {
        for (String line : responseText.split("\\R")) {
            if (line.trim().startsWith("data:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 SSE 响应中提取模型输出文本。
     *
     * <p>兼容两类流式返回：Chat Completions delta.content 增量拼接，以及代理服务在 data 行中返回完整
     * JSON Array / message.content / Responses API 内容。若没有可用 data JSON 内容，则抛出异常并由批量入口降级。</p>
     *
     * @param responseText 包含 data 行的 SSE 响应文本
     * @return 拼接后的模型输出 JSON Array 文本，或最后一次完整内容
     * @throws JsonProcessingException 单个 data 行不是合法 JSON 时抛出
     */
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

    /**
     * 判断 JSON 根节点是否已经是可交给输出解析器处理的模型结果结构。
     *
     * @param root 原始响应根节点
     * @return 如果是数组或包含 results/items/suggestions 数组包装则返回 true
     */
    private boolean isDirectModelOutput(JsonNode root) {
        return root.isArray() || root.path("results").isArray()
                || root.path("items").isArray() || root.path("suggestions").isArray();
    }

    /**
     * 提取 Chat Completions 非流式响应中的 message.content。
     *
     * @param root Chat Completions 响应根节点
     * @return content 文本；字段不存在或不是文本时返回空字符串
     */
    private String chatCompletionContent(JsonNode root) {
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        return content.isTextual() ? content.asText() : "";
    }

    /**
     * 提取 Chat Completions 流式响应单个 data 片段中的 delta.content。
     *
     * @param root 单个 SSE data JSON 根节点
     * @return 增量 content 文本；字段不存在或不是文本时返回空字符串
     */
    private String chatCompletionDeltaContent(JsonNode root) {
        JsonNode content = root.path("choices").path(0).path("delta").path("content");
        return content.isTextual() ? content.asText() : "";
    }

    /**
     * 提取 Responses API 响应中的输出文本。
     *
     * <p>优先兼容 output_text 快捷字段；没有该字段时遍历 output[].content[]，
     * 拼接 text 或 output_text 字段，保留不同 Responses API 形态的兼容性。</p>
     *
     * @param root Responses API 响应根节点
     * @return 可交给输出解析器处理的模型输出文本；没有可用文本时返回空字符串
     */
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

    /**
     * 提取 Responses API content 单元中的文本字段。
     *
     * @param contentItem output[].content[] 中的单个节点
     * @return text 或 output_text 字段文本；两者都不存在时返回空字符串
     */
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
        return outputParser.parseBatchContent(content, requests, this::applyBusinessCorrection);
    }

    /**
     * 判断当前 provider 是否走 OpenAI-compatible 请求协议。
     *
     * @param provider 配置中的 LLM provider
     * @return openai 或 openai-compatible 时返回 true
     */
    private boolean isOpenAiCompatibleProvider(String provider) {
        return "openai".equalsIgnoreCase(provider) || "openai-compatible".equalsIgnoreCase(provider);
    }

    /**
     * 获取系统 prompt。
     *
     * @return prompt 资源加载器提供的系统提示词
     */
    private String systemPrompt() {
        return promptRenderer.getSystemPrompt();
    }

    /**
     * 构建用户 prompt。
     *
     * <p>仅放入公开规则、公开提示词和压缩后的商品条目，不携带订单金额、店铺、owner、来源文件等隐私字段。</p>
     *
     * @param requests 当前批次待分析商品
     * @return 渲染后的用户 prompt
     * @throws JsonProcessingException prompt 输入 JSON 序列化失败时抛出
     */
    private String userPrompt(List<NormalizationAdvisorRequest> requests) throws JsonProcessingException {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("context", publicPromptContext(requests));
        input.put("items", compactPromptItems(requests));
        String inputJson = objectMapper.writeValueAsString(input);
        return promptRenderer.renderUserPrompt(inputJson);
    }

    /**
     * 构建可发送给 LLM 的公开上下文。
     *
     * @param requests 当前批次待分析商品
     * @return 包含规则摘要、公开提示词和 reasonCode 集合的上下文
     */
    private Map<String, Object> publicPromptContext(List<NormalizationAdvisorRequest> requests) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("rules", publicRules(requests));
        context.put("hints", publicHints());
        context.put("reasonCodes", LlmNormalizationItemValidator.reasonCodes());
        return context;
    }

    /**
     * 从批次 RAG 上下文中提取可公开发送的规则摘要。
     *
     * <p>最多保留 10 条去重后的压缩规则，避免 prompt 过长，也避免把原始冗余描述直接发送给模型。</p>
     *
     * @param requests 当前批次待分析商品
     * @return 压缩后的规则摘要列表
     */
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

    /**
     * 构建可公开发送给 LLM 的领域提示词。
     *
     * @return 按业务主题分组的关键词提示，用于辅助模型区分复购消耗品、耐用品、券/定金等边界
     */
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

    /**
     * 将批次商品压缩为 prompt item 列表。
     *
     * <p>每个 item 使用 1-based index 作为回填锚点，只包含名称、SKU、电商分类和压缩别名，
     * 由输出解析器按 index 对齐结果，降低模型乱序输出带来的写错商品风险。</p>
     *
     * @param requests 当前批次待分析商品
     * @return 可序列化到 user prompt 的商品条目列表
     */
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
            items.add(item);
        }
        return items;
    }

    /**
     * 压缩规则摘要。
     *
     * @param ruleSummary 原始规则摘要文本
     * @return ruleId=normalizedName/standardUnit/unitFamily 格式；无法解析时返回截断后的原文
     */
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

    /**
     * 对解析器产出的建议结果执行业务纠偏。
     *
     * <p>P4 后基础字段校验已下沉到 parser / validator，本方法只保留 Advisor 原有的业务安全兜底：
     * 对券/定金、猫食品、食品、色号彩妆、个人护理和耐用品关键词进行纠偏，避免明显误分类直接入库。</p>
     *
     * @param result 已完成基础字段校验的建议结果
     * @return 应用业务纠偏后的建议结果
     */
    private NormalizationAdvisorResult applyBusinessCorrection(NormalizationAdvisorResult result) {
        ClassificationCorrection correction = correction(result.rawProductName(), result.sku(),
                result.suggestedNormalizedName(), result.action(), result.productType(),
                result.reviewRequired(), result.reason());
        return new NormalizationAdvisorResult(result.rawProductName(), result.sku(), correction.action(),
                result.suggestedNormalizedName(), result.rejectedNormalizedName(), correction.productType(),
                result.targetUnit(), result.unitFamily(), result.confidence(), correction.reviewRequired(),
                correction.reason(), result.evidence(), result.reasonCode(), result.shortReason(), result.failed());
    }

    /**
     * 创建批次级失败时的单条兜底结果。
     *
     * @param productName 原始商品名称
     * @param sku         商品规格或 SKU
     * @param reason      失败原因，已由异常分类逻辑归一化
     * @return failed=true 的 REVIEW 建议
     */
    private NormalizationAdvisorResult failedResult(String productName, String sku, String reason) {
        return new NormalizationAdvisorResult(productName, sku, "REVIEW", null, null, "UNKNOWN",
                null, "UNKNOWN", 0.5D, true, reason, List.of(reason), true);
    }

    /**
     * 将 LLM 调用链路异常归类为稳定的错误类型和可展示原因。
     *
     * <p>该分类用于建议结果 reason 和观测指标 errorType；错误文本会先脱敏，避免 API Key 或 Bearer Token
     * 出现在 debug dump、日志或持久化结果中。</p>
     *
     * @param e LLM 调用、输出抽取或解析过程中抛出的异常
     * @return errorType：脱敏错误信息 格式的分类结果
     */
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

    /**
     * 从异常分类结果中提取观测指标使用的错误类型。
     *
     * @param e LLM 调用链路异常
     * @return timeout_error、http_error、json_parse_error 等稳定错误类型
     */
    private String errorType(Exception e) {
        String classified = classifyException(e);
        int splitIndex = classified.indexOf('：');
        return splitIndex <= 0 ? "unknown_error" : classified.substring(0, splitIndex);
    }

    /**
     * 提取异常消息。
     *
     * @param e LLM 调用链路异常
     * @return 异常消息；消息为空时返回异常类名
     */
    private String errorMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * 提取失败观测中的 HTTP 状态码。
     *
     * @param e              LLM 调用链路异常
     * @param clientResponse 已收到的客户端响应；请求未完成时可能为空
     * @return LlmClientException 中的状态码优先，否则返回响应状态码或 0
     */
    private int httpStatus(Exception e, LlmClientResponse clientResponse) {
        if (e instanceof LlmClientException clientException) {
            return clientException.httpStatus();
        }
        return clientResponse == null ? 0 : clientResponse.httpStatus();
    }

    /**
     * 提取失败观测中的响应 Content-Type。
     *
     * @param e              LLM 调用链路异常
     * @param clientResponse 已收到的客户端响应；请求未完成时可能为空
     * @return LlmClientException 中的 Content-Type 优先，否则返回响应 Content-Type 或空字符串
     */
    private String contentType(Exception e, LlmClientResponse clientResponse) {
        if (e instanceof LlmClientException clientException) {
            return clientException.contentType();
        }
        return clientResponse == null ? "" : clientResponse.contentType();
    }

    /**
     * 提取失败观测中的响应体字节数。
     *
     * @param e              LLM 调用链路异常
     * @param clientResponse 已收到的客户端响应；请求未完成时可能为空
     * @return LlmClientException 中的响应字节数优先，否则返回响应字节数或 0
     */
    private int responseBytes(Exception e, LlmClientResponse clientResponse) {
        if (e instanceof LlmClientException clientException) {
            return clientException.responseBytes();
        }
        return clientResponse == null ? 0 : clientResponse.responseBytes();
    }

    /**
     * 提取失败观测中的响应体。
     *
     * @param e              LLM 调用链路异常
     * @param clientResponse 已收到的客户端响应；请求未完成时可能为空
     * @return LlmClientException 中的响应体优先，否则返回客户端响应体或 null
     */
    private String responseBody(Exception e, LlmClientResponse clientResponse) {
        if (e instanceof LlmClientException clientException) {
            return clientException.responseBody();
        }
        return clientResponse == null ? null : clientResponse.body();
    }

    /**
     * 计算阶段耗时。
     *
     * @param startNanos 阶段开始时间，来源于 System.nanoTime()
     * @return 当前时刻到开始时刻的毫秒数
     */
    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 脱敏错误信息。
     *
     * @param message 原始异常消息
     * @return 替换 Bearer Token 和 sk- 前缀密钥后的安全文本
     */
    private String sanitizeError(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer ***")
                .replaceAll("sk-[A-Za-z0-9_\\-]+", "sk-***");
    }

    /**
     * 校验并合并 LLM request body 扩展字段。
     *
     * <p>extraBodyJson 只允许作为 provider 个性化参数进入请求体，不能覆盖 model、messages、stream
     * 等核心字段，也不能携带密钥或鉴权信息；JSON 语法或结构非法时抛出异常，由批量入口统一降级。</p>
     *
     * @param request       待发送给 LLM 的请求体
     * @param extraBodyJson 配置中的扩展 JSON object
     * @throws JsonProcessingException JSON 解析失败时抛出
     */
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
        for (Map.Entry<String, JsonNode> field : extraRoot.properties()) {
            String key = field.getKey();
            if (EXTRA_BODY_FORBIDDEN_TOP_LEVEL_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("NORMALIZATION_LLM_EXTRA_BODY_JSON 不能覆盖核心字段：" + key);
            }
            // 扩展字段允许 provider 个性化参数，但不能携带鉴权信息，避免 debug dump 写出密钥。
            request.put(key, field.getValue());
        }
    }

    /**
     * 递归校验 extraBodyJson 中是否包含敏感鉴权信息。
     *
     * <p>字段名按片段匹配 authorization、api_key、token、secret 等敏感词；
     * 文本值按 Bearer 和 sk- 片段匹配，避免扩展参数被写入 debug dump 后泄露密钥。</p>
     *
     * @param root extraBodyJson 当前校验节点
     */
    private void validateExtraBodyJson(JsonNode root) {
        if (root.isObject()) {
            for (Map.Entry<String, JsonNode> field : root.properties()) {
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

    /**
     * 根据本地关键词规则对 LLM 分类结果做业务纠偏。
     *
     * <p>该方法不负责基础字段合法性校验，只处理项目内已知的高风险误判：
     * 定金/券与真实复购品混淆、猫食品被排除、食品被误标耐用品、色号彩妆需要复核、
     * 个人护理消耗品被误标耐用品，以及包装词被误当作耐用品依据。</p>
     *
     * @param productName             原始商品名称
     * @param sku                     商品规格或 SKU
     * @param suggestedNormalizedName LLM 建议的标准品类名称
     * @param action                  LLM 建议动作
     * @param productType             LLM 判断的商品类型
     * @param reviewRequired          LLM 是否要求人工复核
     * @param reason                  当前原因文本
     * @return 纠偏后的动作、商品类型、复核标记和原因
     */
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

    /**
     * 判断文本中是否存在明确的复购消耗品信号。
     *
     * @param text 商品名、SKU 和建议品类拼接后的文本
     * @return 命中宠物食品、个人护理、美妆护肤或色号彩妆关键词时返回 true
     */
    private boolean containsRepurchaseConsumableSignal(String text) {
        return containsAny(text, CAT_FOOD_KEYWORDS)
                || containsAny(text, PERSONAL_CARE_KEYWORDS)
                || containsAny(text, BEAUTY_CONSUMABLE_KEYWORDS)
                || containsAny(text, COLOR_MAKEUP_KEYWORDS);
    }

    /**
     * 判断文本中是否存在明确的耐用品信号。
     *
     * <p>“包”是高歧义关键词；当它出现在包装或面包上下文中时不作为耐用品依据。</p>
     *
     * @param text 原始商品名称和 SKU 拼接后的文本
     * @return 命中耐用品关键词且未被包装上下文排除时返回 true
     */
    private boolean containsDurableSignal(String text) {
        for (String keyword : DURABLE_KEYWORDS) {
            if (!text.contains(keyword)) {
                continue;
            }
            boolean isPackagingKeyword = "包".equals(keyword);
            if (isPackagingKeyword) {
                boolean isPackagingContext = containsAny(text, PACKAGING_KEYWORDS) || text.contains("面包");
                if (isPackagingContext) {
                    continue;
                }
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
