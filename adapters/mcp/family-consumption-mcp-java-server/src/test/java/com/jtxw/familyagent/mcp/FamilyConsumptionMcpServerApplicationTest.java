package com.jtxw.familyagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FamilyConsumptionMcpServerApplicationTest {
    @Test
    void comparePriceShouldReturnToolErrorWhenRequiredArgumentIsMissing() {
        FamilyConsumptionMcpServerApplication application = new FamilyConsumptionMcpServerApplication(
                new ObjectMapper(),
                new FakeRestClient(),
                new ImportFilePathValidator(java.util.List.of(Path.of("examples").toAbsolutePath().normalize()))
        );

        McpSchema.CallToolResult result = application.comparePriceTool().callHandler().apply(null,
                McpSchema.CallToolRequest.builder()
                        .name("compare_price")
                        .arguments(Map.of("productName", "猫砂"))
                        .build()
        );

        assertThat(result.isError()).isTrue();
        assertThat(result.content().get(0)).extracting("text").isEqualTo("price must be a positive number");
    }

    @Test
    @SuppressWarnings("unchecked")
    void comparePriceOutputSchemaShouldMatchRestResponseFields() {
        FamilyConsumptionMcpServerApplication application = new FamilyConsumptionMcpServerApplication(
                new ObjectMapper(),
                new FakeRestClient(),
                new ImportFilePathValidator(java.util.List.of(Path.of("examples").toAbsolutePath().normalize()))
        );

        Map<String, Object> outputSchema = application.comparePriceTool().tool().outputSchema();
        Map<String, Object> properties = (Map<String, Object>) outputSchema.get("properties");

        assertThat(outputSchema).containsEntry("additionalProperties", false);
        assertThat(properties.keySet()).containsAll(Set.of(
                "productName",
                "normalizedName",
                "currentUnitPrice",
                "unit",
                "historicalMin",
                "historicalMedian",
                "historicalAverage",
                "sampleSize",
                "decision",
                "decisionText",
                "reason"
        ));
        assertThat(properties.keySet()).doesNotContain(
                "historicalAverageUnitPrice",
                "historicalMinUnitPrice",
                "historicalMaxUnitPrice",
                "reviewRequired"
        );
        assertThat((Map<String, Object>) properties.get("historicalMin"))
                .containsEntry("type", java.util.List.of("number", "null"));
        assertThat((Map<String, Object>) properties.get("historicalMedian"))
                .containsEntry("type", java.util.List.of("number", "null"));
        assertThat((Map<String, Object>) properties.get("historicalAverage"))
                .containsEntry("type", java.util.List.of("number", "null"));
    }

    private static class FakeRestClient extends FamilyAgentRestClient {
        FakeRestClient() {
            super(URI.create("http://localhost:8080"), new ObjectMapper());
        }

        @Override
        public Map<String, Object> postJson(String path, Map<String, Object> body) {
            return Map.of("message", "ok");
        }
    }
}
