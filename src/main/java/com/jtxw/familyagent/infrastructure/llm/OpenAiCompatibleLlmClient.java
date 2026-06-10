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
 * @Date: 2026/06/10 17:48:31
 * @Description: OpenAI-compatible LLM HTTP 客户端，负责调用 Chat Completions endpoint 并返回原始响应。
 */
@Service
public class OpenAiCompatibleLlmClient implements LlmClient {
    /**
     * OpenAI-compatible Chat Completions endpoint 路径。
     */
    private static final String OPENAI_CHAT_COMPLETIONS_PATH = "/chat/completions";

    /**
     * 发送 OpenAI-compatible Chat Completions HTTP 请求。
     *
     * @param request LLM 调用请求，包含 baseUrl、API Key、超时时间和请求体
     * @return 原始 HTTP 响应，包含状态码、响应类型、响应体积和响应文本
     */
    @Override
    public LlmClientResponse chatCompletion(LlmClientRequest request) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = Math.max(1, request.requestTimeoutSeconds()) * 1000;
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
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

    private String endpointUrl(String baseUrl, String endpointPath) {
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedEndpointPath = endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;
        return normalizedBaseUrl + normalizedEndpointPath;
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
}
