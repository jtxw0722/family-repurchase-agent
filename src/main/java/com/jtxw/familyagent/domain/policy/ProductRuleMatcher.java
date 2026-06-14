package com.jtxw.familyagent.domain.policy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 16:50:03
 * @Description: 按优先级执行商品规则匹配，运行期通过 ProductRuleProvider 获取 SQLite 归一化规则。
 */
@Component
public class ProductRuleMatcher {
    /**
     * 商品规则提供接口，运行期每次匹配从 Provider 读取最新启用规则，避免引入全局缓存。
     */
    private final ProductRuleProvider productRuleProvider;

    @Autowired
    public ProductRuleMatcher(ProductRuleProvider productRuleProvider) {
        this.productRuleProvider = productRuleProvider;
    }

    /**
     * 根据商品名称匹配归一化规则。
     *
     * @param productName 原始商品名称
     * @return 商品规则匹配结果；未命中时返回 fallback 结果
     */
    public ProductRuleMatchResult match(String productName) {
        if (productName == null || productName.isBlank()) {
            return ProductRuleMatchResult.noMatch(productName, "未命名商品");
        }
        String name = productName.trim();
        for (ProductRule rule : sortedRules()) {
            if (matches(rule, name)) {
                return ProductRuleMatchResult.matched(rule, name);
            }
        }
        return ProductRuleMatchResult.noMatch(name, name);
    }

    /**
     * 查询并排序当前启用的商品规则。
     *
     * @return 按 priority 从高到低排列的规则列表
     */
    private List<ProductRule> sortedRules() {
        return productRuleProvider.listEnabledRules().stream()
                .sorted(Comparator.comparingInt(ProductRule::priority).reversed())
                .toList();
    }

    private boolean matches(ProductRule rule, String name) {
        // 排除词只排除当前规则，先于 include 检查，避免“猫砂盆”误命中“猫砂”。
        return hasNoExcludedKeyword(rule, name) && hasIncludedKeyword(rule, name);
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
