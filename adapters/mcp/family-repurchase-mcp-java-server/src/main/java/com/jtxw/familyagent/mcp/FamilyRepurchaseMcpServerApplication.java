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
    private static final String SERVER_VERSION = resolveVersion();

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

    /**
     * 读取 Jar Manifest 中的 Implementation-Version。
     *
     * <p>本地 IDE 运行时通常读取不到版本号，此时返回 dev；打包运行时由 Maven 写入真实版本。</p>
     *
     * @return 应用版本号
     */
    private static String resolveVersion() {
        Package currentPackage = FamilyRepurchaseMcpServerApplication.class.getPackage();
        if (currentPackage == null) {
            return "dev";
        }
        String version = currentPackage.getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
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
                .tools(
                        application.importFileTool(),
                        application.recordPurchaseTool(),
                        application.comparePriceTool(),
                        application.getPriceBaselineTool(),
                        application.searchPurchaseRecordsTool(),
                        application.generateReportTool()
                )
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

    McpServerFeatures.SyncToolSpecification recordPurchaseTool() {
        String title = "Record Purchase";
        return new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder("record_purchase")
                        .title(title)
                        .description("""
                                录入手动或自然语言抽取后的结构化购买记录。
                                Claude 负责把自然语言整理为字段；业务校验、归一化、去重、入库和复核均由 Spring Boot 后端完成。
                                """)
                        .annotations(toolAnnotations(title, false, false, false))
                        .inputSchema(objectSchema(
                                properties(
                                        property("dryRun", booleanSchema("是否只预览不写入数据库")),
                                        property("records", arraySchema(recordPurchaseInputSchema()))
                                ),
                                List.of("dryRun", "records")
                        ))
                        .outputSchema(objectSchema(properties(
                                property("dryRun", booleanSchema("是否只预览不写入数据库")),
                                property("savedCount", numberSchema("实际写入 purchase_records 的记录数")),
                                property("reviewCount", numberSchema("生成的复核项数量")),
                                property("records", arraySchema(recordPurchaseOutputSchema()))
                        )))
                        .build(),
                this::handleRecordPurchase
        );
    }

    /**
     * 定义原始购买记录关键词检索 MCP tool。
     *
     * <p>该工具只转发到后端只读 REST 接口，返回原始订单样本，不生成价格基线。</p>
     *
     * @return search_purchase_records tool specification
     */
    McpServerFeatures.SyncToolSpecification searchPurchaseRecordsTool() {
        String title = "Search Purchase Records";
        return new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder("search_purchase_records")
                        .title(title)
                        .description("""
                                Search raw historical purchase records by keyword when compare_price or get_price_baseline has no reliable baseline.
                                This tool returns raw order samples only and does not produce a normalized price baseline.
                                If owner is omitted, empty, or null, it searches all family records.
                                LLM answers based on this tool must state that there is no reliable normalized price baseline and that the analysis is only based on raw historical order samples.
                                """)
                        .annotations(toolAnnotations(title, true, false, true))
                        .inputSchema(objectSchema(
                                properties(
                                        property("keyword", stringSchema("查询关键词，例如 猫砂；按原始商品名、SKU、分类和归一化名称检索")),
                                        property("owner", nullableStringSchema("订单所属人，可选；不传、空字符串或 null 时查询全家庭样本")),
                                        property("limit", integerSchema("可选返回条数；后端默认 20，最大 50")),
                                        property("fromDate", nullableStringSchema("可选开始日期，格式 yyyy-MM-dd")),
                                        property("toDate", nullableStringSchema("可选结束日期，格式 yyyy-MM-dd"))
                                ),
                                List.of("keyword")
                        ))
                        .outputSchema(objectSchema(properties(
                                property("keyword", stringSchema("清洗后的查询关键词")),
                                property("scope", stringSchema("查询范围，FAMILY 表示全家庭样本，OWNER 表示指定归属人样本")),
                                property("owner", nullableStringSchema("指定归属人；全家庭查询时为空")),
                                property("matchedCount", numberSchema("符合查询条件的原始记录总数")),
                                property("returnedCount", numberSchema("实际返回的记录数量")),
                                property("records", arraySchema(searchPurchaseRecordItemOutputSchema())),
                                property("warnings", arraySchema(stringSchema("风险提示，说明结果不是价格基线")))
                        )))
                        .build(),
                this::handleSearchPurchaseRecords
        );
    }

    McpServerFeatures.SyncToolSpecification getPriceBaselineTool() {
        String title = "Get Price Baseline";
        return new McpServerFeatures.SyncToolSpecification(
                McpSchema.Tool.builder("get_price_baseline")
                        .title(title)
                        .description("""
                            查询某个复购品的本地历史价格基准线，包括历史最低价、中位价、平均价、样本数量和 evidence。
                            适用于用户询问“历史最低价”“最低多少钱”“历史中位价”“价格基准线”等场景。
                            该工具不需要当前价格；如果未提供 unit，会由后端根据商品归一化规则推断标准单位。
                            """)
                        .annotations(toolAnnotations(title, true, false, true))
                        .inputSchema(objectSchema(
                                properties(
                                        property("productName", stringSchema("商品名称，例如 纸巾、猫砂、洗衣液")),
                                        property("unit", stringSchema("可选。统计单位，例如 kg、抽、L；为空时由商品规则推断标准单位。"))
                                ),
                                List.of("productName")
                        ))
                        .outputSchema(objectSchema(properties(
                                property("productName", stringSchema("原始商品名称")),
                                property("normalizedName", stringSchema("标准化商品名称")),
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
                this::handleGetPriceBaseline
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

    /**
     * 定义生成价格报告的 MCP tool，并保持 outputSchema 与后端 REST 返回字段一致。
     *
     * <p>该 schema 需要包含 message，否则 MCP Host 会因 structuredContent 额外字段校验失败。</p>
     *
     * @return generate_report tool specification
     */
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
                                property("recordCount", numberSchema("纳入报告的记录数")),
                                property("totalAmount", numberSchema("纳入统计的金额合计")),
                                property("pendingReviewCount", numberSchema("待复核记录数")),
                                property("reportPath", stringSchema("生成的报告文件路径")),
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

    private McpSchema.CallToolResult handleRecordPurchase(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> args = arguments(request);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dryRun", requireBoolean(args, "dryRun"));
            body.put("records", requireRecordList(args, "records"));
            return toolSuccess(restClient.postJson("/api/tools/record-purchase", body));
        } catch (Exception e) {
            return toolError(e);
        }
    }

    private McpSchema.CallToolResult handleGetPriceBaseline(McpSyncServerExchange exchange,
                                                            McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> args = arguments(request);
            Map<String, Object> body = new LinkedHashMap<>();

            body.put("productName", requireString(args, "productName"));

            String unit = optionalString(args, "unit");
            if (unit != null) {
                body.put("unit", unit);
            }

            return toolSuccess(restClient.postJson("/api/tools/get-price-baseline", body));
        } catch (Exception e) {
            return toolError(e);
        }
    }

    /**
     * 处理原始购买记录关键词检索工具调用。
     *
     * @param exchange MCP 同步交换上下文
     * @param request  工具调用请求
     * @return 工具调用结果，成功时 structuredContent 复用后端 REST 响应
     */
    private McpSchema.CallToolResult handleSearchPurchaseRecords(McpSyncServerExchange exchange,
                                                                 McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> args = arguments(request);
            Map<String, Object> body = new LinkedHashMap<>();

            body.put("keyword", requireString(args, "keyword"));

            String owner = optionalBlankAsNullString(args, "owner");
            if (owner != null) {
                body.put("owner", owner);
            }

            Integer limit = optionalPositiveInteger(args, "limit");
            if (limit != null) {
                body.put("limit", limit);
            }

            String fromDate = optionalBlankAsNullString(args, "fromDate");
            if (fromDate != null) {
                body.put("fromDate", fromDate);
            }

            String toDate = optionalBlankAsNullString(args, "toDate");
            if (toDate != null) {
                body.put("toDate", toDate);
            }

            return toolSuccess(restClient.postJson("/api/tools/purchase-records/search", body));
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
                .structuredContent(data)
                .isError(false)
                .build();
    }

    private McpSchema.CallToolResult toolError(Exception e) {
        String message = e.getMessage() == null || e.getMessage().isBlank() ? "工具执行失败" : e.getMessage();
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
        throw new ToolExecutionException(name + " 不能为空，必须是非空字符串");
    }

    private String optionalString(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        throw new ToolExecutionException(name + " 提供时必须是非空字符串");
    }

    /**
     * 读取可选字符串参数，并将空字符串按未传处理。
     *
     * @param args 工具入参
     * @param name 参数名
     * @return trim 后的字符串；未传或空白时返回 null
     */
    private String optionalBlankAsNullString(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        throw new ToolExecutionException(name + " 提供时必须是字符串");
    }

    /**
     * 读取可选正整数参数。
     *
     * @param args 工具入参
     * @param name 参数名
     * @return 正整数；未传时返回 null
     */
    private Integer optionalPositiveInteger(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) {
            return null;
        }
        BigDecimal number;
        if (value instanceof Number numericValue) {
            number = new BigDecimal(numericValue.toString());
        } else if (value instanceof String text && !text.isBlank()) {
            try {
                number = new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                throw new ToolExecutionException(name + " 必须是正整数");
            }
        } else {
            throw new ToolExecutionException(name + " 必须是正整数");
        }
        if (number.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ToolExecutionException(name + " 必须是正整数");
        }
        try {
            return number.intValueExact();
        } catch (ArithmeticException e) {
            throw new ToolExecutionException(name + " 必须是正整数");
        }
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
                throw new ToolExecutionException(name + " 必须是正数");
            }
        } else {
            throw new ToolExecutionException(name + " 必须是正数");
        }
        if (number.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ToolExecutionException(name + " 必须是正数");
        }
        return number;
    }

    private boolean requireBoolean(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new ToolExecutionException(name + " 必须是 boolean");
    }

    private List<?> requireRecordList(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list;
        }
        throw new ToolExecutionException(name + " 必填且不能为空数组");
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

    /**
     * 构建整数类型 JSON Schema。
     *
     * @param description 字段说明
     * @return integer schema
     */
    private static Map<String, Object> integerSchema(String description) {
        return Map.of("type", "integer", "description", description);
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

    /**
     * 构建 search_purchase_records 返回记录明细的 JSON Schema。
     *
     * @return 原始购买记录明细 schema
     */
    private static Map<String, Object> searchPurchaseRecordItemOutputSchema() {
        return objectSchema(properties(
                property("recordId", nullableNumberSchema("购买记录 ID")),
                property("orderTime", nullableStringSchema("订单发生时间")),
                property("platform", nullableStringSchema("购买平台")),
                property("owner", nullableStringSchema("订单归属人")),
                property("productName", stringSchema("原始商品名称")),
                property("sku", nullableStringSchema("商品规格或 SKU")),
                property("category", nullableStringSchema("一级商品分类")),
                property("subCategory", nullableStringSchema("二级商品分类")),
                property("quantity", nullableNumberSchema("原始数量")),
                property("unit", nullableStringSchema("原始数量单位")),
                property("totalAmount", nullableNumberSchema("统计总金额")),
                property("currency", nullableStringSchema("交易币种")),
                property("normalizedName", nullableStringSchema("记录中已有的归一化名称，不代表可靠价格基线")),
                property("unitPrice", nullableNumberSchema("记录中已有的单位价格，允许为空"))
        ));
    }

    private static Map<String, Object> recordPurchaseInputSchema() {
        return objectSchema(properties(
                property("productName", stringSchema("商品名称")),
                property("price", numberSchema("购买总价")),
                property("quantity", numberSchema("购买数量")),
                property("unit", stringSchema("数量单位")),
                property("platform", stringSchema("购买平台，可选，缺省为 MANUAL")),
                property("purchaseDate", stringSchema("购买日期 yyyy-MM-dd，可选")),
                property("owner", stringSchema("订单所属人，可选，缺省为 jtxw")),
                property("shopName", stringSchema("店铺名称，可选")),
                property("sku", stringSchema("商品规格或 SKU，可选")),
                property("note", stringSchema("备注，可选")),
                property("sourceText", stringSchema("自然语言原文，可选")),
                property("confirmOutOfRange", booleanSchema("是否确认接受偏离历史价格区间的样本，可选，默认 false"))
        ), List.of("productName", "price", "quantity", "unit"));
    }

    private static Map<String, Object> recordPurchaseOutputSchema() {
        return objectSchema(properties(
                property("recordId", nullableNumberSchema("购买记录 ID；dryRun 时为空")),
                property("productName", stringSchema("原始商品名称")),
                property("normalizedName", stringSchema("标准化商品名称")),
                property("price", numberSchema("购买总价")),
                property("quantity", numberSchema("最终入库数量")),
                property("unit", stringSchema("最终入库单位")),
                property("unitPrice", numberSchema("单位价格")),
                property("decision", stringSchema("统计决策，include 或 exclude")),
                property("reviewRequired", booleanSchema("是否需要人工复核")),
                property("reviewReasons", arraySchema(stringSchema("复核原因")))
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
