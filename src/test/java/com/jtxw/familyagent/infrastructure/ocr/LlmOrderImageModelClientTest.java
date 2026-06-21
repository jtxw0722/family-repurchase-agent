package com.jtxw.familyagent.infrastructure.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.infrastructure.llm.LlmClient;
import com.jtxw.familyagent.infrastructure.llm.LlmRequest;
import com.jtxw.familyagent.infrastructure.llm.LlmResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 14:05:37
 * @Description: 订单截图 LLM 场景适配器测试，验证提示词、图片边界、请求语义和 OCR JSON 解析
 */
class LlmOrderImageModelClientTest {
    /** JUnit 创建的合成图片隔离目录。 */
    @TempDir
    private Path temporaryDirectory;

    @Test
    void shouldBuildSceneRequestAndParseOcrJson() throws IOException {
        AtomicReference<LlmRequest> capturedRequest = new AtomicReference<>();
        LlmClient llmClient = request -> {
            capturedRequest.set(request);
            return response("{\"rawText\":\"商品名称：合成纸巾\\n实付：12.50元\",\"warnings\":[\"版面较复杂\"]}");
        };
        LlmOrderImageModelClient client = client(llmClient);

        OcrResult result = client.recognize(image("order.jpg", new byte[]{1, 2, 3}));

        assertThat(result.rawText()).contains("合成纸巾").contains("实付");
        assertThat(result.warnings()).containsExactly("版面较复杂");
        assertThat(capturedRequest.get().scene()).isEqualTo("parse_order_image");
        assertThat(capturedRequest.get().promptVersion()).isEqualTo("model-ocr-v1");
        assertThat(capturedRequest.get().systemPrompt()).contains("订单截图 OCR 助手");
        assertThat(capturedRequest.get().images()).hasSize(1);
        assertThat(capturedRequest.get().images().get(0).mimeType()).isEqualTo("image/jpeg");
        assertThat(capturedRequest.get().images().get(0).content()).containsExactly(1, 2, 3);
    }

    @Test
    void shouldParseJsonInsideMarkdownCodeBlock() throws IOException {
        LlmOrderImageModelClient client = client(request -> response(
                "```json\n{\"rawText\":\"合成订单文本\",\"warnings\":[]}\n```"));

        OcrResult result = client.recognize(image("order.png", new byte[]{1}));

        assertThat(result.rawText()).isEqualTo("合成订单文本");
    }

    @Test
    void shouldMapSupportedImageMimeTypes() throws IOException {
        AtomicReference<LlmRequest> capturedRequest = new AtomicReference<>();
        LlmOrderImageModelClient client = client(request -> {
            capturedRequest.set(request);
            return response("{\"rawText\":\"ok\",\"warnings\":[]}");
        });

        client.recognize(image("first.png", new byte[]{1}));
        assertThat(capturedRequest.get().images().get(0).mimeType()).isEqualTo("image/png");
        client.recognize(image("second.webp", new byte[]{1}));
        assertThat(capturedRequest.get().images().get(0).mimeType()).isEqualTo("image/webp");
    }

    @Test
    void shouldRejectImageLargerThanConfiguredLimitBeforeCallingLlm() throws IOException {
        ParseOrderImageModelProperties properties = properties();
        properties.setMaxImageBytes(2);
        AtomicReference<LlmRequest> capturedRequest = new AtomicReference<>();
        LlmOrderImageModelClient client = new LlmOrderImageModelClient(
                properties, request -> {
                    capturedRequest.set(request);
                    return response("{\"rawText\":\"ok\"}");
                }, new ObjectMapper());

        assertThatThrownBy(() -> client.recognize(image("large.jpeg", new byte[]{1, 2, 3})))
                .isInstanceOf(OrderImageModelException.class)
                .hasMessageContaining("订单截图大小超过视觉模型限制");
        assertThat(capturedRequest).hasNullValue();
    }

    @Test
    void shouldRejectUnsupportedImageSuffix() throws IOException {
        LlmOrderImageModelClient client = client(request -> response("{\"rawText\":\"ok\"}"));

        assertThatThrownBy(() -> client.recognize(image("order.gif", new byte[]{1})))
                .isInstanceOf(OrderImageModelException.class)
                .hasMessage("视觉模型不支持该图片格式");
    }

    @Test
    void shouldRejectNonJsonContent() throws IOException {
        LlmOrderImageModelClient client = client(request -> response("plain text"));

        assertThatThrownBy(() -> client.recognize(image("invalid.png", new byte[]{1})))
                .isInstanceOf(OrderImageModelException.class)
                .hasMessage("视觉模型 content 不是有效 JSON");
    }

    @Test
    void shouldRejectBlankRawText() throws IOException {
        LlmOrderImageModelClient client = client(request -> response("{\"rawText\":\" \",\"warnings\":[]}"));

        assertThatThrownBy(() -> client.recognize(image("blank.png", new byte[]{1})))
                .isInstanceOf(OrderImageModelException.class)
                .hasMessage("视觉模型返回空文本");
    }

    @Test
    void shouldKeepImageInputDefensiveAndSafeToString() {
        byte[] originalContent = new byte[]{1, 2};
        com.jtxw.familyagent.infrastructure.llm.LlmImageInput imageInput =
                new com.jtxw.familyagent.infrastructure.llm.LlmImageInput(
                        "synthetic.jpg", "image/jpeg", originalContent);
        originalContent[0] = 9;
        byte[] returnedContent = imageInput.content();
        returnedContent[1] = 9;

        assertThat(imageInput.content()).containsExactly(1, 2);
        assertThat(imageInput.toString()).contains("contentSize=2").doesNotContain("[1, 2]");
    }

    /**
     * 使用默认合成配置和指定 LLM 客户端创建被测场景适配器。
     */
    private LlmOrderImageModelClient client(LlmClient llmClient) {
        return new LlmOrderImageModelClient(properties(), llmClient, new ObjectMapper());
    }

    /**
     * 创建启用视觉模型的合成配置，使用本地回环地址和占位密钥。
     */
    private ParseOrderImageModelProperties properties() {
        ParseOrderImageModelProperties properties = new ParseOrderImageModelProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:1/v1");
        properties.setApiKey("synthetic-key");
        properties.setModelName("synthetic-model");
        return properties;
    }

    /**
     * 构造包含指定 content 的合成 LLM 响应。
     */
    private LlmResponse response(String content) {
        return new LlmResponse(content, "fake", "synthetic-model", 1L, null, null);
    }

    /**
     * 在临时目录中创建合成图片文件并返回其路径。
     */
    private Path image(String fileName, byte[] content) throws IOException {
        return Files.write(temporaryDirectory.resolve(fileName), content);
    }
}
