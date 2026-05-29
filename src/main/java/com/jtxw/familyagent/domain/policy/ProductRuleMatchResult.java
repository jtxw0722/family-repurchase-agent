package com.jtxw.familyagent.domain.policy;

/**
 * @Author: jtxw
 * @Date: 2026/05/29/23:20
 * @Description: 商品规则匹配结果，用于归一化和规格解析决策。
 */
public class ProductRuleMatchResult {
    private final boolean matched;
    private final String ruleId;
    private final String normalizedName;
    private final String standardUnit;
    private final UnitFamily unitFamily;
    private final int priority;
    private final String sourceName;

    private ProductRuleMatchResult(boolean matched,
                                   String ruleId,
                                   String normalizedName,
                                   String standardUnit,
                                   UnitFamily unitFamily,
                                   int priority,
                                   String sourceName) {
        this.matched = matched;
        this.ruleId = ruleId;
        this.normalizedName = normalizedName;
        this.standardUnit = standardUnit;
        this.unitFamily = unitFamily == null ? UnitFamily.UNKNOWN : unitFamily;
        this.priority = priority;
        this.sourceName = sourceName;
    }

    public static ProductRuleMatchResult matched(ProductRule rule, String sourceName) {
        return new ProductRuleMatchResult(true, rule.id(), rule.normalizedName(), rule.standardUnit(),
                rule.unitFamily(), rule.priority(), sourceName);
    }

    public static ProductRuleMatchResult noMatch(String sourceName, String fallbackName) {
        return new ProductRuleMatchResult(false, null, fallbackName, null, UnitFamily.UNKNOWN, 0, sourceName);
    }

    public boolean matched() {
        return matched;
    }

    public String ruleId() {
        return ruleId;
    }

    public String normalizedName() {
        return normalizedName;
    }

    public String standardUnit() {
        return standardUnit;
    }

    public UnitFamily unitFamily() {
        return unitFamily;
    }

    public int priority() {
        return priority;
    }

    public String sourceName() {
        return sourceName;
    }
}
