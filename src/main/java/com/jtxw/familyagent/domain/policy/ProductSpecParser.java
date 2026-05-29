package com.jtxw.familyagent.domain.policy;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/05/29/22:30
 * @Description: 从 SKU 和商品名称解析可比价的复购品规格。
 */
@Component
public class ProductSpecParser {
    private static final Pattern BASE_WEIGHT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(kg|KG|Kg|公斤|千克|斤)");
    private static final Pattern MULTIPLIER = Pattern.compile("\\G\\s*[xX*×]\\s*(\\d+(?:\\.\\d+)?)\\s*([\\p{IsHan}A-Za-z]*)");

    public ProductSpecParseResult parse(String sku, String productName) {
        return parseWeight(sku, productName);
    }

    public ProductSpecParseResult parse(String sku, String productName, ProductRuleMatchResult ruleMatch) {
        if (ruleMatch != null && ruleMatch.unitFamily() != UnitFamily.WEIGHT) {
            return ProductSpecParseResult.notParsed();
        }
        return parseWeight(sku, productName);
    }

    private ProductSpecParseResult parseWeight(String sku, String productName) {
        ProductSpecParseResult skuResult = parseOne(sku);
        if (skuResult.parsed()) {
            return skuResult;
        }
        return parseOne(productName);
    }

    private ProductSpecParseResult parseOne(String text) {
        if (text == null || text.isBlank()) {
            return ProductSpecParseResult.notParsed();
        }
        String normalized = normalizeText(text);
        Matcher baseMatcher = BASE_WEIGHT.matcher(normalized);
        if (!baseMatcher.find()) {
            return ProductSpecParseResult.notParsed();
        }

        double base = Double.parseDouble(baseMatcher.group(1));
        String baseUnit = baseMatcher.group(2).toLowerCase(Locale.ROOT);
        double quantityKg = "斤".equals(baseUnit) ? base * 0.5D : base;

        boolean reviewRequired = false;
        String tail = normalized.substring(baseMatcher.end());
        Matcher multiplierMatcher = MULTIPLIER.matcher(tail);
        while (multiplierMatcher.find()) {
            double multiplier = Double.parseDouble(multiplierMatcher.group(1));
            String multiplierUnit = multiplierMatcher.group(2) == null ? "" : multiplierMatcher.group(2);
            quantityKg *= multiplier;
            if (multiplierUnit.contains("次") || multiplierUnit.contains("卡")) {
                reviewRequired = true;
            }
        }

        if (normalized.contains("次卡") || normalized.contains("多次发货")) {
            reviewRequired = true;
        }
        return ProductSpecParseResult.parsed(quantityKg, "kg", reviewRequired);
    }

    private String normalizeText(String text) {
        return text.trim()
                .replace('（', '(')
                .replace('）', ')')
                .replace('＊', '*')
                .replace('Ｘ', 'X')
                .replace('×', 'x');
    }

    public static class ProductSpecParseResult {
        private final boolean parsed;
        private final Double quantity;
        private final String unit;
        private final boolean reviewRequired;

        private ProductSpecParseResult(boolean parsed, Double quantity, String unit, boolean reviewRequired) {
            this.parsed = parsed;
            this.quantity = quantity;
            this.unit = unit;
            this.reviewRequired = reviewRequired;
        }

        public static ProductSpecParseResult notParsed() {
            return new ProductSpecParseResult(false, null, null, false);
        }

        public static ProductSpecParseResult parsed(Double quantity, String unit, boolean reviewRequired) {
            return new ProductSpecParseResult(true, quantity, unit, reviewRequired);
        }

        public boolean parsed() {
            return parsed;
        }

        public Double quantity() {
            return quantity;
        }

        public String unit() {
            return unit;
        }

        public boolean reviewRequired() {
            return reviewRequired;
        }
    }
}
