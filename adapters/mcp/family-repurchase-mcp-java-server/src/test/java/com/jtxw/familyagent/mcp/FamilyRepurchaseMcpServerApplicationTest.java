package com.jtxw.familyagent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
        assertThat(result.content().get(0)).extracting("text").isEqualTo("price 必须是正数");
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

    @Test
    @SuppressWarnings("unchecked")
    void searchPurchaseRecordsSchemaShouldReturnRawRecordsWithoutBaselineFields() {
        FamilyRepurchaseMcpServerApplication application = new FamilyRepurchaseMcpServerApplication(
                new ObjectMapper(),
                new FakeRestClient(),
                new ImportFilePathValidator(java.util.List.of(Path.of("examples").toAbsolutePath().normalize()))
        );

        Map<String, Object> inputSchema = application.searchPurchaseRecordsTool().tool().inputSchema();
        Map<String, Object> inputProperties = (Map<String, Object>) inputSchema.get("properties");
        assertThat(inputProperties.keySet()).containsAll(Set.of("keyword", "owner", "limit", "fromDate", "toDate"));
        assertThat(inputSchema.get("required")).isEqualTo(List.of("keyword"));
        assertThat((Map<String, Object>) inputProperties.get("owner"))
                .containsEntry("type", List.of("string", "null"));
        assertThat((Map<String, Object>) inputProperties.get("fromDate"))
                .containsEntry("type", List.of("string", "null"));
        assertThat((Map<String, Object>) inputProperties.get("toDate"))
                .containsEntry("type", List.of("string", "null"));
        assertThat((Map<String, Object>) inputProperties.get("limit"))
                .containsEntry("type", "integer");

        Map<String, Object> outputSchema = application.searchPurchaseRecordsTool().tool().outputSchema();
        Map<String, Object> outputProperties = (Map<String, Object>) outputSchema.get("properties");
        assertThat(outputProperties.keySet()).containsAll(Set.of(
                "keyword", "scope", "owner", "matchedCount", "returnedCount", "records", "warnings"
        ));
        assertThat(outputProperties.keySet()).doesNotContain("baseline", "roughBaseline", "fallbackBaseline");

        Map<String, Object> records = (Map<String, Object>) outputProperties.get("records");
        Map<String, Object> recordItem = (Map<String, Object>) records.get("items");
        Map<String, Object> recordProperties = (Map<String, Object>) recordItem.get("properties");
        assertThat(recordProperties.keySet()).containsAll(Set.of(
                "recordId", "orderTime", "platform", "owner", "productName", "sku", "category",
                "subCategory", "quantity", "unit", "totalAmount", "currency", "normalizedName", "unitPrice"
        ));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchPurchaseRecordsShouldForwardToBackendSearchEndpoint() {
        CaptureRestClient restClient = new CaptureRestClient();
        FamilyRepurchaseMcpServerApplication application = new FamilyRepurchaseMcpServerApplication(
                new ObjectMapper(),
                restClient,
                new ImportFilePathValidator(java.util.List.of(Path.of("examples").toAbsolutePath().normalize()))
        );

        McpSchema.CallToolResult result = application.searchPurchaseRecordsTool().callHandler().apply(null,
                McpSchema.CallToolRequest.builder()
                        .name("search_purchase_records")
                        .arguments(Map.of(
                                "keyword", "猫砂",
                                "owner", "   ",
                                "limit", 10,
                                "fromDate", "2025-01-01",
                                "toDate", "2026-06-12"
                        ))
                        .build()
        );

        assertThat(result.isError()).isFalse();
        assertThat(restClient.path).isEqualTo("/api/tools/purchase-records/search");
        assertThat(restClient.body).containsEntry("keyword", "猫砂");
        assertThat(restClient.body).containsEntry("limit", 10);
        assertThat(restClient.body).containsEntry("fromDate", "2025-01-01");
        assertThat(restClient.body).containsEntry("toDate", "2026-06-12");
        assertThat(restClient.body).doesNotContainKey("owner");
    }

    @Test
    void searchPurchaseRecordsShouldNotForwardNullOwnerToBackend() {
        CaptureRestClient restClient = new CaptureRestClient();
        FamilyRepurchaseMcpServerApplication application = new FamilyRepurchaseMcpServerApplication(
                new ObjectMapper(),
                restClient,
                new ImportFilePathValidator(java.util.List.of(Path.of("examples").toAbsolutePath().normalize()))
        );
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("keyword", "猫砂");
        arguments.put("owner", null);
        arguments.put("limit", 10);

        McpSchema.CallToolResult result = application.searchPurchaseRecordsTool().callHandler().apply(null,
                McpSchema.CallToolRequest.builder()
                        .name("search_purchase_records")
                        .arguments(arguments)
                        .build()
        );

        assertThat(result.isError()).isFalse();
        assertThat(restClient.path).isEqualTo("/api/tools/purchase-records/search");
        assertThat(restClient.body).containsEntry("keyword", "猫砂");
        assertThat(restClient.body).containsEntry("limit", 10);
        assertThat(restClient.body).doesNotContainKey("owner");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchPurchaseRecordsShouldReturnFamilyScopeAndWarnings() {
        FamilyRepurchaseMcpServerApplication application = new FamilyRepurchaseMcpServerApplication(
                new ObjectMapper(),
                new SearchFakeRestClient(),
                new ImportFilePathValidator(java.util.List.of(Path.of("examples").toAbsolutePath().normalize()))
        );

        McpSchema.CallToolResult result = application.searchPurchaseRecordsTool().callHandler().apply(null,
                McpSchema.CallToolRequest.builder()
                        .name("search_purchase_records")
                        .arguments(Map.of("keyword", "猫砂", "limit", 10))
                        .build()
        );

        assertThat(result.isError()).isFalse();
        Map<String, Object> structuredContent = (Map<String, Object>) result.structuredContent();
        assertThat(structuredContent).containsEntry("scope", "FAMILY");
        assertThat((List<String>) structuredContent.get("warnings")).isNotEmpty();
        assertThat((List<Map<String, Object>>) structuredContent.get("records")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordPurchaseSchemaShouldMatchBackendFields() {
        FamilyRepurchaseMcpServerApplication application = new FamilyRepurchaseMcpServerApplication(
                new ObjectMapper(),
                new FakeRestClient(),
                new ImportFilePathValidator(java.util.List.of(Path.of("examples").toAbsolutePath().normalize()))
        );

        Map<String, Object> inputSchema = application.recordPurchaseTool().tool().inputSchema();
        Map<String, Object> inputProperties = (Map<String, Object>) inputSchema.get("properties");
        assertThat(inputProperties.keySet()).containsAll(Set.of("dryRun", "records"));
        assertThat(inputSchema.get("required")).isEqualTo(List.of("dryRun", "records"));

        Map<String, Object> records = (Map<String, Object>) inputProperties.get("records");
        Map<String, Object> recordItem = (Map<String, Object>) records.get("items");
        Map<String, Object> recordProperties = (Map<String, Object>) recordItem.get("properties");
        assertThat(recordProperties.keySet()).containsAll(Set.of(
                "productName", "price", "quantity", "unit", "platform", "purchaseDate",
                "owner", "shopName", "sku", "note", "sourceText"
        ));
        assertThat(recordItem.get("required")).isEqualTo(List.of("productName", "price", "quantity", "unit"));

        Map<String, Object> outputSchema = application.recordPurchaseTool().tool().outputSchema();
        Map<String, Object> outputProperties = (Map<String, Object>) outputSchema.get("properties");
        assertThat(outputProperties.keySet()).containsAll(Set.of("dryRun", "savedCount", "reviewCount", "records"));
        Map<String, Object> outputRecords = (Map<String, Object>) outputProperties.get("records");
        Map<String, Object> outputRecordItem = (Map<String, Object>) outputRecords.get("items");
        Map<String, Object> outputRecordProperties = (Map<String, Object>) outputRecordItem.get("properties");
        assertThat(outputRecordProperties.keySet()).containsAll(Set.of(
                "recordId", "productName", "normalizedName", "price", "quantity", "unit",
                "unitPrice", "decision", "reviewRequired", "reviewReasons"
        ));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordPurchaseShouldForwardToBackendRecordEndpoint() {
        CaptureRestClient restClient = new CaptureRestClient();
        FamilyRepurchaseMcpServerApplication application = new FamilyRepurchaseMcpServerApplication(
                new ObjectMapper(),
                restClient,
                new ImportFilePathValidator(java.util.List.of(Path.of("examples").toAbsolutePath().normalize()))
        );

        McpSchema.CallToolResult result = application.recordPurchaseTool().callHandler().apply(null,
                McpSchema.CallToolRequest.builder()
                        .name("record_purchase")
                        .arguments(Map.of(
                                "dryRun", false,
                                "records", List.of(recordPurchaseArgs())
                        ))
                        .build()
        );

        assertThat(result.isError()).isFalse();
        assertThat(restClient.path).isEqualTo("/api/tools/record-purchase");
        assertThat(restClient.body).containsEntry("dryRun", false);
        List<Map<String, Object>> records = (List<Map<String, Object>>) restClient.body.get("records");
        assertThat(records.get(0)).containsEntry("productName", "猫砂");
        assertThat(records.get(0)).containsEntry("unit", "kg");
    }

    private Map<String, Object> recordPurchaseArgs() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("productName", "猫砂");
        record.put("price", 109.9);
        record.put("quantity", 24);
        record.put("unit", "kg");
        record.put("platform", "JD");
        record.put("purchaseDate", "2026-06-04");
        record.put("owner", "jtxw");
        record.put("shopName", "京东自营");
        record.put("sku", "6kg*4包");
        record.put("note", "手动录入");
        record.put("sourceText", "昨天在京东买了猫砂");
        return record;
    }

    private static class FakeRestClient extends FamilyAgentRestClient {
        FakeRestClient() {
            super(URI.create("http://127.0.0.1:8080"), new ObjectMapper());
        }

        @Override
        public Map<String, Object> postJson(String path, Map<String, Object> body) {
            return Map.of("message", "ok");
        }
    }

    private static class CaptureRestClient extends FamilyAgentRestClient {
        String path;
        Map<String, Object> body;

        CaptureRestClient() {
            super(URI.create("http://127.0.0.1:8080"), new ObjectMapper());
        }

        @Override
        public Map<String, Object> postJson(String path, Map<String, Object> body) {
            this.path = path;
            this.body = body;
            return Map.of("dryRun", false, "savedCount", 1, "reviewCount", 0, "records", List.of());
        }
    }

    private static class SearchFakeRestClient extends FamilyAgentRestClient {
        SearchFakeRestClient() {
            super(URI.create("http://127.0.0.1:8080"), new ObjectMapper());
        }

        @Override
        public Map<String, Object> postJson(String path, Map<String, Object> body) {
            return Map.of(
                    "keyword", "猫砂",
                    "scope", "FAMILY",
                    "matchedCount", 1,
                    "returnedCount", 1,
                    "records", List.of(Map.of(
                            "recordId", 123,
                            "productName", "名创优品猫砂",
                            "sku", "混合猫砂 40kg"
                    )),
                    "warnings", List.of("该结果来自原始订单记录检索，不代表已完成商品归一化。")
            );
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
