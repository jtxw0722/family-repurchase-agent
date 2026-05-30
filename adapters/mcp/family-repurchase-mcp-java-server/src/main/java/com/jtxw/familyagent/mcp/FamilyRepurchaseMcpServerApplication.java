package com.jtxw.familyagent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * @Author: jtxw
 * @Date: 2026/05/28/00:28
 * @Description: Family Repurchase Agent Java MCP stdio Server 启动入口
 */
public class FamilyRepurchaseMcpServerApplication {
    private static final String SERVER_NAME = "family-repurchase-agent";
    private static final String SERVER_VERSION = "0.4.0";

    private final ObjectMapper objectMapper;
    private final FamilyAgentRestClient restClient;
    private final ImportFilePathValidator importFilePathValidator;

    public FamilyRepurchaseMcpServerApplication(ObjectMapper objectMapper,
                                                 FamilyAgentRestClient restClient,
                                                 ImportFilePathValidator importFilePathValidator) {
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        this.importFilePathValidator = importFilePathValidator;
    }

    public static void main(String[] args) {
        ObjectMapper objectMapper = new ObjectMapper();
        McpJsonMapper mcpJsonMapper = new JacksonMcpJsonMapper(objectMapper);
        AppConfig config = AppConfig.fromEnv(System.getenv());
        FamilyRepurchaseMcpServerApplication application = new FamilyRepurchaseMcpServerApplication(
                objectMapper,
                new FamilyAgentRestClient(config.apiBaseUri(), objectMapper),
                new ImportFilePathValidator(config.importAllowedDirs())
        );

        EofAwareInputStream inputStream = new EofAwareInputStream(System.in);
        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(mcpJsonMapper, inputStream, System.out);
        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions("""
                该 MCP Server 暴露本地家庭复购品价格决策工具。
                价格计算、历史价格对比和购买记录判断均由本地 Spring Boot 后端完成。
                禁止使用这些工具访问电商网站、Cookie、浏览器会话或外部账号。
                import_file 工具只接受配置的安全导入目录下的文件。
                """)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .validateToolInputs(false)
                .tools(application.importFileTool(), application.comparePriceTool(), application.generateReportTool())
                .build();
        inputStream.awaitEndOfInput();
        waitForPendingMessages();
        server.closeGracefully();
    }

    McpServerFeatures.SyncToolSpecification importFileTool() {
        return new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder("import_file")
                        .title("Import Order File")
                        .description("导入本地 CSV 或 Excel 订单文件，并生成购买记录和待复核记录。")
                        .annotations(toolAnnotations("Import Order File", false, false, false))
                        .inputSchema(objectSchema(
                                properties(
                                        property("filePath", stringSchema("本地 CSV 或 Excel 文件路径")),
                                        property("owner", stringSchema("订单所属人，可选"))
                                ),
                                List.of("filePath")
                        ))
                        .outputSchema(objectSchema(properties(
                                property("batchId", numberSchema("导入批次 ID")),
                                property("totalCount", numberSchema("文件总记录数")),
                                property("importedCount", numberSchema("成功导入记录数")),
                                property("reviewCount", numberSchema("待复核记录数")),
                                property("message", stringSchema("导入结果说明"))
                        )))
                        .build(),
                this::handleImportFile
        );
    }

    McpServerFeatures.SyncToolSpecification comparePriceTool() {
        return new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder("compare_price")
                        .title("Compare Product Price")
                        .description("比较当前商品单位价格与本地历史价格，返回价格判断结果。")
                        .annotations(toolAnnotations("Compare Product Price", true, false, true))
                        .inputSchema(objectSchema(
                                properties(
                                        property("productName", stringSchema("商品名称")),
                                        property("price", numberSchema("当前总价或实付金额")),
                                        property("quantity", numberSchema("当前数量")),
                                        property("unit", stringSchema("计量单位"))
                                ),
                                List.of("productName", "price", "quantity", "unit")
                        ))
                        .outputSchema(objectSchema(properties(
                                property("productName", stringSchema("原始商品名称")),
                                property("normalizedName", stringSchema("标准化商品名称")),
                                property("current", objectSchema(properties(
                                        property("price", numberSchema("当前总价")),
                                        property("quantity", numberSchema("当前数量")),
                                        property("unit", stringSchema("计量单位")),
                                        property("unitPrice", numberSchema("当前单位价格")),
                                        property("formula", stringSchema("单位价格计算表达式"))
                                ))),
                                property("baseline", objectSchema(properties(
                                        property("sampleSize", numberSchema("历史样本数量")),
                                        property("unit", stringSchema("历史统计统一单位")),
                                        property("historicalMin", nullableNumberSchema("历史最低单位价格")),
                                        property("historicalMedian", nullableNumberSchema("历史中位数单位价格")),
                                        property("historicalAverage", nullableNumberSchema("历史平均单位价格")),
                                        property("dateRange", nullableObjectSchema(properties(
                                                property("from", stringSchema("历史样本最早购买日期")),
                                                property("to", stringSchema("历史样本最晚购买日期"))
                                        )))
                                ))),
                                property("decision", objectSchema(properties(
                                        property("code", stringSchema("价格判断编码")),
                                        property("text", stringSchema("价格判断展示文本")),
                                        property("reason", stringSchema("判断原因")),
                                        property("confidence", stringSchema("判断置信度"))
                                ))),
                                property("evidence", objectSchema(properties(
                                        property("source", stringSchema("证据来源")),
                                        property("sourceRecords", arraySchema(sourceRecordOutputSchema())),
                                        property("excludedRecordCount", numberSchema("被排除记录数量")),
                                        property("excludedReasons", arraySchema(stringSchema("排除原因"))),
                                        property("outliers", arraySchema(sourceRecordOutputSchema()))
                                ))),
                                property("warnings", arraySchema(stringSchema("风险提示")))
                        )))
                        .build(),
                this::handleComparePrice
        );
    }

    McpServerFeatures.SyncToolSpecification generateReportTool() {
        return new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder("generate_report")
                        .title("Generate Repurchase Price Report")
                        .description("根据指定月份生成 Markdown 价格报告。")
                        .annotations(toolAnnotations("Generate Repurchase Price Report", false, false, true))
                        .inputSchema(objectSchema(
                                properties(property("month", stringSchema("报告月份，格式为 yyyy-MM"))),
                                List.of("month")
                        ))
                        .outputSchema(objectSchema(properties(
                                property("month", stringSchema("报告月份")),
                                property("reportPath", stringSchema("本地 Markdown 报告路径")),
                                property("markdown", stringSchema("报告 Markdown 内容")),
                                property("message", stringSchema("报告生成结果说明"))
                        )))
                        .build(),
                this::handleGenerateReport
        );
    }

    private McpSchema.CallToolResult handleImportFile(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> args = arguments(request);
            ImportFilePathValidator.SafeImportFile safeFile = importFilePathValidator.validate(requireString(args, "filePath"));

            // 校验使用绝对路径，请求仍传原始路径，保持与 Spring Boot 后端的相对路径行为兼容
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("filePath", safeFile.originalPath());
            String owner = optionalString(args, "owner");
            if (owner != null) {
                body.put("owner", owner);
            }
            return toolSuccess(restClient.postJson("/api/tools/import-file", body));
        } catch (Exception e) {
            return toolError(e);
        }
    }

    private McpSchema.CallToolResult handleComparePrice(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> args = arguments(request);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("productName", requireString(args, "productName"));
            body.put("price", requirePositiveNumber(args, "price"));
            body.put("quantity", requirePositiveNumber(args, "quantity"));
            body.put("unit", requireString(args, "unit"));
            return toolSuccess(restClient.postJson("/api/tools/compare-price", body));
        } catch (Exception e) {
            return toolError(e);
        }
    }

    private McpSchema.CallToolResult handleGenerateReport(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> args = arguments(request);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("month", requireString(args, "month"));
            return toolSuccess(restClient.postJson("/api/tools/generate-report", body));
        } catch (Exception e) {
            return toolError(e);
        }
    }

    private McpSchema.CallToolResult toolSuccess(Map<String, Object> data) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(toJson(data))
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult toolError(Exception e) {
        String message = e.getMessage() == null || e.getMessage().isBlank() ? "Tool execution failed" : e.getMessage();
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }

    private Map<String, Object> arguments(McpSchema.CallToolRequest request) {
        return request.arguments() == null ? Map.of() : request.arguments();
    }

    private String requireString(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        throw new ToolExecutionException(name + " must be a non-empty string");
    }

    private String optionalString(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        throw new ToolExecutionException(name + " must be a non-empty string when provided");
    }

    private BigDecimal requirePositiveNumber(Map<String, Object> args, String name) {
        Object value = args.get(name);
        BigDecimal number;
        if (value instanceof Number numericValue) {
            number = new BigDecimal(numericValue.toString());
        } else if (value instanceof String text && !text.isBlank()) {
            try {
                number = new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                throw new ToolExecutionException(name + " must be a positive number");
            }
        } else {
            throw new ToolExecutionException(name + " must be a positive number");
        }
        if (number.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ToolExecutionException(name + " must be a positive number");
        }
        return number;
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return String.valueOf(data);
        }
    }

    private static McpSchema.ToolAnnotations toolAnnotations(String title,
                                                            boolean readOnly,
                                                            boolean destructive,
                                                            boolean idempotent) {
        return McpSchema.ToolAnnotations.builder()
                .title(title)
                .readOnlyHint(readOnly)
                .destructiveHint(destructive)
                .idempotentHint(idempotent)
                .openWorldHint(false)
                .build();
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return objectSchema(properties, List.of());
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    @SafeVarargs
    private static Map<String, Object> properties(Map.Entry<String, Object>... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            properties.put(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    private static Map.Entry<String, Object> property(String name, Map<String, Object> schema) {
        return Map.entry(name, schema);
    }

    private static Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> numberSchema(String description) {
        return Map.of("type", "number", "description", description);
    }

    private static Map<String, Object> nullableNumberSchema(String description) {
        return Map.of("type", List.of("number", "null"), "description", description);
    }

    private static Map<String, Object> nullableStringSchema(String description) {
        return Map.of("type", List.of("string", "null"), "description", description);
    }

    private static Map<String, Object> nullableObjectSchema(Map<String, Object> properties) {
        Map<String, Object> schema = objectSchema(properties);
        schema.put("type", List.of("object", "null"));
        return schema;
    }

    private static Map<String, Object> sourceRecordOutputSchema() {
        return objectSchema(properties(
                property("recordId", nullableNumberSchema("历史记录 ID")),
                property("role", stringSchema("证据角色")),
                property("purchaseDate", nullableStringSchema("购买日期")),
                property("productName", stringSchema("历史记录商品名称")),
                property("price", nullableNumberSchema("历史记录统计金额")),
                property("quantity", nullableNumberSchema("统一口径数量")),
                property("unit", nullableStringSchema("统一口径单位")),
                property("unitPrice", nullableNumberSchema("统一口径单位价格")),
                property("unitPriceUnit", nullableStringSchema("单位价格口径单位")),
                property("originalQuantity", nullableNumberSchema("原始记录数量")),
                property("originalUnit", nullableStringSchema("原始记录单位"))
        ));
    }

    private static Map<String, Object> arraySchema(Map<String, Object> itemSchema) {
        return Map.of("type", "array", "items", itemSchema);
    }

    private static Map<String, Object> booleanSchema(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static void waitForPendingMessages() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class EofAwareInputStream extends InputStream {
        private static final long EOF_DRAIN_DELAY_MILLIS = 500L;

        private final InputStream delegate;
        private final CountDownLatch eofLatch = new CountDownLatch(1);
        private boolean eofDelayed;

        EofAwareInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value == -1) {
                delayBeforeEof();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int value = delegate.read(buffer, offset, length);
            if (value == -1) {
                delayBeforeEof();
            }
            return value;
        }

        private void delayBeforeEof() {
            if (!eofDelayed) {
                eofDelayed = true;
                try {
                    Thread.sleep(EOF_DRAIN_DELAY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            eofLatch.countDown();
        }

        void awaitEndOfInput() {
            try {
                eofLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
