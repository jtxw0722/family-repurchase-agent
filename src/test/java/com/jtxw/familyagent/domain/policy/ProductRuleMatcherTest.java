package com.jtxw.familyagent.domain.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 17:15:00
 * @Description: 配置化商品规则匹配的单元测试，通过显式 ProductRuleProvider 构造测试规则，避免混用旧本地配置 fallback。
 */
class ProductRuleMatcherTest {
    /**
     * 测试用商品规则 Provider，显式构造与默认 SQLite 种子规则一致的核心规则。
     */
    private static final ProductRuleProvider TEST_RULE_PROVIDER = () -> List.of(
            new ProductRule("cat_litter", "猫砂", "宠物用品", 100,
                    List.of("猫砂", "猫沙", "豆腐砂", "膨润土", "矿砂", "混合砂", "植物砂"),
                    List.of("猫砂盆", "猫厕所", "猫屎盆", "猫砂铲", "防带砂"),
                    "kg", UnitFamily.WEIGHT),
            new ProductRule("cat_food", "猫粮", "宠物用品", 90,
                    List.of("猫粮", "幼猫粮", "成猫粮", "全价猫粮"),
                    List.of("猫粮勺", "储粮桶"),
                    "kg", UnitFamily.WEIGHT),
            new ProductRule("tissue", "纸巾", "日用品", 80,
                    List.of("纸巾", "抽纸", "面巾纸"),
                    List.of("纸巾盒", "收纳盒", "湿巾", "卷纸"),
                    "抽", UnitFamily.DRAW_COUNT),
            new ProductRule("laundry_detergent", "洗衣液", "日用品", 80,
                    List.of("洗衣液"),
                    List.of("洗衣液瓶", "分装瓶"),
                    "L", UnitFamily.VOLUME)
    );

    /**
     * 被测商品规则匹配器，使用显式测试 Provider，不依赖 yml fallback。
     */
    private final ProductRuleMatcher matcher = new ProductRuleMatcher(TEST_RULE_PROVIDER);

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
    void shouldOnlyExcludeCurrentRuleWhenAnotherRuleIncludesSameKeyword() {
        ProductRuleMatcher customMatcher = new ProductRuleMatcher(() -> List.of(
                new ProductRule("cat_litter", "猫砂", "宠物用品", 100,
                        List.of("猫砂"), List.of("猫砂盆"), "kg", UnitFamily.WEIGHT),
                new ProductRule("pet_accessory", "宠物用品配件", "宠物用品", 90,
                        List.of("猫砂盆"), List.of(), "个", UnitFamily.COUNT)
        ));

        assertThat(customMatcher.match("猫砂盆大号防外溅").matched()).isTrue();
        assertThat(customMatcher.match("猫砂盆大号防外溅").normalizedName()).isEqualTo("宠物用品配件");
    }

    @Test
    void shouldMatchOtherConfiguredProducts() {
        assertThat(matcher.match("全价猫粮 10kg").normalizedName()).isEqualTo("猫粮");
        assertThat(matcher.match("原生木浆抽纸 24包").normalizedName()).isEqualTo("纸巾");
        assertThat(matcher.match("除菌洗衣液 3L").normalizedName()).isEqualTo("洗衣液");
    }

    @Test
    void shouldMatchTissueAsDrawCountRule() {
        ProductRuleMatchResult result = matcher.match("维达超韧抽纸 3层130抽×24包（195×133mm）");

        assertThat(result.matched()).isTrue();
        assertThat(result.normalizedName()).isEqualTo("纸巾");
        assertThat(result.standardUnit()).isEqualTo("抽");
        assertThat(result.unitFamily()).isEqualTo(UnitFamily.DRAW_COUNT);
    }

    @Test
    void shouldMatchTissueAsVolumeRule() {
        ProductRuleMatchResult result = matcher.match("除菌洗衣液 2L*3瓶");

        assertThat(result.normalizedName()).isEqualTo("洗衣液");
        assertThat(result.standardUnit()).isEqualTo("L");
        assertThat(result.unitFamily()).isEqualTo(UnitFamily.VOLUME);
    }
}
