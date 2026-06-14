package com.jtxw.familyagent.domain.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:02:39
 * @Description: 学习型商品归一化组件测试，验证商品名称归一化仅委托规则链路并保留低置信复核兜底
 */
class LearningProductNameNormalizerTest {
    @Test
    void shouldForceLegacyFallbackToReview() {
        Fixture fixture = fixture();

        ProductNameNormalizationResult result = fixture.normalizer().normalize("舒肤佳沐浴露清香型 720ml", "暂无");

        assertThat(result.matchedRule()).isEqualTo("legacy_fallback");
        assertThat(result.confidence()).isEqualTo(0.5D);
        assertThat(result.needReview()).isTrue();
    }

    @Test
    void shouldUseRuleMatchWithoutExtraLookup() {
        Fixture fixture = fixture();

        ProductNameNormalizationResult result = fixture.normalizer().normalize("猫砂 10kg", "默认");

        assertThat(result.normalizedName()).isEqualTo("猫砂");
        assertThat(result.targetUnit()).isEqualTo("kg");
        assertThat(result.matchedRule()).isEqualTo("cat_litter");
        assertThat(result.needReview()).isFalse();
    }

    private Fixture fixture() {
        ProductRule rule = new ProductRule("cat_litter", "猫砂", 100,
                List.of("猫砂"), List.of(), "kg", UnitFamily.WEIGHT);
        ProductNameNormalizer delegate = new ProductNameNormalizer(
                new ProductNormalizer(new ProductRuleMatcher(() -> List.of(rule))), List.of());
        return new Fixture(new LearningProductNameNormalizer(delegate));
    }

    private record Fixture(LearningProductNameNormalizer normalizer) {
    }
}
