package com.jtxw.familyagent.domain.policy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/29/23:20
 * @Description: 按优先级执行商品规则匹配。
 */
@Component
public class ProductRuleMatcher {
    private final List<ProductRule> rules;

    public ProductRuleMatcher() {
        this(new ProductRuleProperties());
    }

    @Autowired
    public ProductRuleMatcher(ProductRuleProperties properties) {
        this.rules = properties.rules().stream()
                .sorted(Comparator.comparingInt(ProductRule::priority).reversed())
                .toList();
    }

    public ProductRuleMatchResult match(String productName) {
        if (productName == null || productName.isBlank()) {
            return ProductRuleMatchResult.noMatch(productName, "未命名商品");
        }
        String name = productName.trim();
        for (ProductRule rule : rules) {
            if (matches(rule, name)) {
                return ProductRuleMatchResult.matched(rule, name);
            }
        }
        return ProductRuleMatchResult.noMatch(name, name);
    }

    private boolean matches(ProductRule rule, String name) {
        return hasIncludedKeyword(rule, name) && hasNoExcludedKeyword(rule, name);
    }

    private boolean hasIncludedKeyword(ProductRule rule, String name) {
        for (String keyword : rule.includeKeywords()) {
            if (name.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNoExcludedKeyword(ProductRule rule, String name) {
        for (String keyword : rule.excludeKeywords()) {
            if (name.contains(keyword)) {
                return false;
            }
        }
        return true;
    }
}
