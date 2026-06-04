package com.jtxw.familyagent.domain.policy;

import java.util.List;
import java.util.Objects;

/**
 * @Author: jtxw
 * @Date: 2026/06/04
 * @Description: 商品名称归一化规则，描述标准品类、目标单位、别名和匹配优先级。
 */
public record NormalizationRule(
        /**
         * 规则标识，用于 matchedRule 和复核证据。
         */
        String ruleId,
        /**
         * 归一化后的标准品类名称。
         */
        String normalizedName,
        /**
         * 该品类用于价格比较的目标单位。
         */
        String targetUnit,
        /**
         * 可命中的商品标题或 SKU 别名。
         */
        List<String> aliases,
        /**
         * 匹配优先级，数值越大越优先。
         */
        int priority
) {
    public NormalizationRule {
        aliases = aliases == null ? List.of() : aliases.stream()
                .filter(Objects::nonNull)
                .toList();
    }
}
