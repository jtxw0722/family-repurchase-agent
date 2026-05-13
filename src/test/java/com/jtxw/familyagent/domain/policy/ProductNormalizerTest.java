package com.jtxw.familyagent.domain.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/05/13/15:36
 * @Description: 商品名称归一化规则的单元测试。
 */
class ProductNormalizerTest {
    @Test
    void shouldNormalizeCatLitter() {
        ProductNormalizer normalizer = new ProductNormalizer();
        assertThat(normalizer.normalize("某品牌混合猫砂 12kg")).isEqualTo("猫砂");
    }
}
