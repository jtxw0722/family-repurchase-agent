package com.jtxw.familyagent.infrastructure.llm;

import com.jtxw.familyagent.application.LlmClient;
import com.jtxw.familyagent.application.LlmClientException;
import com.jtxw.familyagent.application.LlmClientRequest;
import com.jtxw.familyagent.application.LlmClientResponse;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 10:35:42
 * @Description: 归一化建议链路兼容客户端，保留原始 HTTP 请求响应契约以避免本轮职责重构影响既有业务
 */
@Service
public class NormalizationOpenAiCompatibleLlmClient implements LlmClient {
    /**
     * OpenAI-compatible Chat Completions endpoint 路径。
     */
    private static final String OPENAI_CHAT_COMPLETIONS_PATH = "/chat/completions";

    /**
     * 发送归一化链路已经序列化的 Chat Completions 请求。
     *
     * @param request 既有 LLM 调用请求
     * @return 既有原始 HTTP 响应
     */
    @Override
    public LlmClientResponse chatCompletion(LlmClientRequest request) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = Math.max(1, request.requestTimeoutSeconds()) * 1000;
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        return restClient.post()
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
     * 将错误响应文本截断为单行摘要，避免大段响应进入异常信息。
     *
     * @param text 待截断的错误响应文本
     * @return 最多 500 字符的单行摘要
     */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String normalizedText = text.replaceAll("\\s+", " ").trim();
        int maxLength = 500;
        return normalizedText.length() <= maxLength ? normalizedText : normalizedText.substring(0, maxLength) + "...";
    }
}
