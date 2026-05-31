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

        assertThat(normalizer.normalize("名创优品钠基矿猫砂5kg*8包")).isEqualTo("猫砂");
    }

    @Test
    void shouldNotNormalizeCatLitterAccessoriesAsCatLitter() {
        ProductNormalizer normalizer = new ProductNormalizer();

        assertThat(normalizer.normalize("猫砂盆全封闭抽屉顶入式")).isNotEqualTo("猫砂");
        assertThat(normalizer.normalize("猫砂铲")).isNotEqualTo("猫砂");
        assertThat(normalizer.normalize("猫厕所超大号")).isNotEqualTo("猫砂");
    }

    @Test
    void shouldNormalizeConfiguredRepurchaseProducts() {
        ProductNormalizer normalizer = new ProductNormalizer();

        assertThat(normalizer.normalize("全价猫粮 10kg")).isEqualTo("猫粮");
        assertThat(normalizer.normalize("原生木浆抽纸 24包")).isEqualTo("纸巾");
        assertThat(normalizer.normalize("除菌洗衣液 3L")).isEqualTo("洗衣液");
    }

    @Test
    void shouldReturnStructuredNormalizationResultForTissue() {
        ProductNormalizer normalizer = new ProductNormalizer();

        ProductNormalizationResult result = normalizer.normalizeProduct("维达超韧抽纸 3层130抽×24包（195×133mm）");

        assertThat(result.normalizedName()).isEqualTo("纸巾");
        assertThat(result.preferredUnitFamily()).isEqualTo(UnitFamily.DRAW_COUNT);
        assertThat(result.standardUnit()).isEqualTo("抽");
        assertThat(result.reviewRequired()).isFalse();
    }
}
