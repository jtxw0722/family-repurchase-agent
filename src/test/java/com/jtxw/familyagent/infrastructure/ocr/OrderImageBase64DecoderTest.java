package com.jtxw.familyagent.infrastructure.ocr;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 08:21:54
 * @Description: 订单截图 Base64 解析组件测试，覆盖 data URL、纯 Base64、MIME 推断和安全错误信息
 */
class OrderImageBase64DecoderTest {
    /** 合成图片字节内容，不包含真实订单截图。 */
    private static final byte[] IMAGE_BYTES = new byte[]{1, 2, 3, 4};
    /** 合成图片字节对应的 Base64 文本，用于验证错误信息不泄露原始载荷。 */
    private static final String IMAGE_BASE64 = Base64.getEncoder().encodeToString(IMAGE_BYTES);

    @Test
    void shouldDecodeJpegDataUrl() {
        OrderImageInput input = decoder().decode("data:image/jpeg;base64," + IMAGE_BASE64,
                "order.jpg", null);

        assertThat(input.sourceType()).isEqualTo(OrderImageInputType.BASE64);
        assertThat(input.displayName()).isEqualTo("order.jpg");
        assertThat(input.mimeType()).isEqualTo("image/jpeg");
        assertThat(input.content()).containsExactly(IMAGE_BYTES);
    }

    @Test
    void shouldDecodePngDataUrl() {
        OrderImageInput input = decoder().decode("data:image/png;base64," + IMAGE_BASE64,
                "order.png", null);

        assertThat(input.mimeType()).isEqualTo("image/png");
        assertThat(input.content()).containsExactly(IMAGE_BYTES);
    }

    @Test
    void shouldDecodeWebpDataUrl() {
        OrderImageInput input = decoder().decode("data:image/webp;base64," + IMAGE_BASE64,
                "order.webp", null);

        assertThat(input.mimeType()).isEqualTo("image/webp");
        assertThat(input.content()).containsExactly(IMAGE_BYTES);
    }

    @Test
    void shouldDecodePlainBase64WithMimeType() {
        OrderImageInput input = decoder().decode(IMAGE_BASE64, "ignored.gif", "image/jpeg");

        assertThat(input.mimeType()).isEqualTo("image/jpeg");
        assertThat(input.displayName()).isEqualTo("ignored.gif");
    }

    @Test
    void shouldDecodePlainBase64WithFileNameSuffix() {
        OrderImageInput input = decoder().decode(IMAGE_BASE64, "C:\\Users\\tester\\order.png", null);

        assertThat(input.mimeType()).isEqualTo("image/png");
        assertThat(input.displayName()).isEqualTo("order.png");
    }

    @Test
    void shouldRejectInvalidBase64WithSafeMessage() {
        String invalidBase64 = "not-a-real-base64";

        assertThatThrownBy(() -> decoder().decode(invalidBase64, "order.jpg", "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("imageBase64 不是合法的 Base64 图片内容")
                .hasMessageNotContaining(invalidBase64);
    }

    @Test
    void shouldRejectUnsupportedMimeType() {
        assertThatThrownBy(() -> decoder().decode("data:image/gif;base64," + IMAGE_BASE64,
                "order.gif", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不支持的图片 MIME 类型：image/gif")
                .hasMessageNotContaining(IMAGE_BASE64);
    }

    @Test
    void shouldRejectOversizedImageWithSafeMessage() {
        ParseOrderImageModelProperties properties = new ParseOrderImageModelProperties();
        properties.setMaxImageBytes(2);
        OrderImageBase64Decoder decoder = new OrderImageBase64Decoder(properties);

        assertThatThrownBy(() -> decoder.decode(IMAGE_BASE64, "order.jpg", "image/jpeg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("订单截图大小超过限制：4 > 2 字节")
                .hasMessageNotContaining(IMAGE_BASE64);
    }

    @Test
    void shouldRejectUnknownMimeTypeForPlainBase64() {
        assertThatThrownBy(() -> decoder().decode(IMAGE_BASE64, "order.unknown", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("无法判断图片 MIME 类型，请提供 imageMimeType 或 imageFileName");
    }

    @Test
    void shouldKeepContentDefensiveAndSafeToString() {
        OrderImageInput input = decoder().decode(IMAGE_BASE64, "order.jpg", "image/jpeg");
        byte[] content = input.content();
        content[0] = 9;

        assertThat(input.content()).containsExactly(IMAGE_BYTES);
        assertThat(input.toString())
                .contains("contentSize=4")
                .doesNotContain(IMAGE_BASE64)
                .doesNotContain(new String(IMAGE_BYTES, StandardCharsets.ISO_8859_1));
    }

    /**
     * 创建使用默认图片大小限制的 Base64 解码器。
     */
    private OrderImageBase64Decoder decoder() {
        return new OrderImageBase64Decoder(new ParseOrderImageModelProperties());
    }
}
