package com.jtxw.familyagent.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.application.LlmClientException;
import com.jtxw.familyagent.application.LlmClientRequest;
import com.jtxw.familyagent.infrastructure.config.LlmDebugLogProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 08:04:40
 * @Description: 归一化建议 OpenAI-compatible 兼容客户端测试，验证统一 LLM debug dump 写入和敏感信息边界
 */
class NormalizationOpenAiCompatibleLlmClientTest {
    /**
     * 测试使用的 JSON 解析器。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteNormalizationDebugFileWhenEnabled() throws Exception {
        AtomicReference<String> capturedRequest = new AtomicReference<>();
        String response = "{\"model\":\"normalization-response-model\","
                + "\"choices\":[{\"message\":{\"content\":\"{\\\"suggestions\\\":[]}\"}}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":6}}";
        HttpServer server = server(response, 200, capturedRequest);
        server.start();
        try {
            Path debugDir = tempDir.resolve("normalization-success");
            client(debugDir).chatCompletion(request(server, "normalization-secret-key"));

            String debugJson = Files.readString(singleFile(debugDir), StandardCharsets.UTF_8);
            assertThat(capturedRequest.get()).contains("normalization-request-model");
            assertThat(debugJson)
                    .contains("\"scene\" : \"normalization_rule_suggestions\"")
                    .contains("\"promptVersion\" : \"normalization-rule-suggestions-v1\"")
                    .contains("\"systemPrompt\" : \"system prompt\"")
                    .contains("\"userPrompt\" : \"user prompt\"")
                    .contains("\"content\" : \"{\\\"suggestions\\\":[]}\"")
                    .doesNotContain("Authorization")
                    .doesNotContain("Bearer")
                    .doesNotContain("normalization-secret-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldWriteNormalizationFailureDebugFileWhenNon2xx() throws Exception {
        HttpServer server = server("{\"error\":\"denied\"}", 500, new AtomicReference<>());
        server.start();
        try {
            Path debugDir = tempDir.resolve("normalization-failure");
            assertThatThrownBy(() -> client(debugDir).chatCompletion(request(server, "normalization-secret-key")))
                    .isInstanceOf(LlmClientException.class);

            String debugJson = Files.readString(singleFile(debugDir), StandardCharsets.UTF_8);
            assertThat(debugJson)
                    .contains("\"scene\" : \"normalization_rule_suggestions\"")
                    .contains("\"success\" : false")
                    .contains("\"type\" : \"LlmClientException\"")
                    .doesNotContain("Authorization")
                    .doesNotContain("Bearer")
                    .doesNotContain("normalization-secret-key");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 创建开启 debug 的归一化兼容客户端。
     */
    private NormalizationOpenAiCompatibleLlmClient client(Path debugDir) {
        LlmDebugLogProperties properties = new LlmDebugLogProperties();
        properties.setDebugLogEnabled(true);
        properties.setDebugLogDir(debugDir.toString());
        return new NormalizationOpenAiCompatibleLlmClient(
                objectMapper, new LlmDebugLogger(properties, objectMapper));
    }

    /**
     * 构造归一化建议链路的既有原始 LLM 请求对象。
     */
    private LlmClientRequest request(HttpServer server, String apiKey) {
        String requestBody = "{\"model\":\"normalization-request-model\","
                + "\"messages\":[{\"role\":\"system\",\"content\":\"system prompt\"},"
                + "{\"role\":\"user\",\"content\":\"user prompt\"}],"
                + "\"temperature\":0.8,\"max_tokens\":100}";
        return new LlmClientRequest(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                apiKey,
                2,
                requestBody
        );
    }

    /**
     * 创建本地 HTTP 服务，返回指定响应并捕获请求体。
     */
    private HttpServer server(String responseBody, int statusCode, AtomicReference<String> requestBody)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (exchange; var response = exchange.getResponseBody()) {
                response.write(responseBytes);
            }
        });
        return server;
    }

    /**
     * 读取 debug 目录中唯一生成的文件。
     */
    private Path singleFile(Path debugDir) throws IOException {
        try (var files = Files.list(debugDir)) {
            List<Path> debugFiles = files.toList();
            assertThat(debugFiles).hasSize(1);
            return debugFiles.get(0);
        }
    }
}
