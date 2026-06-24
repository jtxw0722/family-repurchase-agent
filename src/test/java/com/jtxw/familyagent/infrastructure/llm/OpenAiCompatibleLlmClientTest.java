package com.jtxw.familyagent.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * @Date: 2026/06/20 12:39:31
 * @Description: OpenAI-compatible 通用供应商适配器测试，验证文本图片请求、响应元数据和安全异常
 */
class OpenAiCompatibleLlmClientTest {
    /**
     * 测试用 JSON 解析器。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldBuildImageRequestAndParseContentUsageAndModel() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        String response = "{\"model\":\"actual-model\",\"choices\":[{\"message\":{\"content\":\"synthetic content\"}}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":7}}";
        HttpServer server = server(response, 200, requestBody);
        server.start();
        try {
            LlmResponse result = client(server, "safe-key", 2).chat(imageRequest());

            assertThat(result.content()).isEqualTo("synthetic content");
            assertThat(result.provider()).isEqualTo("openai-compatible");
            assertThat(result.model()).isEqualTo("actual-model");
            assertThat(result.promptTokens()).isEqualTo(12);
            assertThat(result.completionTokens()).isEqualTo(7);
            assertThat(result.durationMillis()).isNotNegative();
            assertThat(requestBody.get()).contains("request-model").contains("data:image/jpeg;base64,AQID");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldUseTextContentWhenImagesEmpty() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                200, requestBody);
        server.start();
        try {
            LlmRequest request = new LlmRequest("scene", "v1", "system", "plain user text",
                    "request-model", null, null, null);

            client(server, "safe-key", 2).chat(request);

            assertThat(requestBody.get()).contains("\"content\":\"plain user text\"")
                    .doesNotContain("image_url");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldWriteSuccessDebugFileWhenEnabled() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        String response = "{\"model\":\"actual-model\",\"choices\":[{\"message\":{\"content\":\"debug content\"}}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}";
        HttpServer server = server(response, 200, requestBody);
        server.start();
        try {
            Path debugDir = tempDir.resolve("openai-success");
            client(server, "secret-debug-key", 2, debugDir).chat(imageRequest());

            String debugJson = Files.readString(singleFile(debugDir), StandardCharsets.UTF_8);
            assertThat(debugJson)
                    .contains("\"scene\" : \"synthetic-scene\"")
                    .contains("\"promptVersion\" : \"v1\"")
                    .contains("\"content\" : \"debug content\"")
                    .doesNotContain("Authorization")
                    .doesNotContain("Bearer")
                    .doesNotContain("secret-debug-key")
                    .doesNotContain("data:image/jpeg;base64")
                    .doesNotContain("AQID");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldWriteFailureDebugFileWhenProviderReturnsNon2xx() throws Exception {
        HttpServer server = server("{\"error\":\"denied\"}", 429, new AtomicReference<>());
        server.start();
        try {
            Path debugDir = tempDir.resolve("openai-failure");
            assertThatThrownBy(() -> client(server, "secret-provider-key", 2, debugDir).chat(imageRequest()))
                    .isInstanceOf(LlmException.class);

            String debugJson = Files.readString(singleFile(debugDir), StandardCharsets.UTF_8);
            assertThat(debugJson)
                    .contains("\"success\" : false")
                    .contains("\"type\" : \"LlmException\"")
                    .doesNotContain("Authorization")
                    .doesNotContain("Bearer")
                    .doesNotContain("secret-provider-key")
                    .doesNotContain("data:image/jpeg;base64")
                    .doesNotContain("AQID");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldFallbackToRequestModelWhenResponseModelMissingAndUsageMissing() throws Exception {
        HttpServer server = server("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                200, new AtomicReference<>());
        server.start();
        try {
            LlmResponse result = client(server, "safe-key", 2).chat(imageRequest());

            assertThat(result.model()).isEqualTo("request-model");
            assertThat(result.promptTokens()).isNull();
            assertThat(result.completionTokens()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectNon2xxWithoutLeakingApiKey() throws Exception {
        HttpServer server = server("{\"error\":\"denied\"}", 429, new AtomicReference<>());
        server.start();
        try {
            assertThatThrownBy(() -> client(server, "secret-provider-key", 2).chat(imageRequest()))
                    .isInstanceOf(LlmException.class)
                    .hasMessage("LLM HTTP 响应异常：429")
                    .hasMessageNotContaining("secret-provider-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectEmptyChoices() throws Exception {
        assertResponseFailure("{\"choices\":[]}", "LLM 响应 choices 为空");
    }

    @Test
    void shouldRejectEmptyContent() throws Exception {
        assertResponseFailure("{\"choices\":[{\"message\":{\"content\":\" \"}}]}",
                "LLM 响应 content 为空");
    }

    @Test
    void shouldRejectInvalidJsonResponse() throws Exception {
        assertResponseFailure("not-json", "LLM HTTP 响应不是有效 JSON");
    }

    @Test
    void shouldReturnSafeErrorWhenRequestTimesOut() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            assertThatThrownBy(() -> client(server, "secret-timeout-key", 1).chat(imageRequest()))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("LLM 请求失败")
                    .hasMessageNotContaining("secret-timeout-key")
                    .hasMessageNotContaining("AQID");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 断言指定 HTTP 响应内容会导致 LLM 调用抛出包含预期信息的异常。
     */
    private void assertResponseFailure(String response, String message) throws Exception {
        HttpServer server = server(response, 200, new AtomicReference<>());
        server.start();
        try {
            assertThatThrownBy(() -> client(server, "safe-key", 2).chat(imageRequest()))
                    .isInstanceOf(LlmException.class)
                    .hasMessage(message);
        } finally {
            server.stop(0);
        }
    }

    /**
     * 构造包含合成图片输入的通用 LLM 请求。
     */
    private LlmRequest imageRequest() {
        return new LlmRequest("synthetic-scene", "v1", "system", "user", "request-model",
                0D, 1000, List.of(new LlmImageInput("synthetic.jpg", "image/jpeg", new byte[]{1, 2, 3})));
    }

    /**
     * 使用指定 HTTP 服务器地址、API Key 和超时创建被测客户端。
     */
    private OpenAiCompatibleLlmClient client(HttpServer server, String apiKey, int timeoutSeconds) {
        OpenAiCompatibleLlmClientSettings settings = new OpenAiCompatibleLlmClientSettings(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", apiKey, timeoutSeconds);
        return new OpenAiCompatibleLlmClient(settings, objectMapper);
    }

    /**
     * 使用开启 debug 的配置创建被测客户端。
     */
    private OpenAiCompatibleLlmClient client(HttpServer server, String apiKey, int timeoutSeconds, Path debugDir) {
        OpenAiCompatibleLlmClientSettings settings = new OpenAiCompatibleLlmClientSettings(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", apiKey, timeoutSeconds);
        LlmDebugLogProperties properties = new LlmDebugLogProperties();
        properties.setDebugLogEnabled(true);
        properties.setDebugLogDir(debugDir.toString());
        return new OpenAiCompatibleLlmClient(settings, objectMapper, new LlmDebugLogger(properties, objectMapper));
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

    /**
     * 创建本地 HTTP 服务器，返回指定响应内容和状态码，并捕获请求体。
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
}
