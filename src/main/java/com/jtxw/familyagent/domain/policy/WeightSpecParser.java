package com.jtxw.familyagent.domain.policy;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/05/31/11:45
 * @Description: 重量类复购品规格解析器。
 */
@Component
public class WeightSpecParser implements UnitSpecParser {
    private static final Pattern BASE_WEIGHT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(kg|KG|Kg|公斤|千克|斤)");
    private static final Pattern MULTIPLIER = Pattern.compile("\\G\\s*[xX*×]\\s*(\\d+(?:\\.\\d+)?)\\s*([\\p{IsHan}A-Za-z]*)");

    @Override
    public boolean supports(UnitFamily unitFamily) {
        return unitFamily == UnitFamily.WEIGHT;
    }

    @Override
    public ProductSpecParseResult parse(String sku, String productName) {
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
            if (isMultiDeliveryMultiplier(multiplierUnit)) {
                reviewRequired = true;
            }
        }

        if (containsMultiDeliveryTerm(normalized)) {
            reviewRequired = true;
        }
        return ProductSpecParseResult.parsed(quantityKg, "kg", reviewRequired);
    }

    /**
     * 判断乘法尾部单位是否表达多次权益。
     *
     * <p>包、盒、提、抽等普通包装单位不属于多次权益；只有“次”或“次卡”等履约次数才需要人工复核。</p>
     *
     * @param multiplierUnit 乘法表达式中数字后的单位文本
     * @return 是否属于多次权益单位
     */
    private boolean isMultiDeliveryMultiplier(String multiplierUnit) {
        if (multiplierUnit == null || multiplierUnit.isBlank()) {
            return false;
        }
        return multiplierUnit.contains("次");
    }

    /**
     * 判断完整规格文本是否包含明确多次发货或权益卡语义。
     *
     * @param normalized 已归一化符号的规格文本
     * @return 是否包含多次权益语义
     */
    private boolean containsMultiDeliveryTerm(String normalized) {
        return normalized.contains("次卡")
                || normalized.contains("多次发货")
                || normalized.contains("分次发货")
                || normalized.contains("按月发")
                || normalized.contains("月卡")
                || normalized.contains("季卡")
                || normalized.contains("年卡");
    }

    private String normalizeText(String text) {
        return text.trim()
                .replace('（', '(')
                .replace('）', ')')
                .replace('＊', '*')
                .replace('Ｘ', 'X')
                .replace('×', 'x');
    }
}
