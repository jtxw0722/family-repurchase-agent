package com.jtxw.familyagent.domain.policy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/04/19:39
 * @Description: 商品名称归一化组件，根据可注入规则将原始商品标题和 SKU 归一为标准品类与目标单位。
 */
@Component
public class ProductNameNormalizer {
    private static final List<NormalizationRule> DEFAULT_RULES = List.of(
            new NormalizationRule("sleep_pants_keyword", "安睡裤", "条",
                    List.of("安睡裤", "安心裤", "裤型卫生巾", "夜用裤型"), 1000),
            new NormalizationRule("sanitary_pad_keyword", "卫生巾", "片",
                    List.of("卫生巾", "姨妈巾"), 900)
    );

    private final ProductNormalizer productNormalizer;
    private final List<NormalizationRule> rules;

    @Autowired
    public ProductNameNormalizer(ProductNormalizer productNormalizer) {
        this(productNormalizer, DEFAULT_RULES);
    }

    public ProductNameNormalizer(ProductNormalizer productNormalizer, List<NormalizationRule> rules) {
        this.productNormalizer = productNormalizer;
        this.rules = rules == null ? List.of() : rules.stream()
                .sorted(Comparator.comparingInt(NormalizationRule::priority).reversed())
                .toList();
    }

    /**
     * 按“商品标题 + SKU”执行归一化。
     *
     * @param productName 原始商品标题
     * @param sku         原始规格/SKU 文本
     * @return 标准品类、目标单位、置信度和复核标记
     */
    public ProductNameNormalizationResult normalize(String productName, String sku) {
        String text = combinedText(productName, sku);
        for (NormalizationRule rule : rules) {
            if (containsAny(text, rule.aliases())) {
                return new ProductNameNormalizationResult(
                        rule.normalizedName(),
                        rule.targetUnit(),
                        0.95D,
                        rule.ruleId(),
                        false
                );
            }
        }

        ProductRuleMatchResult productNameMatch = productNormalizer.match(productName);
        if (productNameMatch.matched()) {
            return fromRuleMatch(productNameMatch);
        }
        ProductRuleMatchResult skuMatch = productNormalizer.match(sku);
        if (skuMatch.matched()) {
            return fromRuleMatch(skuMatch);
        }

        // 兼容旧逻辑：未命中任何规则时仍返回旧 ProductNormalizer 的结果，不改变历史品类行为。
        ProductNormalizationResult fallback = productNormalizer.normalizeProduct(productName);
        return new ProductNameNormalizationResult(
                fallback.normalizedName(),
                fallback.standardUnit(),
                0.5D,
                "legacy_fallback",
                true
        );
    }

    private ProductNameNormalizationResult fromRuleMatch(ProductRuleMatchResult match) {
        return new ProductNameNormalizationResult(
                match.normalizedName(),
                match.standardUnit(),
                0.9D,
                match.ruleId(),
                false
        );
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (text.contains(keyword.trim())) {
                return true;
            }
        }
        return false;
    }

    private String combinedText(String productName, String sku) {
        return safeText(productName) + " " + safeText(sku);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
