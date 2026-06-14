package com.jtxw.familyagent.domain.policy;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 21:10:00
 * @Description: 测试用商品规则提供器工厂，负责为非 Spring 单测显式构造数据库化后的默认归一化规则依赖
 */
public final class TestProductRuleProviders {
    private TestProductRuleProviders() {
    }

    /**
     * 构造与 SQLite 初始化种子一致的测试规则提供器。
     *
     * @return 测试用商品规则提供器，不依赖本地配置文件
     */
    public static ProductRuleProvider defaultRules() {
        return () -> List.of(
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
    }

    /**
     * 构造默认商品归一化器，供非 Spring 单测显式注入规则来源。
     *
     * @return 测试用商品归一化器
     */
    public static ProductNormalizer productNormalizer() {
        return new ProductNormalizer(new ProductRuleMatcher(defaultRules()));
    }

    /**
     * 构造默认规格解析器集合，保持与 Spring 运行期注入的解析器类型一致。
     *
     * @return 测试用规格解析器集合
     */
    public static List<UnitSpecParser> defaultUnitSpecParsers() {
        return List.of(new WeightSpecParser(), new DrawCountSpecParser(), new VolumeSpecParser());
    }

    /**
     * 构造默认规格解析器，供非 Spring 单测显式注入商品归一化器。
     *
     * @return 测试用规格解析器
     */
    public static ProductSpecParser productSpecParser() {
        return new ProductSpecParser(productNormalizer(), defaultUnitSpecParsers());
    }

    /**
     * 构造商品名称归一化器，保留调用方自定义测试规则并显式传入默认商品规则提供器。
     *
     * @param rules 测试用名称归一化规则，允许为空列表
     * @return 测试用商品名称归一化器
     */
    public static ProductNameNormalizer productNameNormalizer(List<NormalizationRule> rules) {
        return new ProductNameNormalizer(productNormalizer(), rules);
    }
}
