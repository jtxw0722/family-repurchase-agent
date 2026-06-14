package com.jtxw.familyagent.domain.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/05/29/22:35
 * @Description: 复购品规格解析规则的单元测试。
 */
class ProductSpecParserTest {
    private final ProductSpecParser parser = TestProductRuleProviders.productSpecParser();
    private final ProductRuleMatcher matcher = new ProductRuleMatcher(TestProductRuleProviders.defaultRules());

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
        ProductSpecParseResult tissueResult = parser.parse("10斤*8包", "抽纸10斤*8包", tissue);
        assertThat(tissueResult.parsed()).isFalse();
        assertThat(tissueResult.reviewRequired()).isTrue();
    }

    @Test
    void shouldParseTissueDrawCountSpecs() {
        ProductSpecParseResult result = parser.parse("", "维达超韧抽纸 3层130抽×24包（195×133mm）");

        assertThat(result.parsed()).isTrue();
        assertThat(result.quantity()).isEqualTo(3120D);
        assertThat(result.unit()).isEqualTo("抽");
        assertThat(result.reviewRequired()).isFalse();
    }

    @Test
    void shouldParseTissuePackageSkuWithTitleDrawCount() {
        ProductSpecParseResult result = parser.parse("48包",
                "洁柔无香纸巾face抽纸面巾纸130抽8包家用实惠装整箱卫生餐巾纸");

        assertThat(result.parsed()).isTrue();
        assertThat(result.quantity()).isEqualTo(6240D);
        assertThat(result.unit()).isEqualTo("抽");
        assertThat(result.reviewRequired()).isFalse();
    }

    @Test
    void shouldParseTissueDrawCountWithoutExplicitMultiplySign() {
        ProductSpecParseResult result = parser.parse("暂无", "某品牌原生木浆抽纸100抽20包");

        assertThat(result.parsed()).isTrue();
        assertThat(result.quantity()).isEqualTo(2000D);
        assertThat(result.unit()).isEqualTo("抽");
        assertThat(result.reviewRequired()).isFalse();
    }

    @Test
    void shouldNotParseDimensionsAsDrawCountSpecs() {
        ProductSpecParseResult result = parser.parse("", "维达超韧抽纸 3层4包（195×133mm）");

        assertThat(result.parsed()).isFalse();
        assertThat(result.reviewRequired()).isTrue();
    }

    @Test
    void shouldRequireReviewWhenDrawCountSpecMissesDrawsPerPack() {
        ProductSpecParseResult result = parser.parse("", "维达超韧抽纸 24包");

        assertThat(result.parsed()).isFalse();
        assertThat(result.reviewRequired()).isTrue();
    }

    @Test
    void shouldKeepRealCatLitterTimesCardReview() {
        ProductSpecParseResult result = parser.parse("2.5kg*4包*6次", "猫砂次卡");

        assertThat(result.parsed()).isTrue();
        assertThat(result.quantity()).isEqualTo(60D);
        assertThat(result.unit()).isEqualTo("kg");
        assertThat(result.reviewRequired()).isTrue();
    }

    @Test
    void shouldParseLiterMultipacks() {
        ProductSpecParseResult result = parser.parse("", "洗衣液 2L*3瓶");

        assertThat(result.parsed()).isTrue();
        assertThat(result.quantity()).isEqualTo(6D);
        assertThat(result.unit()).isEqualTo("L");
        assertThat(result.reviewRequired()).isFalse();
    }

    @Test
    void shouldConvertMilliliterMultipacksToLiter() {
        ProductSpecParseResult result = parser.parse("", "洗衣液 500ml*4瓶");

        assertThat(result.parsed()).isTrue();
        assertThat(result.quantity()).isEqualTo(2D);
        assertThat(result.unit()).isEqualTo("L");
        assertThat(result.reviewRequired()).isFalse();
    }

    @Test
    void shouldConvertSingleMilliliterSpecToLiter() {
        ProductSpecParseResult result = parser.parse("", "洗衣液 750ml");

        assertThat(result.parsed()).isTrue();
        assertThat(result.quantity()).isEqualTo(0.75D);
        assertThat(result.unit()).isEqualTo("L");
        assertThat(result.reviewRequired()).isFalse();
    }

    @Test
    void shouldPreferSkuForVolumeSpecs() {
        ProductSpecParseResult result = parser.parse("500ml*4瓶", "洗衣液 2L*3瓶");

        assertThat(result.parsed()).isTrue();
        assertThat(result.quantity()).isEqualTo(2D);
        assertThat(result.unit()).isEqualTo("L");
        assertThat(result.reviewRequired()).isFalse();
    }

    private void assertSpec(String text, double quantity, boolean reviewRequired) {
        ProductSpecParseResult result = parser.parse(text, "");

        assertThat(result.parsed()).isTrue();
        assertThat(result.quantity()).isEqualTo(quantity);
        assertThat(result.unit()).isEqualTo("kg");
        assertThat(result.reviewRequired()).isEqualTo(reviewRequired);
    }
}
