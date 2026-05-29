package com.jtxw.familyagent.domain.policy;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/29/23:20
 * @Description: 商品归一化和规格解析的配置化匹配规则。
 */
public class ProductRule {
    private final String id;
    private final String normalizedName;
    private final int priority;
    private final List<String> includeKeywords;
    private final List<String> excludeKeywords;
    private final String standardUnit;
    private final UnitFamily unitFamily;

    public ProductRule(String id,
                       String normalizedName,
                       int priority,
                       List<String> includeKeywords,
                       List<String> excludeKeywords,
                       String standardUnit,
                       UnitFamily unitFamily) {
        this.id = id;
        this.normalizedName = normalizedName;
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
