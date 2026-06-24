package com.jtxw.familyagent.infrastructure.ocr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 08:34:00
 * @Description: 订单截图隐私脱敏器测试，验证收货信息隐藏和商品字段保留边界
 */
class OrderImagePrivacySanitizerTest {
    /**
     * 被测订单截图隐私脱敏器。
     */
    private final OrderImagePrivacySanitizer sanitizer = new OrderImagePrivacySanitizer();

    @Test
    void shouldHideFullMobileNumber() {
        assertThat(sanitizer.sanitizeRawText("手机号：13012340624"))
                .isEqualTo("手机号：[手机号已隐藏]");
    }

    @Test
    void shouldHidePartiallyMaskedMobileNumber() {
        assertThat(sanitizer.sanitizeRawText("电话：130****0624"))
                .isEqualTo("电话：[手机号已隐藏]");
    }

    @Test
    void shouldHideOrderNumber() {
        assertThat(sanitizer.sanitizeRawText("订单编号：260604398102382163163"))
                .isEqualTo("订单编号：[编号已隐藏]");
    }

    @Test
    void shouldHideTrackingNumber() {
        assertThat(sanitizer.sanitizeRawText("快递单号：777415590765148"))
                .isEqualTo("快递单号：[编号已隐藏]");
    }

    @Test
    void shouldHideWholeShippingInfoLine() {
        assertThat(sanitizer.sanitizeRawText("肖华 130****0624 南环路179号1栋"))
                .isEqualTo("[收货信息已隐藏]");
    }

    @Test
    void shouldNotHideProductTitleWithPersonNameWords() {
        String rawText = "商品名称：赵露思同款包包\n商品名称：刘亦菲同款耳环";

        assertThat(sanitizer.sanitizeRawText(rawText))
                .contains("赵露思同款包包")
                .contains("刘亦菲同款耳环")
                .doesNotContain("[姓名已隐藏]");
    }

    @Test
    void shouldNotHideSpecValues() {
        String rawText = "规格：596ml*24瓶\n容量：10L\n重量：1.5kg";

        assertThat(sanitizer.sanitizeRawText(rawText))
                .contains("596ml*24瓶")
                .contains("10L")
                .contains("1.5kg")
                .doesNotContain("[编号已隐藏]");
    }

    @Test
    void shouldNotHidePriceValues() {
        String rawText = "价格：¥25.73\n实付：¥18.9";

        assertThat(sanitizer.sanitizeRawText(rawText))
                .contains("¥25.73")
                .contains("¥18.9")
                .doesNotContain("[编号已隐藏]");
    }
}
