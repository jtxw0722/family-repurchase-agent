package com.jtxw.familyagent.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.application.LlmClientException;
import com.jtxw.familyagent.application.LlmClientRequest;
import com.jtxw.familyagent.application.LlmClientResponse;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 10:35:42
 * @Description: 归一化建议链路兼容客户端，保留原始 HTTP 请求响应契约并接入统一 LLM debug dump
 */
@Service
public class NormalizationOpenAiCompatibleLlmClient implements com.jtxw.familyagent.application.LlmClient {
    /**
     * OpenAI-compatible Chat Completions endpoint 路径。
     */
    private static final String OPENAI_CHAT_COMPLETIONS_PATH = "/chat/completions";
    /**
     * 归一化规则建议 LLM debug 场景标识。
     */
    private static final String SCENE = "normalization_rule_suggestions";
    /**
     * 归一化规则建议 prompt 版本标识。
     */
    private static final String PROMPT_VERSION = "normalization-rule-suggestions-v1";
    /**
     * 归一化规则建议当前使用的供应商协议标识。
     */
    private static final String PROVIDER = "openai-compatible";
    /**
     * 错误响应摘要最大字符数，避免异常消息过长。
     */
    private static final int ERROR_RESPONSE_SUMMARY_MAX_LENGTH = 500;

    /**
     * JSON 解析器，用于从既有 requestBody/responseBody 中提取安全 debug 摘要。
     */
    private final ObjectMapper objectMapper;
    /**
     * 统一 LLM debug dump 写入器，关闭时不会创建目录或写文件。
     */
    private final LlmDebugLogger debugLogger;

    /**
     * 创建归一化建议链路兼容客户端。
     *
     * @param objectMapper JSON 解析器，用于提取安全 debug 摘要
     * @param debugLogger  统一 LLM debug dump 写入器
     */
    public NormalizationOpenAiCompatibleLlmClient(ObjectMapper objectMapper, LlmDebugLogger debugLogger) {
        this.objectMapper = objectMapper;
        this.debugLogger = debugLogger;
    }

    /**
     * 发送归一化链路已经序列化的 Chat Completions 请求，并在统一 debug 开启时写入安全 dump 文件。
     *
     * @param request 既有 LLM 调用请求，包含连接配置和已序列化请求体
     * @return 既有原始 HTTP 响应
     */
    @Override
    public LlmClientResponse chatCompletion(LlmClientRequest request) {
        LlmRequest debugRequest = debugRequest(request);
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = Math.max(1, request.requestTimeoutSeconds()) * 1000;
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        long startNanos = System.nanoTime();
        try {
            LlmClientResponse response = restClient.post()
                    .uri(endpointUrl(request.baseUrl(), OPENAI_CHAT_COMPLETIONS_PATH))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + request.apiKey())
                    .body(request.requestBody())
                    .exchange((clientRequest, clientResponse) -> {
                        byte[] responseBody = clientResponse.getBody().readAllBytes();
                        String responseText = new String(responseBody, StandardCharsets.UTF_8);
                        int httpStatus = clientResponse.getStatusCode().value();
                        String contentType = clientResponse.getHeaders().getContentType() == null
                                ? "" : clientResponse.getHeaders().getContentType().toString();
                        if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                            throw new LlmClientException(httpStatus, contentType, responseBody.length, responseText,
                                    "LLM HTTP 响应异常：" + httpStatus + "；响应：" + abbreviate(responseText));
                        }
                        return new LlmClientResponse(httpStatus, contentType, responseBody.length,
                                responseText(responseBody));
                    });
            long durationMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            debugLogger.logSuccess(debugRequest, debugResponse(debugRequest, response.body(), durationMillis));
            return response;
        } catch (RuntimeException exception) {
            long durationMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            debugLogger.logFailure(debugRequest, PROVIDER, debugRequest.model(), exception, durationMillis);
            throw exception;
        }
    }

    /**
     * 从既有原始请求体中提取安全 debug 摘要，不保留 API Key、Authorization Header 或完整 HTTP 请求体。
     */
    private LlmRequest debugRequest(LlmClientRequest request) {
        try {
            JsonNode root = objectMapper.readTree(request.requestBody());
            return new LlmRequest(
                    SCENE,
                    PROMPT_VERSION,
                    messageContent(root, "system"),
                    messageContent(root, "user"),
                    root.path("model").asText(""),
                    root.path("temperature").isNumber() ? root.path("temperature").doubleValue() : null,
                    root.path("max_tokens").isIntegralNumber() ? root.path("max_tokens").intValue() : null,
                    List.of()
            );
        } catch (RuntimeException | IOException exception) {
            return new LlmRequest(SCENE, PROMPT_VERSION, null, "request summary unavailable",
                    "", null, null, List.of());
        }
    }

    /**
     * 从模型响应中提取 debug 所需的模型输出和 token 信息，响应不是 OpenAI JSON 时回退记录响应文本。
     */
    private LlmResponse debugResponse(LlmRequest request, String responseText, long durationMillis) {
        try {
            JsonNode root = objectMapper.readTree(responseText);
            JsonNode usage = root.path("usage");
            String responseModel = root.path("model").isTextual() && !root.path("model").asText().isBlank()
                    ? root.path("model").asText() : request.model();
            return new LlmResponse(
                    extractModelContent(root, responseText),
                    PROVIDER,
                    responseModel,
                    durationMillis,
                    usage.path("prompt_tokens").isIntegralNumber() ? usage.path("prompt_tokens").intValue() : null,
                    usage.path("completion_tokens").isIntegralNumber() ? usage.path("completion_tokens").intValue() : null
            );
        } catch (RuntimeException | IOException exception) {
            return new LlmResponse(responseText, PROVIDER, request.model(), durationMillis, null, null);
        }
    }

    /**
     * 拼接基础地址和 endpoint 路径，自动处理尾部和前导斜杠。
     *
     * @param baseUrl      服务基础地址
     * @param endpointPath endpoint 路径
     * @return 规范化拼接后的请求地址
     */
    private String endpointUrl(String baseUrl, String endpointPath) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedEndpointPath = endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;
        return normalizedBaseUrl + normalizedEndpointPath;
    }

    /**
     * 将 HTTP 响应字节解码为 UTF-8 文本，响应为空时抛出异常。
     *
     * @param responseBody 原始响应字节
     * @return UTF-8 非空响应文本
     */
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

    /**
     * 读取指定角色的第一条文本消息，兼容字符串 content 和 content array 结构。
     */
    private String messageContent(JsonNode root, String role) {
        JsonNode messages = root.path("messages");
        if (!messages.isArray()) {
            return null;
        }
        for (JsonNode message : messages) {
            if (!role.equals(message.path("role").asText())) {
                continue;
            }
            JsonNode content = message.path("content");
            if (content.isTextual()) {
                return content.asText();
            }
            if (content.isArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonNode contentItem : content) {
                    String text = contentItem.path("text").asText("");
                    if (!text.isBlank()) {
                        builder.append(text);
                    }
                }
                return builder.isEmpty() ? null : builder.toString();
            }
        }
        return null;
    }

    /**
     * 从 OpenAI-compatible 响应中提取首个 message.content，缺失时回退到完整响应文本用于本地排障。
     */
    private String extractModelContent(JsonNode root, String responseText) {
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            String content = choices.get(0).path("message").path("content").asText("");
            if (!content.isBlank()) {
                return content;
            }
        }
        return responseText;
    }

    /**
     * 将错误响应文本截断为单行摘要，避免大段响应进入异常信息。
     *
     * @param text 待截断的错误响应文本
     * @return 最大 500 字符的单行摘要
     */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String normalizedText = text.replaceAll("\\s+", " ").trim();
        return normalizedText.length() <= ERROR_RESPONSE_SUMMARY_MAX_LENGTH
                ? normalizedText : normalizedText.substring(0, ERROR_RESPONSE_SUMMARY_MAX_LENGTH) + "...";
    }
}
