package com.jtxw.familyagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FamilyAgentRestClientTest {
    private final FamilyAgentRestClient client = new FamilyAgentRestClient(URI.create("http://127.0.0.1:8080"), new ObjectMapper());

    @Test
    void shouldParseJsonResponseBody() {
        Map<String, Object> result = client.parseResponseBody("{\"message\":\"ok\",\"count\":2}");

        assertThat(result).containsEntry("message", "ok");
        assertThat(result).containsEntry("count", 2);
    }

    @Test
    void shouldKeepRawTextWhenResponseIsNotJson() {
        Map<String, Object> result = client.parseResponseBody("backend unavailable");

        assertThat(result).containsEntry("raw", "backend unavailable");
    }

    @Test
    void shouldPostJsonToBackend() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/tools/compare-price", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"message\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        });
        server.start();

        try {
            URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            FamilyAgentRestClient restClient = new FamilyAgentRestClient(baseUri, new ObjectMapper());

            Map<String, Object> result = restClient.postJson("/api/tools/compare-price", Map.of("productName", "猫砂"));

            assertThat(result).containsEntry("message", "ok");
            assertThat(requestBody.get()).contains("\"productName\":\"猫砂\"");
        } finally {
            server.stop(0);
        }
    }
}
