package com.jtxw.familyagent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/05/28/00:19
 * @Description: 本地 Spring Boot REST Tool API 客户端
 */
public class FamilyAgentRestClient {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final URI apiBaseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FamilyAgentRestClient(URI apiBaseUri, ObjectMapper objectMapper) {
        this.apiBaseUri = apiBaseUri;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 以 JSON POST 调用主项目 REST Tool API，并兼容非 JSON 错误响应
     *
     * @param path REST API 路径
     * @param body 请求体
     * @return 响应 JSON 对象
     */
    public Map<String, Object> postJson(String path, Map<String, Object> body) {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ToolExecutionException("Failed to serialize REST request body", e);
        }

        HttpRequest request = HttpRequest.newBuilder(apiBaseUri.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ToolExecutionException("Failed to connect to Family Consumption Agent backend at " + apiBaseUri + ": " + e.getMessage(), e);
        }

        Map<String, Object> data = parseResponseBody(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Object error = data.get("error");
            Object message = data.get("message");
            Object raw = data.get("raw");
            throw new ToolExecutionException(String.valueOf(error != null ? error : message != null ? message : raw != null ? raw : "REST API request failed: HTTP " + response.statusCode()));
        }
        return data;
    }

    Map<String, Object> parseResponseBody(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(text, MAP_TYPE);
        } catch (JsonProcessingException e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("raw", text);
            return result;
        }
    }
}
