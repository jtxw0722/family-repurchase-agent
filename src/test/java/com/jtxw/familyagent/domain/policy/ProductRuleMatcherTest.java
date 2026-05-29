package com.jtxw.familyagent.domain.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/05/29/23:30
 * @Description: 配置化商品规则匹配的单元测试。
 */
class ProductRuleMatcherTest {
    private final ProductRuleMatcher matcher = new ProductRuleMatcher();

    @Test
    void shouldMatchCatLitterAsWeightRule() {
        ProductRuleMatchResult result = matcher.match("名创优品钠基矿猫砂5kg*8包");

        assertThat(result.matched()).isTrue();
        assertThat(result.normalizedName()).isEqualTo("猫砂");
        assertThat(result.standardUnit()).isEqualTo("kg");
        assertThat(result.unitFamily()).isEqualTo(UnitFamily.WEIGHT);
    }

    @Test
    void shouldExcludeCatLitterAccessoriesFromCatLitterRule() {
        assertThat(matcher.match("猫砂盆全封闭抽屉顶入式").normalizedName()).isNotEqualTo("猫砂");
        assertThat(matcher.match("猫厕所超大号").normalizedName()).isNotEqualTo("猫砂");
    }

    @Test
    void shouldMatchOtherConfiguredProducts() {
        assertThat(matcher.match("全价猫粮 10kg").normalizedName()).isEqualTo("猫粮");
        assertThat(matcher.match("原生木浆抽纸 24包").normalizedName()).isEqualTo("纸巾");
        assertThat(matcher.match("除菌洗衣液 3L").normalizedName()).isEqualTo("洗衣液");
    }
}
