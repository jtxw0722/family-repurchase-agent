package com.jtxw.familyagent.domain.policy;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 16:50:03
 * @Description: 商品归一化和规格解析的配置化匹配规则，承载名称、关键词、标准单位和单位族等核心匹配信息。
 */
public class ProductRule {
    /**
     * 规则业务编码，对应 normalization_rules.rule_code，不允许为空。
     */
    private final String id;
    /**
     * 归一化商品名称，用于写入购买记录 normalized_name，不允许为空。
     */
    private final String normalizedName;
    /**
     * 商品品类，用于名称库展示和 LLM 归一化上下文，允许为空。
     */
    private final String category;
    /**
     * 规则优先级，数值越大优先级越高，默认沿用初始化规则配置。
     */
    private final int priority;
    /**
     * 正向命中关键词，命中任意关键词后当前规则可作为候选。
     */
    private final List<String> includeKeywords;
    /**
     * 负向排除关键词，命中任意关键词后仅排除当前规则。
     */
    private final List<String> excludeKeywords;
    /**
     * 标准统计单位，例如 kg、L、抽；用于导入阶段规格解析和价格基准统计。
     */
    private final String standardUnit;
    /**
     * 单位族，例如 WEIGHT、VOLUME、DRAW_COUNT；用于判断规格解析和单位换算是否可自动完成。
     */
    private final UnitFamily unitFamily;

    public ProductRule(String id,
                       String normalizedName,
                       int priority,
                       List<String> includeKeywords,
                       List<String> excludeKeywords,
                       String standardUnit,
                       UnitFamily unitFamily) {
        this(id, normalizedName, "", priority, includeKeywords, excludeKeywords, standardUnit, unitFamily);
    }

    public ProductRule(String id,
                       String normalizedName,
                       String category,
                       int priority,
                       List<String> includeKeywords,
                       List<String> excludeKeywords,
                       String standardUnit,
                       UnitFamily unitFamily) {
        this.id = id;
        this.normalizedName = normalizedName;
        this.category = category == null ? "" : category;
        this.priority = priority;
        this.includeKeywords = includeKeywords == null ? List.of() : List.copyOf(includeKeywords);
        this.excludeKeywords = excludeKeywords == null ? List.of() : List.copyOf(excludeKeywords);
        this.standardUnit = standardUnit;
        this.unitFamily = unitFamily == null ? UnitFamily.UNKNOWN : unitFamily;
    }

    public String id() {
        return id;
    }

    public String normalizedName() {
        return normalizedName;
    }

    public String category() {
        return category;
    }

    public int priority() {
        return priority;
    }

    public List<String> includeKeywords() {
        return includeKeywords;
    }

    public List<String> excludeKeywords() {
        return excludeKeywords;
    }

    public String standardUnit() {
        return standardUnit;
    }

    public UnitFamily unitFamily() {
        return unitFamily;
    }
}
