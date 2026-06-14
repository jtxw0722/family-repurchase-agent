package com.jtxw.familyagent.domain.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class QuantityUnitParserTest {
    private final ProductNameNormalizer normalizer = TestProductRuleProviders.productNameNormalizer(testRules());
    private final QuantityUnitParser parser = new QuantityUnitParser();

    @Test
    void shouldParseLaundryBeadsMultipack() {
        ProductNameNormalizationResult name = normalizer.normalize("洗衣凝珠", "12颗*3盒");
        QuantityUnitParseResult result = parse(name, "洗衣凝珠", "12颗*3盒", 36.0D, 1D);

        assertThat(name.normalizedName()).isEqualTo("洗衣凝珠");
        assertThat(result.unit()).isEqualTo("颗");
        assertThat(result.quantity()).isEqualTo(36D);
        assertThat(result.unitPrice()).isCloseTo(1.0D, offset(0.000001D));
        assertThat(result.needReview()).isFalse();
    }

    @Test
    void shouldPreferMultiplicationExpressionOverTotalCountHint() {
        QuantityUnitParseResult result = parser.parse(
                "测试计数品",
                "条",
                "测试计数品",
                "【1次囤够量|48条】4条*12包",
                120.03D,
                1D,
                "件"
        );

        assertThat(result.unit()).isEqualTo("条");
        assertThat(result.quantity()).isEqualTo(48D);
        assertThat(result.unitPrice()).isCloseTo(2.500625D, offset(0.000001D));
        assertThat(result.needReview()).isFalse();
    }

    @Test
    void shouldStillSumSingleCountExpressionsWhenNoMultiplicationExists() {
        QuantityUnitParseResult result = parser.parse(
                "测试计数品",
                "片",
                "测试计数品",
                "270mm*80片+295mm*27片",
                216.58D,
                1D,
                "件"
        );

        assertThat(result.unit()).isEqualTo("片");
        assertThat(result.quantity()).isEqualTo(107D);
        assertThat(result.needReview()).isFalse();
    }

    @Test
    void shouldParseLaundryBeadsPlusExpression() {
        ProductNameNormalizationResult name = normalizer.normalize("凝珠", "10颗+2颗");
        QuantityUnitParseResult result = parse(name, "凝珠", "10颗+2颗", 24.0D, 1D);

        assertThat(name.normalizedName()).isEqualTo("洗衣凝珠");
        assertThat(result.unit()).isEqualTo("颗");
        assertThat(result.quantity()).isEqualTo(12D);
        assertThat(result.unitPrice()).isCloseTo(2.0D, offset(0.000001D));
        assertThat(result.needReview()).isFalse();
    }

    @Test
    void shouldParseSingleLaundryBeadsCount() {
        ProductNameNormalizationResult name = normalizer.normalize("洗衣珠", "30颗");
        QuantityUnitParseResult result = parse(name, "洗衣珠", "30颗", 45.0D, 1D);

        assertThat(name.normalizedName()).isEqualTo("洗衣凝珠");
        assertThat(result.unit()).isEqualTo("颗");
        assertThat(result.quantity()).isEqualTo(30D);
        assertThat(result.unitPrice()).isCloseTo(1.5D, offset(0.000001D));
        assertThat(result.needReview()).isFalse();
    }

    @Test
    void shouldParseCountUnitForInjectedProductWithoutHardcodedName() {
        ProductNameNormalizer coffeeNormalizer = TestProductRuleProviders.productNameNormalizer(java.util.List.of(
                new NormalizationRule("test_coffee_capsule", "咖啡胶囊", "颗",
                        java.util.List.of("咖啡胶囊"), 100)
        ));
        ProductNameNormalizationResult name = coffeeNormalizer.normalize("咖啡胶囊", "10颗*3盒");

        QuantityUnitParseResult result = parse(name, "咖啡胶囊", "10颗*3盒", 60.0D, 1D);

        assertThat(name.normalizedName()).isEqualTo("咖啡胶囊");
        assertThat(result.unit()).isEqualTo("颗");
        assertThat(result.quantity()).isEqualTo(30D);
        assertThat(result.unitPrice()).isCloseTo(2.0D, offset(0.000001D));
        assertThat(result.needReview()).isFalse();
    }

    @Test
    void shouldMultiplyLaundryBeadsSkuCountByRawQuantity() {
        ProductNameNormalizationResult name = normalizer.normalize("洗衣凝珠", "12颗");
        QuantityUnitParseResult result = parse(name, "洗衣凝珠", "12颗", 72.0D, 3D);

        assertThat(name.normalizedName()).isEqualTo("洗衣凝珠");
        assertThat(result.unit()).isEqualTo("颗");
        assertThat(result.quantity()).isEqualTo(36D);
        assertThat(result.unitPrice()).isCloseTo(2.0D, offset(0.000001D));
        assertThat(result.needReview()).isFalse();
    }

    @Test
    void shouldRequireReviewForLaundryBeadsWithoutCount() {
        ProductNameNormalizationResult name = normalizer.normalize("洗衣凝珠", "暂无");
        QuantityUnitParseResult result = parse(name, "洗衣凝珠", "暂无", 29.9D, 1D);

        assertThat(name.normalizedName()).isEqualTo("洗衣凝珠");
        assertThat(result.unit()).isEqualTo("颗");
        assertThat(result.needReview()).isTrue();
    }

    @Test
    void shouldPassThroughGenericProducts() {
        ProductNameNormalizationResult name = normalizer.normalize("洗衣液补充装", "暂无");
        QuantityUnitParseResult result = parse(name, "洗衣液补充装", "暂无", 29.9D, 1D);

        assertThat(name.normalizedName()).isEqualTo("洗衣用品");
        assertThat(result.unit()).isEqualTo("件");
        assertThat(result.quantity()).isEqualTo(1D);
        assertThat(result.needReview()).isFalse();
    }

    private QuantityUnitParseResult parse(ProductNameNormalizationResult name,
                                          String productName,
                                          String sku,
                                          Double price,
                                          Double rawQuantity) {
        return parser.parse(name.normalizedName(), name.targetUnit(), productName, sku, price, rawQuantity, "件");
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
