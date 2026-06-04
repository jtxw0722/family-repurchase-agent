package com.jtxw.familyagent.domain.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductNameNormalizerTest {
    private final ProductNameNormalizer normalizer = new ProductNameNormalizer(testRules());

    @Test
    void shouldNormalizeSpecificRuleBeforeGenericRule() {
        ProductNameNormalizationResult result = normalizer.normalize("洗衣用品 洗衣凝珠", "12颗*3盒");

        assertThat(result.normalizedName()).isEqualTo("洗衣凝珠");
        assertThat(result.targetUnit()).isEqualTo("颗");
        assertThat(result.matchedRule()).isEqualTo("test_laundry_beads");
        assertThat(result.needReview()).isFalse();
    }

    @Test
    void shouldNormalizeByAlias() {
        ProductNameNormalizationResult result = normalizer.normalize("凝珠", "10颗+2颗");

        assertThat(result.normalizedName()).isEqualTo("洗衣凝珠");
        assertThat(result.targetUnit()).isEqualTo("颗");
        assertThat(result.needReview()).isFalse();
    }

    @Test
    void shouldNormalizeGenericRuleWhenSpecificAliasIsAbsent() {
        ProductNameNormalizationResult result = normalizer.normalize("洗衣液补充装", "");

        assertThat(result.normalizedName()).isEqualTo("洗衣用品");
        assertThat(result.targetUnit()).isEqualTo("件");
        assertThat(result.matchedRule()).isEqualTo("test_laundry_supplies");
    }

    @Test
    void shouldIgnoreBlankAliases() {
        ProductNameNormalizer blankAliasNormalizer = new ProductNameNormalizer(java.util.List.of(
                new NormalizationRule("blank_alias_rule", "空白别名商品", "件",
                        java.util.Arrays.asList("", "   ", null), 100)
        ));

        ProductNameNormalizationResult result = blankAliasNormalizer.normalize("无关商品", "暂无");

        assertThat(result.normalizedName()).isEqualTo("无关商品");
        assertThat(result.matchedRule()).isEqualTo("legacy_fallback");
    }

    @Test
    void shouldKeepLegacyRulesForOtherProducts() {
        ProductNameNormalizationResult result = normalizer.normalize("名创优品钠基矿猫砂5kg*8包", "");

        assertThat(result.normalizedName()).isEqualTo("猫砂");
        assertThat(result.targetUnit()).isEqualTo("kg");
        assertThat(result.matchedRule()).isEqualTo("cat_litter");
    }

    private static java.util.List<NormalizationRule> testRules() {
        return java.util.List.of(
                new NormalizationRule("test_laundry_beads", "洗衣凝珠", "颗",
                        java.util.List.of("洗衣凝珠", "凝珠", "洗衣珠"), 100),
                new NormalizationRule("test_laundry_supplies", "洗衣用品", "件",
                        java.util.List.of("洗衣液", "洗衣用品"), 10)
        );
    }
}
