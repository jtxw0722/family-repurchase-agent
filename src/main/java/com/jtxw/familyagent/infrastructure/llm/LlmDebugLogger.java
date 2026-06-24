package com.jtxw.familyagent.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jtxw.familyagent.infrastructure.config.LlmDebugLogProperties;
import com.jtxw.familyagent.infrastructure.ocr.OrderImagePrivacySanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 08:04:40
 * @Description: 统一 LLM debug 日志写入器，负责按安全边界生成本地 JSON dump 文件且不影响主业务流程
 */
@Service
public class LlmDebugLogger {
    /**
     * 普通应用日志记录器，仅记录 debug dump 写入失败摘要，不输出 prompt、响应正文或密钥。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmDebugLogger.class);
    /**
     * OpenAI-compatible 供应商默认标识，用于失败时没有响应对象的场景。
     */
    private static final String DEFAULT_PROVIDER = "openai-compatible";
    /**
     * debug 文件名时间格式，按时间顺序排序且精确到毫秒。
     */
    private static final DateTimeFormatter FILE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    /**
     * debug JSON 中 createdAt 的本地时间格式。
     */
    private static final DateTimeFormatter JSON_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * 统一 LLM debug 日志配置，控制是否写文件以及写入目录。
     */
    private final LlmDebugLogProperties properties;
    /**
     * JSON 写入器，使用项目统一 ObjectMapper 生成可读 JSON。
     */
    private final ObjectMapper objectMapper;
    /**
     * 缩进格式 JSON 写入器，用于提升本地排障文件可读性。
     */
    private final ObjectWriter objectWriter;
    /**
     * 订单截图隐私脱敏器，用于 parse_order_image debug 内容落盘前兜底脱敏。
     */
    private final OrderImagePrivacySanitizer privacySanitizer;

    /**
     * 创建统一 LLM debug 日志写入器。
     *
     * @param properties   统一 LLM debug 日志配置，不允许为空
     * @param objectMapper JSON 序列化组件，不允许为空
     */
    public LlmDebugLogger(LlmDebugLogProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new OrderImagePrivacySanitizer());
    }

    /**
     * 创建统一 LLM debug 日志写入器，并注入订单截图隐私脱敏器。
     *
     * @param properties       统一 LLM debug 日志配置，不允许为空
     * @param objectMapper     JSON 序列化组件，不允许为空
     * @param privacySanitizer 订单截图隐私脱敏器，不允许为空
     */
    @Autowired
    public LlmDebugLogger(LlmDebugLogProperties properties, ObjectMapper objectMapper,
                          OrderImagePrivacySanitizer privacySanitizer) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.objectWriter = objectMapper.writerWithDefaultPrettyPrinter();
        this.privacySanitizer = privacySanitizer;
    }

    /**
     * 创建关闭状态的 debug logger，供单元测试或未接入 Spring 的构造路径保持兼容。
     *
     * @param objectMapper JSON 序列化组件，不允许为空
     * @return 关闭状态的 LLM debug 日志写入器
     */
    public static LlmDebugLogger disabled(ObjectMapper objectMapper) {
        return new LlmDebugLogger(new LlmDebugLogProperties(), objectMapper);
    }

    /**
     * 记录成功的 LLM 调用 debug dump。
     *
     * @param request  供应商无关 LLM 请求，图片内容会被摘要化
     * @param response 供应商无关 LLM 响应，允许包含模型输出文本
     */
    public void logSuccess(LlmRequest request, LlmResponse response) {
        if (response == null) {
            return;
        }
        write(request, response.provider(), response.model(), response.durationMillis(), true, response, null);
    }

    /**
     * 记录失败的 LLM 调用 debug dump，默认按 OpenAI-compatible 供应商标识写入。
     *
     * @param request        供应商无关 LLM 请求，图片内容会被摘要化
     * @param exception      调用异常，只写入类型和消息摘要
     * @param durationMillis 调用耗时，单位毫秒，允许为空
     */
    public void logFailure(LlmRequest request, Throwable exception, Long durationMillis) {
        write(request, DEFAULT_PROVIDER, request == null ? null : request.model(),
                durationMillis, false, null, exception);
    }

    /**
     * 记录失败的 LLM 调用 debug dump，允许调用方指定供应商和模型标识。
     *
     * @param request        供应商无关 LLM 请求，图片内容会被摘要化
     * @param provider       供应商标识，允许为空
     * @param model          模型名称，允许为空
     * @param exception      调用异常，只写入类型和消息摘要
     * @param durationMillis 调用耗时，单位毫秒，允许为空
     */
    public void logFailure(LlmRequest request, String provider, String model, Throwable exception, Long durationMillis) {
        write(request, provider, model, durationMillis, false, null, exception);
    }

    /**
     * 按统一结构写入 LLM debug JSON 文件；任何写入异常都会降级为普通 warning。
     */
    private void write(LlmRequest request, String provider, String model, Long durationMillis,
                       boolean success, LlmResponse response, Throwable exception) {
        if (!isEnabled()) {
            return;
        }
        try {
            LocalDateTime createdAt = LocalDateTime.now();
            ObjectNode root = objectMapper.createObjectNode();
            root.put("createdAt", JSON_TIME_FORMATTER.format(createdAt));
            putText(root, "scene", request == null ? null : request.scene());
            putText(root, "promptVersion", request == null ? null : request.promptVersion());
            putText(root, "provider", provider);
            putText(root, "model", firstText(model, request == null ? null : request.model()));
            putLong(root, "durationMillis", durationMillis);
            root.put("success", success);
            root.set("request", requestNode(request));
            root.set("response", response == null ? objectMapper.nullNode() : responseNode(request, response));
            root.set("error", exception == null ? objectMapper.nullNode() : errorNode(exception));

            Path directory = Path.of(properties.getDebugLogDir());
            Files.createDirectories(directory);
            Path filePath = directory.resolve(fileName(createdAt, request, provider));
            objectWriter.writeValue(filePath.toFile(), root);
            LOGGER.info("LLM debug dump written: scene={}, promptVersion={}, provider={}, model={}, "
                            + "success={}, durationMillis={}, file={}",
                    request == null ? null : request.scene(),
                    request == null ? null : request.promptVersion(),
                    provider,
                    firstText(model, request == null ? null : request.model()),
                    success,
                    durationMillis,
                    filePath);
        } catch (RuntimeException | IOException writeException) {
            LOGGER.warn("LLM debug dump write failed: {}", writeException.getClass().getSimpleName());
        }
    }

    /**
     * 判断 debug dump 是否开启；关闭时不创建目录、不写文件。
     */
    private boolean isEnabled() {
        return properties != null && properties.isDebugLogEnabled();
    }

    /**
     * 生成不含图片原文和鉴权信息的请求摘要节点。
     */
    private ObjectNode requestNode(LlmRequest request) {
        ObjectNode requestNode = objectMapper.createObjectNode();
        if (request == null) {
            return requestNode;
        }
        putText(requestNode, "systemPrompt", request.systemPrompt());
        putText(requestNode, "userPrompt", request.userPrompt());
        requestNode.set("systemPromptLines", linesNode(request.systemPrompt()));
        requestNode.set("userPromptLines", linesNode(request.userPrompt()));
        if (request.temperature() == null) {
            requestNode.putNull("temperature");
        } else {
            requestNode.put("temperature", request.temperature());
        }
        if (request.maxTokens() == null) {
            requestNode.putNull("maxTokens");
        } else {
            requestNode.put("maxTokens", request.maxTokens());
        }
        requestNode.put("imageCount", request.images().size());
        ArrayNode imagesNode = requestNode.putArray("images");
        for (LlmImageInput image : request.images()) {
            ObjectNode imageNode = imagesNode.addObject();
            putText(imageNode, "fileName", image.fileName());
            putText(imageNode, "mimeType", image.mimeType());
            imageNode.put("byteSize", image.content().length);
            imageNode.put("contentRedacted", true);
        }
        return requestNode;
    }

    /**
     * 生成模型响应摘要节点，保留 content 便于本地 debug，但不包含 HTTP Header。
     */
    private ObjectNode responseNode(LlmRequest request, LlmResponse response) {
        ObjectNode responseNode = objectMapper.createObjectNode();
        String safeContent = safeResponseContent(request, response.content());
        putText(responseNode, "content", safeContent);
        responseNode.set("contentLines", linesNode(safeContent));
        appendParsedContent(request, responseNode, safeContent);
        putInteger(responseNode, "promptTokens", response.promptTokens());
        putInteger(responseNode, "completionTokens", response.completionTokens());
        return responseNode;
    }

    /**
     * 为响应内容追加 parsedJson 和 rawTextLines，解析失败时保持原 debug 文件写入流程。
     */
    private void appendParsedContent(LlmRequest request, ObjectNode responseNode, String content) {
        try {
            JsonNode parsedJson = objectMapper.readTree(extractJsonContent(content));
            if (isParseOrderImage(request) && parsedJson.isObject() && parsedJson.path("rawText").isTextual()) {
                ObjectNode sanitizedJson = ((ObjectNode) parsedJson).deepCopy();
                String sanitizedRawText = privacySanitizer.sanitizeRawText(parsedJson.path("rawText").asText());
                sanitizedJson.put("rawText", sanitizedRawText);
                responseNode.set("parsedJson", sanitizedJson);
                responseNode.set("rawTextLines", linesNode(sanitizedRawText));
                return;
            }
            responseNode.set("parsedJson", parsedJson);
        } catch (RuntimeException | IOException ignored) {
            // parsedJson 仅用于提升 debug 可读性，解析失败不能影响主流程。
        }
    }

    /**
     * 根据调用场景生成安全响应内容；订单截图 OCR 场景会在 debug 落盘前做隐私脱敏。
     */
    private String safeResponseContent(LlmRequest request, String content) {
        if (!isParseOrderImage(request) || content == null || content.isBlank()) {
            return content;
        }
        try {
            JsonNode parsedJson = objectMapper.readTree(extractJsonContent(content));
            if (parsedJson.isObject() && parsedJson.path("rawText").isTextual()) {
                ObjectNode sanitizedJson = ((ObjectNode) parsedJson).deepCopy();
                sanitizedJson.put("rawText", privacySanitizer.sanitizeRawText(parsedJson.path("rawText").asText()));
                return objectMapper.writeValueAsString(sanitizedJson);
            }
        } catch (RuntimeException | IOException ignored) {
            // 非 JSON 响应继续走文本级兜底脱敏。
        }
        return privacySanitizer.sanitizeRawText(content);
    }

    /**
     * 判断是否为订单截图视觉 OCR 场景。
     */
    private boolean isParseOrderImage(LlmRequest request) {
        return request != null && "parse_order_image".equals(request.scene());
    }

    /**
     * 将真实换行和字面量 \n 都转换为 JSON 数组，提升 prompt 和响应正文的可读性。
     */
    private ArrayNode linesNode(String value) {
        ArrayNode linesNode = objectMapper.createArrayNode();
        if (value == null) {
            return linesNode;
        }
        String[] lines = normalizeReadableLineBreaks(value).split("\\R", -1);
        for (String line : lines) {
            linesNode.add(line);
        }
        return linesNode;
    }

    /**
     * 将真实换行和字面量 \n 统一为可按行阅读的换行符。
     */
    private String normalizeReadableLineBreaks(String value) {
        return value.replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n");
    }

    /**
     * 从纯 JSON 或 Markdown JSON code block 中提取 JSON 文本。
     */
    private String extractJsonContent(String content) {
        if (content == null) {
            return "";
        }
        String trimmedContent = content.trim();
        if (trimmedContent.startsWith("```")) {
            return normalizeReadableLineBreaks(trimmedContent).replaceFirst("^```[a-zA-Z]*\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        return trimmedContent;
    }

    /**
     * 生成异常摘要节点，仅包含异常类型和消息，不输出堆栈和请求正文。
     */
    private ObjectNode errorNode(Throwable exception) {
        ObjectNode errorNode = objectMapper.createObjectNode();
        putText(errorNode, "type", exception.getClass().getSimpleName());
        putText(errorNode, "message", safeErrorMessage(exception));
        return errorNode;
    }

    /**
     * 对异常消息做兜底脱敏，避免未来调用方把鉴权头、图片 Base64 或大段请求体拼进异常后直接落盘。
     */
    private String safeErrorMessage(Throwable exception) {
        if (exception == null || exception.getMessage() == null) {
            return null;
        }
        return exception.getMessage()
                .replaceAll("Bearer\\s+[^\\s,，;；]+", "Bearer [REDACTED]")
                .replaceAll("data:image/[^\\s\"']+", "[REDACTED_IMAGE]")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 生成可读、可排序且避免并发冲突的 debug 文件名。
     */
    private String fileName(LocalDateTime createdAt, LlmRequest request, String provider) {
        String scene = sanitize(request == null ? null : request.scene());
        String promptVersion = sanitize(request == null ? null : request.promptVersion());
        String providerPart = sanitize(provider);
        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return FILE_TIME_FORMATTER.format(createdAt) + "_" + scene + "_" + promptVersion
                + "_" + providerPart + "_" + shortId + ".json";
    }

    /**
     * 清洗文件名片段，避免中文冒号、斜杠、反斜杠、空格等不安全字符进入文件名。
     */
    private String sanitize(String value) {
        String normalizedValue = value == null || value.isBlank() ? "unknown" : value.trim();
        String safeValue = normalizedValue.replaceAll("[^a-zA-Z0-9._-]", "_")
                .replaceAll("_+", "_")
                .toLowerCase(Locale.ROOT);
        return safeValue.isBlank() ? "unknown" : safeValue;
    }

    /**
     * 返回第一个非空文本，用于响应缺少模型时回退到请求模型。
     */
    private String firstText(String firstValue, String secondValue) {
        return firstValue == null || firstValue.isBlank() ? secondValue : firstValue;
    }

    private void putText(ObjectNode node, String fieldName, String value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value);
        }
    }

    private void putLong(ObjectNode node, String fieldName, Long value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value);
        }
    }

    private void putInteger(ObjectNode node, String fieldName, Integer value) {
        if (value == null) {
            node.putNull(fieldName);
        } else {
            node.put(fieldName, value);
        }
    }
}
