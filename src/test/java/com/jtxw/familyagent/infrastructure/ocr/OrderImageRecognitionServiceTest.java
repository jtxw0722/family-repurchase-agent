package com.jtxw.familyagent.infrastructure.ocr;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 14:32:44
 * @Description: 订单截图识别编排服务测试，覆盖模型开关、模型优先、本地 OCR 兜底和敏感配置脱敏
 */
class OrderImageRecognitionServiceTest {
    /** 测试使用的虚拟图片路径，客户端均为内存桩，不读取该文件。 */
    private static final Path IMAGE_PATH = Path.of("synthetic-order.png");
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
}
