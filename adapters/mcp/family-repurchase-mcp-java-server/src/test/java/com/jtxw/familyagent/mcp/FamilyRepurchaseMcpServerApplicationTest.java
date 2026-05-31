package com.jtxw.familyagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FamilyRepurchaseMcpServerApplicationTest {
    @Test
    void comparePriceShouldReturnToolErrorWhenRequiredArgumentIsMissing() {
        FamilyRepurchaseMcpServerApplication application = new FamilyRepurchaseMcpServerApplication(
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
        FamilyRepurchaseMcpServerApplication application = new FamilyRepurchaseMcpServerApplication(
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
                "current",
                "baseline",
                "decision",
                "evidence",
                "warnings"
        ));
        assertThat(properties.keySet()).doesNotContain(
                "currentUnitPrice",
                "unit",
                "historicalMin",
                "historicalMedian",
                "historicalAverage",
                "sampleSize",
                "decisionText",
                "reason",
                "historicalAverageUnitPrice",
                "historicalMinUnitPrice",
                "historicalMaxUnitPrice",
                "reviewRequired"
        );
        Map<String, Object> baseline = (Map<String, Object>) properties.get("baseline");
        Map<String, Object> baselineProperties = (Map<String, Object>) baseline.get("properties");
        assertThat(baselineProperties.keySet()).containsAll(Set.of(
                "sampleSize", "unit", "historicalMin", "historicalMedian", "historicalAverage", "dateRange"
        ));
        assertThat((Map<String, Object>) baselineProperties.get("historicalMin"))
                .containsEntry("type", java.util.List.of("number", "null"));
        assertThat((Map<String, Object>) baselineProperties.get("historicalMedian"))
                .containsEntry("type", java.util.List.of("number", "null"));
        assertThat((Map<String, Object>) baselineProperties.get("historicalAverage"))
                .containsEntry("type", java.util.List.of("number", "null"));

        Map<String, Object> current = (Map<String, Object>) properties.get("current");
        Map<String, Object> currentProperties = (Map<String, Object>) current.get("properties");
        assertThat(currentProperties.keySet()).containsAll(Set.of("price", "quantity", "unit", "unitPrice", "formula"));

        Map<String, Object> decision = (Map<String, Object>) properties.get("decision");
        Map<String, Object> decisionProperties = (Map<String, Object>) decision.get("properties");
        assertThat(decisionProperties.keySet()).containsAll(Set.of("code", "text", "reason", "confidence"));

        Map<String, Object> evidence = (Map<String, Object>) properties.get("evidence");
        Map<String, Object> evidenceProperties = (Map<String, Object>) evidence.get("properties");
        assertThat(evidenceProperties.keySet()).containsAll(Set.of(
                "source", "sourceRecords", "excludedRecordCount", "excludedReasons", "outliers"
        ));

        Map<String, Object> sourceRecords = (Map<String, Object>) evidenceProperties.get("sourceRecords");
        Map<String, Object> sourceRecordSchema = (Map<String, Object>) sourceRecords.get("items");
        Map<String, Object> sourceRecordProperties = (Map<String, Object>) sourceRecordSchema.get("properties");
        assertThat(sourceRecordProperties.keySet()).containsAll(Set.of(
                "recordId",
                "role",
                "purchaseDate",
                "productName",
                "price",
                "quantity",
                "unit",
                "unitPrice",
                "unitPriceUnit",
                "originalQuantity",
                "originalUnit"
        ));
    }

    /**
     * 验证 generate_report 的输出 schema 覆盖 REST 返回字段，避免 structuredContent 校验失败。
     */
    @Test
    @SuppressWarnings("unchecked")
    void generateReportOutputSchemaShouldMatchRestResponseFields() {
        FamilyRepurchaseMcpServerApplication application = new FamilyRepurchaseMcpServerApplication(
                new ObjectMapper(),
                new FakeRestClient(),
                new ImportFilePathValidator(java.util.List.of(Path.of("examples").toAbsolutePath().normalize()))
        );

        Map<String, Object> outputSchema = application.generateReportTool().tool().outputSchema();
        Map<String, Object> properties = (Map<String, Object>) outputSchema.get("properties");

        assertThat(outputSchema).containsEntry("additionalProperties", false);
        assertThat(properties.keySet()).containsAll(Set.of(
                "month",
                "recordCount",
                "totalAmount",
                "pendingReviewCount",
                "reportPath",
                "message"
        ));
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

    @Test
    @SuppressWarnings("unchecked")
    void comparePriceShouldReturnStructuredContentWhenOutputSchemaExists() {
        FamilyRepurchaseMcpServerApplication application = new FamilyRepurchaseMcpServerApplication(
                new ObjectMapper(),
                new FakeRestClient(),
                new ImportFilePathValidator(java.util.List.of(Path.of("examples").toAbsolutePath().normalize()))
        );

        McpSchema.CallToolResult result = application.comparePriceTool().callHandler().apply(null,
                McpSchema.CallToolRequest.builder()
                        .name("compare_price")
                        .arguments(Map.of(
                                "productName", "膨润土猫砂",
                                "price", 10.3,
                                "quantity", 5,
                                "unit", "kg"
                        ))
                        .build()
        );

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isInstanceOf(Map.class);

        Map<String, Object> structuredContent = (Map<String, Object>) result.structuredContent();
        assertThat(structuredContent).containsEntry("message", "ok");
    }
}
