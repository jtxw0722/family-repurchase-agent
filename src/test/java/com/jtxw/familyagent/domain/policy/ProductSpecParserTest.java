package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.policy.ProductSpecParser.ProductSpecParseResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/05/29/22:35
 * @Description: 复购品规格解析规则的单元测试。
 */
class ProductSpecParserTest {
    private final ProductSpecParser parser = new ProductSpecParser();
    private final ProductRuleMatcher matcher = new ProductRuleMatcher();

    @Test
    void shouldParseKgMultipacks() {
        assertSpec("5kg*8包", 40D, false);
        assertSpec("4.5kg*2包", 9D, false);
        assertSpec("15kg", 15D, false);
    }

    @Test
    void shouldParseJinSpecsAsKg() {
        assertSpec("10斤*8包", 40D, false);
        assertSpec("20斤", 10D, false);
        assertSpec("3.8斤", 1.9D, false);
    }

    @Test
    void shouldParseMultipleMultipliersAndRequireReview() {
        assertSpec("2.5kg*4包*6次", 60D, true);
    }

    @Test
    void shouldPreferSkuBeforeProductName() {
        ProductSpecParseResult result = parser.parse("【除臭加倍】升级款自然原味10斤*8包", "名创优品钠基矿猫砂5kg*8包");

        assertThat(result.parsed()).isTrue();
        assertThat(result.quantity()).isEqualTo(40D);
        assertThat(result.unit()).isEqualTo("kg");
    }

    @Test
    void shouldParseWeightOnlyWhenRuleFamilyIsWeight() {
        ProductRuleMatchResult catLitter = matcher.match("名创优品钠基矿猫砂5kg*8包");
        ProductRuleMatchResult tissue = matcher.match("抽纸10斤*8包");

        assertThat(parser.parse("10斤*8包", "名创优品钠基矿猫砂5kg*8包", catLitter).quantity()).isEqualTo(40D);
        assertThat(parser.parse("10斤*8包", "抽纸10斤*8包", tissue).parsed()).isFalse();
    }

    private void assertSpec(String text, double quantity, boolean reviewRequired) {
        ProductSpecParseResult result = parser.parse(text, "");

        assertThat(result.parsed()).isTrue();
        assertThat(result.quantity()).isEqualTo(quantity);
        assertThat(result.unit()).isEqualTo("kg");
        assertThat(result.reviewRequired()).isEqualTo(reviewRequired);
    }
}
