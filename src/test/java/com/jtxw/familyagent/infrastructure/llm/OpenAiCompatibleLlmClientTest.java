package com.jtxw.familyagent.infrastructure.llm;

import com.jtxw.familyagent.application.LlmClientException;
import com.jtxw.familyagent.application.LlmClientRequest;
import com.jtxw.familyagent.application.LlmClientResponse;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/10 18:47:43
 * @Description: OpenAI-compatible LLM HTTP 客户端测试，验证原始响应、异常信息、请求头和超时行为。
 */
class OpenAiCompatibleLlmClientTest {
    /**
     * 测试用 LLM HTTP 客户端实例。
     */
    private final OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient();

    @Test
    void shouldReturnRawResponseWhenStatusIs2xx() throws Exception {
        byte[] responseBody = "{\"choices\":[{\"message\":{\"content\":\"[]\"}}]}".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(responseBody, "application/json", 200);
        server.start();
        try {
            LlmClientResponse response = client.chatCompletion(request(server, "test-key", "{}"));

            assertThat(response.httpStatus()).isEqualTo(200);
            assertThat(response.contentType()).contains("application/json");
            assertThat(response.responseBytes()).isEqualTo(responseBody.length);
            assertThat(response.body()).isEqualTo(new String(responseBody, StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldThrowLlmClientExceptionWhenStatusIsNot2xx() throws Exception {
        byte[] responseBody = "{\"error\":\"quota exceeded\"}".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(responseBody, "application/octet-stream", 429);
        server.start();
        try {
            assertThatThrownBy(() -> client.chatCompletion(request(server, "test-key", "{}")))
                    .isInstanceOfSatisfying(LlmClientException.class, exception -> {
                        assertThat(exception.httpStatus()).isEqualTo(429);
                        assertThat(exception.contentType()).contains("application/octet-stream");
                        assertThat(exception.responseBytes()).isEqualTo(responseBody.length);
                        assertThat(exception.responseBody()).isEqualTo(new String(responseBody, StandardCharsets.UTF_8));
                        assertThat(exception.getMessage())
                                .contains("LLM HTTP 响应异常：429")
                                .contains("quota exceeded")
                                .doesNotContain("test-key");
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldThrowClearExceptionWhenResponseBodyIsEmpty() throws Exception {
        HttpServer server = server(new byte[0], "application/json", 200);
        server.start();
        try {
            assertThatThrownBy(() -> client.chatCompletion(request(server, "test-key", "{}")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("LLM 返回空响应");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSendAuthorizationHeaderWithoutLeakingApiKeyInAssertionMessage() throws Exception {
        AtomicReference<String> authorizationHeader = new AtomicReference<>("");
        AtomicReference<String> contentTypeHeader = new AtomicReference<>("");
        AtomicReference<String> acceptHeader = new AtomicReference<>("");
        byte[] responseBody = "{\"choices\":[{\"message\":{\"content\":\"[]\"}}]}".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentTypeHeader.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            acceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBody.length);
            try (exchange; var response = exchange.getResponseBody()) {
                response.write(responseBody);
            }
        });
        server.start();
        try {
            client.chatCompletion(request(server, "secret-token", "{}"));

            assertThat(authorizationHeader.get()).startsWith("Bearer ");
            assertThat(authorizationHeader.get()).endsWith("secret-token");
            assertThat(contentTypeHeader.get()).contains("application/json");
            assertThat(acceptHeader.get()).contains("application/json");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRespectReadTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(1500L);
                byte[] responseBody = "{\"choices\":[{\"message\":{\"content\":\"[]\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBody.length);
                exchange.getResponseBody().write(responseBody);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            assertThatThrownBy(() -> client.chatCompletion(request(server, "test-key", "{}")))
                    .hasRootCauseInstanceOf(SocketTimeoutException.class);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(byte[] responseBody, String contentType, int statusCode) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(statusCode, responseBody.length);
            try (exchange; var response = exchange.getResponseBody()) {
                response.write(responseBody);
            }
        });
        return server;
    }

    private LlmClientRequest request(HttpServer server, String apiKey, String requestBody) {
        return new LlmClientRequest(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                apiKey,
                1,
                requestBody
        );
    }
}
