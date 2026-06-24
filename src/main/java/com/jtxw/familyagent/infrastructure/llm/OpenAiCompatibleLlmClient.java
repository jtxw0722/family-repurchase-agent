package com.jtxw.familyagent.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 12:39:31
 * @Description: OpenAI-compatible 通用供应商适配器，负责构造文本或图片聊天请求并提取首个模型消息
 */
public class OpenAiCompatibleLlmClient implements LlmClient {
    /**
     * OpenAI-compatible Chat Completions endpoint 路径。
     */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    /**
     * 供应商标识。
     */
    private static final String PROVIDER = "openai-compatible";
    /**
     * 未配置有效超时时使用的默认秒数。
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;

    /**
     * 不包含业务配置的供应商连接设置。
     */
    private final OpenAiCompatibleLlmClientSettings settings;
    /**
     * JSON 请求构造和响应解析器。
     */
    private final ObjectMapper objectMapper;
    /**
     * LLM debug dump 写入器，关闭时不会创建目录或写文件。
     */
    private final LlmDebugLogger debugLogger;

    /**
     * 创建 OpenAI-compatible 通用供应商适配器，使用指定连接设置和 JSON 解析器。
     *
     * @param settings     供应商连接设置
     * @param objectMapper JSON 请求构造和响应解析器
     */
    public OpenAiCompatibleLlmClient(OpenAiCompatibleLlmClientSettings settings, ObjectMapper objectMapper) {
        this(settings, objectMapper, LlmDebugLogger.disabled(objectMapper));
    }

    /**
     * 创建 OpenAI-compatible 通用供应商适配器，使用指定连接设置、JSON 解析器和 debug 写入器。
     *
     * @param settings     供应商连接设置
     * @param objectMapper JSON 请求构造和响应解析器
     * @param debugLogger  LLM debug dump 写入器
     */
    public OpenAiCompatibleLlmClient(OpenAiCompatibleLlmClientSettings settings, ObjectMapper objectMapper,
                                     LlmDebugLogger debugLogger) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.debugLogger = debugLogger;
    }

    /**
     * 将通用聊天请求转换为 OpenAI-compatible 协议并返回首个文本消息。
     *
     * @param request 供应商无关的聊天请求
     * @return 首个消息文本、实际模型、调用耗时和可选 token 用量
     */
    @Override
    public LlmResponse chat(LlmRequest request) {
        validate(request);
        String requestJson = buildRequestJson(request);
        long startNanos = System.nanoTime();
        try {
            String responseJson = execute(requestJson);
            long durationMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            LlmResponse response = parseResponse(responseJson, request.model(), durationMillis);
            debugLogger.logSuccess(request, response);
            return response;
        } catch (RuntimeException exception) {
            long durationMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            debugLogger.logFailure(request, exception, durationMillis);
            throw exception;
        }
    }

    /**
     * 校验连接设置和通用请求必填字段，错误信息不回显实际配置值。
     */
    private void validate(LlmRequest request) {
        if (settings == null) {
            throw new LlmException("LLM 连接设置未配置");
        }
        requireText(settings.baseUrl(), "LLM base-url 未配置");
        requireText(settings.apiKey(), "LLM api-key 未配置");
        if (request == null) {
            throw new LlmException("LLM 请求不能为空");
        }
        requireText(request.model(), "LLM model 未配置");
        requireText(request.userPrompt(), "LLM userPrompt 不能为空");
    }

    /**
     * 构造 OpenAI-compatible 请求；有图片时使用 content array，无图片时使用普通文本。
     *
     * @param request 通用聊天请求
     * @return JSON 请求体，不应写入日志或异常
     */
    private String buildRequestJson(LlmRequest request) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.model().trim());
        root.put("temperature", request.temperature() == null ? 0D : request.temperature());
        if (request.maxTokens() != null) {
            root.put("max_tokens", request.maxTokens());
        }
        ArrayNode messages = root.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.systemPrompt());
        }
        ObjectNode userMessage = messages.addObject().put("role", "user");
        if (request.images().isEmpty()) {
            userMessage.put("content", request.userPrompt());
        } else {
            ArrayNode content = userMessage.putArray("content");
            content.addObject().put("type", "text").put("text", request.userPrompt());
            for (LlmImageInput image : request.images()) {
                content.addObject().put("type", "image_url").putObject("image_url")
                        .put("url", dataUrl(image));
            }
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (IOException exception) {
            throw new LlmException("无法构造 LLM 请求 JSON");
        }
    }

    /**
     * 调用 Chat Completions，仅在错误信息中保留 HTTP 状态或根异常类型。
     *
     * @param requestJson JSON 请求体，不记录、不回显
     * @return 非空 HTTP 响应正文
     */
    private String execute(String requestJson) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutSeconds = settings.timeoutSeconds() > 0
                ? settings.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;
        requestFactory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        try {
            return restClient.post()
                    .uri(endpointUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + settings.apiKey())
                    .body(requestJson)
                    .exchange((httpRequest, httpResponse) -> {
                        byte[] responseBytes = httpResponse.getBody().readAllBytes();
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            throw new LlmException("LLM HTTP 响应异常：" + httpResponse.getStatusCode().value());
                        }
                        String responseText = new String(responseBytes, StandardCharsets.UTF_8);
                        if (responseText.isBlank()) {
                            throw new LlmException("LLM 返回空响应");
                        }
                        return responseText;
                    });
        } catch (LlmException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LlmException("LLM 请求失败：" + rootExceptionName(exception));
        }
    }

    /**
     * 解析 OpenAI-compatible 外层响应，不解释消息文本的业务含义。
     *
     * @param responseJson   HTTP 响应正文
     * @param requestModel   请求模型名称，响应缺失 model 时作为兜底
     * @param durationMillis 请求耗时，单位毫秒
     * @return 通用 LLM 响应
     */
    private LlmResponse parseResponse(String responseJson, String requestModel, long durationMillis) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (IOException exception) {
            throw new LlmException("LLM HTTP 响应不是有效 JSON");
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException("LLM 响应 choices 为空");
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new LlmException("LLM 响应 content 为空");
        }
        JsonNode usage = root.path("usage");
        Integer promptTokens = integerOrNull(usage.path("prompt_tokens"));
        Integer completionTokens = integerOrNull(usage.path("completion_tokens"));
        String responseModel = root.path("model").isTextual() && !root.path("model").asText().isBlank()
                ? root.path("model").asText() : requestModel;
        return new LlmResponse(content.asText(), PROVIDER, responseModel, durationMillis,
                promptTokens, completionTokens);
    }

    /**
     * 将单张图片转换为 OpenAI-compatible data URL，仅在内存请求构造期间使用。
     *
     * @param image 通用图片输入
     * @return 包含 MIME 类型和 Base64 内容的 data URL
     */
    private String dataUrl(LlmImageInput image) {
        requireText(image.mimeType(), "LLM 图片 MIME 类型不能为空");
        return "data:" + image.mimeType() + ";base64,"
                + Base64.getEncoder().encodeToString(image.content());
    }

    /**
     * 拼接规范化基础地址和 Chat Completions endpoint 路径。
     *
     * @return 规范化基础地址后拼接的 Chat Completions endpoint
     */
    private String endpointUrl() {
        String baseUrl = settings.baseUrl().trim();
        return (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                + CHAT_COMPLETIONS_PATH;
    }

    /**
     * 从 JSON 节点提取整数值，非整数类型时返回空。
     */
    private Integer integerOrNull(JsonNode value) {
        return value.isIntegralNumber() ? value.intValue() : null;
    }

    /**
     * 校验配置值非空且非空白，不满足时抛出安全异常。
     */
    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new LlmException(message);
        }
    }

    /**
     * 获取异常链根因的简单类名，用于安全错误信息中标识底层异常类型。
     */
    private String rootExceptionName(RuntimeException exception) {
        Throwable rootCause = exception;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        return rootCause.getClass().getSimpleName();
    }
}
