package com.jtxw.familyagent.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.infrastructure.config.LlmDebugLogProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 08:04:40
 * @Description: 统一 LLM debug 日志写入器测试，验证开关、JSON 结构、失败记录和敏感内容脱敏
 */
class LlmDebugLoggerTest {
    /**
     * 测试使用的 JSON 解析器，用于读取 debug dump 文件内容。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void shouldNotCreateDirectoryWhenDisabled() {
        Path debugDir = tempDir.resolve("disabled");
        LlmDebugLogger logger = logger(false, debugDir);

        logger.logSuccess(request(), response(12L));

        assertThat(debugDir).doesNotExist();
    }

    @Test
    void shouldWriteSuccessJsonAndRedactImageContent() throws Exception {
        Path debugDir = tempDir.resolve("success");
        LlmDebugLogger logger = logger(true, debugDir);

        logger.logSuccess(request(), response(34L));

        Path debugFile = singleFile(debugDir);
        String jsonText = Files.readString(debugFile, StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(jsonText);
        assertThat(debugFile.getFileName().toString())
                .contains("parse_order_image")
                .contains("model-ocr-v1")
                .contains("openai-compatible");
        assertThat(root.path("scene").asText()).isEqualTo("parse_order_image");
        assertThat(root.path("promptVersion").asText()).isEqualTo("model-ocr-v1");
        assertThat(root.path("provider").asText()).isEqualTo("openai-compatible");
        assertThat(root.path("model").asText()).isEqualTo("actual-model");
        assertThat(root.path("durationMillis").asLong()).isEqualTo(34L);
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("request").path("systemPrompt").asText()).isEqualTo("system prompt");
        assertThat(root.path("request").path("userPrompt").asText()).isEqualTo("user prompt");
        assertThat(root.path("request").path("systemPromptLines").get(0).asText()).isEqualTo("system prompt");
        assertThat(root.path("request").path("userPromptLines").get(0).asText()).isEqualTo("user prompt");
        assertThat(root.path("request").path("imageCount").asInt()).isEqualTo(1);
        JsonNode image = root.path("request").path("images").get(0);
        assertThat(image.path("fileName").asText()).isEqualTo("order.jpg");
        assertThat(image.path("mimeType").asText()).isEqualTo("image/jpeg");
        assertThat(image.path("byteSize").asInt()).isEqualTo(3);
        assertThat(image.path("contentRedacted").asBoolean()).isTrue();
        assertThat(root.path("response").path("content").asText()).isEqualTo("model content");
        assertThat(root.path("response").path("contentLines").get(0).asText()).isEqualTo("model content");
        assertThat(root.path("response").path("promptTokens").asInt()).isEqualTo(10);
        assertThat(root.path("response").path("completionTokens").asInt()).isEqualTo(20);
        assertThat(jsonText)
                .doesNotContain("data:image/jpeg;base64")
                .doesNotContain("AQID")
                .doesNotContain("secret-api-key");
    }

    @Test
    void shouldWriteReadableLinesForRealAndLiteralNewlines() throws Exception {
        Path debugDir = tempDir.resolve("readable-lines");
        LlmDebugLogger logger = logger(true, debugDir);
        LlmRequest request = new LlmRequest("normalization_rule_suggestions", "v1",
                "system line 1\\nsystem line 2", "user line 1\nuser line 2",
                "request-model", 0D, null, List.of());
        LlmResponse response = new LlmResponse("```json\\n{\"suggestions\":[]}\\n```",
                "openai-compatible", "actual-model", 12L, null, null);

        logger.logSuccess(request, response);

        JsonNode root = objectMapper.readTree(Files.readString(singleFile(debugDir), StandardCharsets.UTF_8));
        JsonNode systemPromptLines = root.path("request").path("systemPromptLines");
        assertThat(systemPromptLines).hasSize(2);
        assertThat(systemPromptLines.get(0).asText()).isEqualTo("system line 1");
        assertThat(systemPromptLines.get(1).asText()).isEqualTo("system line 2");
        JsonNode userPromptLines = root.path("request").path("userPromptLines");
        assertThat(userPromptLines).hasSize(2);
        assertThat(userPromptLines.get(0).asText()).isEqualTo("user line 1");
        assertThat(userPromptLines.get(1).asText()).isEqualTo("user line 2");
        JsonNode contentLines = root.path("response").path("contentLines");
        assertThat(contentLines).hasSize(3);
        assertThat(contentLines.get(0).asText()).isEqualTo("```json");
        assertThat(contentLines.get(1).asText()).isEqualTo("{\"suggestions\":[]}");
        assertThat(contentLines.get(2).asText()).isEqualTo("```");
        assertThat(root.path("response").path("parsedJson").path("suggestions").isArray()).isTrue();
    }

    @Test
    void shouldSanitizeParseOrderImageResponseInDebugFile() throws Exception {
        Path debugDir = tempDir.resolve("parse-order-image-redacted");
        LlmDebugLogger logger = logger(true, debugDir);
        String content = "{\"rawText\":\"收货人：张三\\n张三 130****0624 南环路179号1栋\\n"
                + "订单编号：260604398102382163163\\n快递单号：777415590765148\\n"
                + "商品名称：赵露思同款包包\\n实付：¥25.73\",\"warnings\":[]}";

        logger.logSuccess(request(), new LlmResponse(content, "openai-compatible",
                "actual-model", 90L, null, null));

        String jsonText = Files.readString(singleFile(debugDir), StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(jsonText);
        assertThat(root.path("response").path("content").asText())
                .contains("[姓名已隐藏]")
                .contains("[收货信息已隐藏]")
                .contains("订单编号：[编号已隐藏]")
                .contains("快递单号：[编号已隐藏]")
                .contains("商品名称：赵露思同款包包")
                .contains("实付：¥25.73");
        JsonNode rawTextLines = root.path("response").path("rawTextLines");
        assertThat(rawTextLines.toString())
                .contains("[收货信息已隐藏]", "商品名称：赵露思同款包包", "实付：¥25.73");
        assertThat(root.path("response").path("parsedJson").path("rawText").asText())
                .doesNotContain("张三")
                .doesNotContain("130****0624")
                .doesNotContain("南环路179号")
                .doesNotContain("260604398102382163163")
                .doesNotContain("777415590765148");
        assertThat(jsonText)
                .doesNotContain("张三")
                .doesNotContain("130****0624")
                .doesNotContain("南环路179号")
                .doesNotContain("260604398102382163163")
                .doesNotContain("777415590765148")
                .doesNotContain("data:image/jpeg;base64")
                .doesNotContain("secret-api-key");
    }

    @Test
    void shouldWriteFailureJson() throws Exception {
        Path debugDir = tempDir.resolve("failure");
        LlmDebugLogger logger = logger(true, debugDir);

        logger.logFailure(request(), new LlmException("provider failed"), 56L);

        JsonNode root = objectMapper.readTree(Files.readString(singleFile(debugDir), StandardCharsets.UTF_8));
        assertThat(root.path("success").asBoolean()).isFalse();
        assertThat(root.path("response").isNull()).isTrue();
        assertThat(root.path("error").path("type").asText()).isEqualTo("LlmException");
        assertThat(root.path("error").path("message").asText()).isEqualTo("provider failed");
        assertThat(root.path("durationMillis").asLong()).isEqualTo(56L);
    }

    @Test
    void shouldRedactSensitiveContentInErrorMessage() throws Exception {
        Path debugDir = tempDir.resolve("failure-redacted");
        LlmDebugLogger logger = logger(true, debugDir);

        logger.logFailure(request(), new LlmException("failed Bearer secret-token\n"
                + "image=data:image/jpeg;base64,AQID and body"), 78L);

        String jsonText = Files.readString(singleFile(debugDir), StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(jsonText);
        assertThat(root.path("error").path("message").asText())
                .isEqualTo("failed Bearer [REDACTED] image=[REDACTED_IMAGE] and body");
        assertThat(jsonText)
                .doesNotContain("secret-token")
                .doesNotContain("data:image/jpeg;base64")
                .doesNotContain("AQID");
    }

    /**
     * 按指定开关和目录创建被测 debug 写入器。
     */
    private LlmDebugLogger logger(boolean enabled, Path debugDir) {
        LlmDebugLogProperties properties = new LlmDebugLogProperties();
        properties.setDebugLogEnabled(enabled);
        properties.setDebugLogDir(debugDir.toString());
        return new LlmDebugLogger(properties, objectMapper);
    }

    /**
     * 构造包含图片的 LLM 请求，用于验证图片只写摘要不写 Base64。
     */
    private LlmRequest request() {
        return new LlmRequest("parse_order_image", "model-ocr-v1", "system prompt", "user prompt",
                "request-model", 0D, null,
                List.of(new LlmImageInput("order.jpg", "image/jpeg", new byte[]{1, 2, 3})));
    }

    /**
     * 构造成功 LLM 响应，用于验证响应正文和 token 用量写入。
     */
    private LlmResponse response(long durationMillis) {
        return new LlmResponse("model content", "openai-compatible", "actual-model", durationMillis, 10, 20);
    }

    /**
     * 读取 debug 目录中唯一生成的文件。
     */
    private Path singleFile(Path debugDir) throws Exception {
        try (var files = Files.list(debugDir)) {
            List<Path> debugFiles = files.toList();
            assertThat(debugFiles).hasSize(1);
            return debugFiles.get(0);
        }
    }
}
