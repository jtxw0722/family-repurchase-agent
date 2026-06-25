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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 09:21:54
 * @Description: 订单截图 LLM 场景适配器测试，验证提示词、本地或内存图片边界、请求语义和 OCR JSON 解析
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
                }, new ObjectMapper(), new OrderImagePrivacySanitizer());

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

    @Test
    void shouldSanitizePrivacyInfoFromModelRawText() throws IOException {
        LlmOrderImageModelClient client = client(request -> response("{\"rawText\":\"收货人：张三\\n"
                + "张三 130****0624 南环路179号1栋\\n订单编号：260604398102382163163\\n"
                + "快递单号：777415590765148\\n商品名称：赵露思同款包包\\n规格：596ml*24瓶\\n实付：¥25.73\","
                + "\"warnings\":[\"存在隐私字段\"]}"));

        OcrResult result = client.recognize(image("privacy.jpg", new byte[]{1, 2, 3}));

        assertThat(result.rawText())
                .contains("[姓名已隐藏]")
                .contains("[收货信息已隐藏]")
                .contains("订单编号：[编号已隐藏]")
                .contains("快递单号：[编号已隐藏]")
                .contains("商品名称：赵露思同款包包")
                .contains("规格：596ml*24瓶")
                .contains("实付：¥25.73")
                .doesNotContain("张三")
                .doesNotContain("130****0624")
                .doesNotContain("南环路179号")
                .doesNotContain("260604398102382163163")
                .doesNotContain("777415590765148");
        assertThat(result.warnings()).containsExactly("存在隐私字段");
    }

    @Test
    void shouldBuildLlmImageInputFromBase64ImageInput() {
        byte[] imageBytes = new byte[]{5, 6, 7};
        String base64Text = Base64.getEncoder().encodeToString(imageBytes);
        AtomicReference<LlmRequest> capturedRequest = new AtomicReference<>();
        LlmOrderImageModelClient client = client(request -> {
            capturedRequest.set(request);
            return response("{\"rawText\":\"商品名称：内存图片咖啡\\n规格：268ml\",\"warnings\":[]}");
        });

        OcrResult result = client.recognize(OrderImageInput.base64("order.jpg", "image/jpeg", imageBytes));

        assertThat(result.rawText()).contains("内存图片咖啡");
        assertThat(capturedRequest.get().images()).hasSize(1);
        assertThat(capturedRequest.get().images().get(0).fileName()).isEqualTo("order.jpg");
        assertThat(capturedRequest.get().images().get(0).mimeType()).isEqualTo("image/jpeg");
        assertThat(capturedRequest.get().images().get(0).content()).containsExactly(imageBytes);
        assertThat(capturedRequest.get().userPrompt()).doesNotContain(base64Text);
        assertThat(capturedRequest.get().images().get(0).toString()).doesNotContain(base64Text);
    }

    @Test
    void shouldUseBase64MimeTypeWhenFileNameSuffixDiffers() {
        AtomicReference<LlmRequest> capturedRequest = new AtomicReference<>();
        LlmOrderImageModelClient client = client(request -> {
            capturedRequest.set(request);
            return response("{\"rawText\":\"ok\",\"warnings\":[]}");
        });

        client.recognize(OrderImageInput.base64("order.gif", "image/webp", new byte[]{1, 2}));

        assertThat(capturedRequest.get().images().get(0).fileName()).isEqualTo("order.gif");
        assertThat(capturedRequest.get().images().get(0).mimeType()).isEqualTo("image/webp");
    }


    /**
     * 使用默认合成配置和指定 LLM 客户端创建被测场景适配器。
     */
    private LlmOrderImageModelClient client(LlmClient llmClient) {
        return new LlmOrderImageModelClient(properties(), llmClient,
                new ObjectMapper(), new OrderImagePrivacySanitizer());
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
