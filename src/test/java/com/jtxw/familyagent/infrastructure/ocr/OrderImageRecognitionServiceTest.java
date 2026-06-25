package com.jtxw.familyagent.infrastructure.ocr;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 09:21:54
 * @Description: 订单截图识别编排服务测试，覆盖模型开关、模型优先、Base64 临时文件、本地 OCR 兜底和敏感配置脱敏
 */
class OrderImageRecognitionServiceTest {
    /** 测试使用的虚拟图片路径，客户端均为内存桩，不读取该文件。 */
    private static final Path IMAGE_PATH = Path.of("synthetic-order.png");
    /** 测试使用的合成内存图片，不包含真实订单截图。 */
    private static final byte[] IMAGE_BYTES = new byte[]{1, 2, 3};
    /** 用于验证异常和 warning 不泄露的虚拟 API Key。 */
    private static final String SECRET_API_KEY = "unit-test-secret-key";

    @Test
    void shouldUseLocalOcrDirectlyWhenModelDisabled() {
        AtomicBoolean modelCalled = new AtomicBoolean();
        AtomicBoolean localCalled = new AtomicBoolean();
        ParseOrderImageModelProperties properties = properties(false, true);
        OrderImageRecognitionService service = new OrderImageRecognitionService(
                path -> {
                    localCalled.set(true);
                    return result("local text");
                },
                path -> {
                    modelCalled.set(true);
                    return result("model text");
                }, properties);

        OcrResult result = service.recognize(IMAGE_PATH);

        assertThat(result.rawText()).isEqualTo("local text");
        assertThat(localCalled).isTrue();
        assertThat(modelCalled).isFalse();
    }

    @Test
    void shouldUseModelWithoutCallingLocalOcrWhenModelSucceeds() {
        AtomicBoolean localCalled = new AtomicBoolean();
        OrderImageRecognitionService service = new OrderImageRecognitionService(
                path -> {
                    localCalled.set(true);
                    return result("local text");
                }, path -> result("model text"), properties(true, true));

        OcrResult result = service.recognize(IMAGE_PATH);

        assertThat(result.rawText()).isEqualTo("model text");
        assertThat(localCalled).isFalse();
    }

    @Test
    void shouldFallbackToLocalOcrWithSafeWarningWhenModelFails() {
        ParseOrderImageModelProperties properties = properties(true, true);
        OrderImageRecognitionService service = new OrderImageRecognitionService(
                path -> result("local text"),
                path -> { throw new OrderImageModelException("连接失败：" + SECRET_API_KEY); },
                properties);

        OcrResult result = service.recognize(IMAGE_PATH);

        assertThat(result.rawText()).isEqualTo("local text");
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("视觉模型识别失败，已回退本地 OCR"));
        assertThat(result.warnings()).allMatch(warning -> !warning.contains(SECRET_API_KEY));
    }

    @Test
    void shouldThrowSafeErrorWhenFallbackDisabled() {
        ParseOrderImageModelProperties properties = properties(true, false);
        OrderImageRecognitionService service = new OrderImageRecognitionService(
                path -> result("local text"),
                path -> { throw new OrderImageModelException("连接失败：" + SECRET_API_KEY); },
                properties);

        assertThatThrownBy(() -> service.recognize(IMAGE_PATH))
                .isInstanceOfSatisfying(OrderImageModelException.class, exception -> {
                    assertThat(exception.getMessage())
                            .contains("视觉模型识别失败，且未启用本地 OCR 兜底")
                            .doesNotContain(SECRET_API_KEY);
                    assertThat(exception.getCause()).isNull();
                });
    }

    @Test
    void shouldFallbackWhenModelReturnsBlankText() {
        OrderImageRecognitionService service = new OrderImageRecognitionService(
                path -> result("local text"), path -> result(" "), properties(true, true));

        OcrResult result = service.recognize(IMAGE_PATH);

        assertThat(result.rawText()).isEqualTo("local text");
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("视觉模型返回空文本"));
    }

    @Test
    void shouldUseBase64ModelInputWithoutCallingLocalOcrWhenModelSucceeds() {
        AtomicBoolean localCalled = new AtomicBoolean();
        AtomicBoolean modelCalled = new AtomicBoolean();
        OrderImageRecognitionService service = new OrderImageRecognitionService(
                path -> {
                    localCalled.set(true);
                    return result("local text");
                },
                modelClient(input -> {
                    modelCalled.set(true);
                    assertThat(input.sourceType()).isEqualTo(OrderImageInputType.BASE64);
                    assertThat(input.content()).containsExactly(IMAGE_BYTES);
                    return result("model text");
                }),
                properties(true, true));

        OcrResult result = service.recognize(base64Input());

        assertThat(result.rawText()).isEqualTo("model text");
        assertThat(modelCalled).isTrue();
        assertThat(localCalled).isFalse();
    }

    @Test
    void shouldCreateAndDeleteTemporaryFileForBase64WhenModelDisabled() {
        AtomicReference<Path> capturedTempPath = new AtomicReference<>();
        OrderImageRecognitionService service = new OrderImageRecognitionService(
                path -> {
                    capturedTempPath.set(path);
                    assertThat(Files.exists(path)).isTrue();
                    assertThat(path.getFileName().toString()).startsWith("family-order-image-");
                    return result("local text");
                },
                modelClient(input -> result("model text")),
                properties(false, true));

        OcrResult result = service.recognize(base64Input());

        assertThat(result.rawText()).isEqualTo("local text");
        assertThat(capturedTempPath.get()).isNotNull();
        assertThat(Files.exists(capturedTempPath.get())).isFalse();
    }

    @Test
    void shouldCreateAndDeleteTemporaryFileForBase64FallbackWhenModelFails() {
        AtomicReference<Path> capturedTempPath = new AtomicReference<>();
        OrderImageRecognitionService service = new OrderImageRecognitionService(
                path -> {
                    capturedTempPath.set(path);
                    assertThat(Files.exists(path)).isTrue();
                    return result("local text");
                },
                modelClient(input -> {
                    throw new OrderImageModelException("合成模型失败：" + SECRET_API_KEY);
                }),
                properties(true, true));

        OcrResult result = service.recognize(base64Input());

        assertThat(result.rawText()).isEqualTo("local text");
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("视觉模型识别失败，已回退本地 OCR"));
        assertThat(result.warnings()).allMatch(warning -> !warning.contains(SECRET_API_KEY));
        assertThat(Files.exists(capturedTempPath.get())).isFalse();
    }

    @Test
    void shouldNotCallLocalOcrForBase64WhenModelFailsAndFallbackDisabled() {
        AtomicBoolean localCalled = new AtomicBoolean();
        OrderImageRecognitionService service = new OrderImageRecognitionService(
                path -> {
                    localCalled.set(true);
                    return result("local text");
                },
                modelClient(input -> {
                    throw new OrderImageModelException("合成模型失败");
                }),
                properties(true, false));

        assertThatThrownBy(() -> service.recognize(base64Input()))
                .isInstanceOf(OrderImageModelException.class)
                .hasMessageContaining("视觉模型识别失败，且未启用本地 OCR 兜底");
        assertThat(localCalled).isFalse();
    }

    /**
     * 创建指定开关和兜底策略的合成视觉模型配置。
     */
    private ParseOrderImageModelProperties properties(boolean enabled, boolean fallbackToLocalOcr) {
        ParseOrderImageModelProperties properties = new ParseOrderImageModelProperties();
        properties.setEnabled(enabled);
        properties.setFallbackToLocalOcr(fallbackToLocalOcr);
        properties.setApiKey(SECRET_API_KEY);
        return properties;
    }

    /**
     * 构造仅包含 rawText 的合成 OCR 结果。
     */
    private OcrResult result(String rawText) {
        return new OcrResult(rawText, null, List.of());
    }

    /**
     * 创建合成 Base64 图片输入对象。
     */
    private OrderImageInput base64Input() {
        return OrderImageInput.base64("order.jpg", "image/jpeg", IMAGE_BYTES);
    }

    /**
     * 创建支持内存图片识别的视觉模型客户端桩。
     */
    private OrderImageModelClient modelClient(java.util.function.Function<OrderImageInput, OcrResult> action) {
        return new OrderImageModelClient() {
            @Override
            public OcrResult recognize(Path imagePath) {
                return action.apply(OrderImageInput.path(imagePath));
            }

            @Override
            public OcrResult recognize(OrderImageInput imageInput) {
                return action.apply(imageInput);
            }
        };
    }
}
