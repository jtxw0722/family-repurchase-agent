package com.jtxw.familyagent.domain.policy;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/05/31/12:40
 * @Description: 容量类复购品规格解析器。
 */
@Component
public class VolumeSpecParser implements UnitSpecParser {
    private static final Pattern BASE_VOLUME = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(L|l|升|ml|mL|ML|毫升)");
    private static final Pattern MULTIPLIER = Pattern.compile("\\G\\s*[xX*×]\\s*(\\d+(?:\\.\\d+)?)\\s*([\\p{IsHan}A-Za-z]*)");

    @Override
    public boolean supports(UnitFamily unitFamily) {
        return unitFamily == UnitFamily.VOLUME;
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
        Matcher baseMatcher = BASE_VOLUME.matcher(normalized);
        if (!baseMatcher.find()) {
            return ProductSpecParseResult.notParsed();
        }

        double base = Double.parseDouble(baseMatcher.group(1));
        double quantityLiters = toLiters(base, baseMatcher.group(2));
        boolean reviewRequired = false;

        String tail = normalized.substring(baseMatcher.end());
        Matcher multiplierMatcher = MULTIPLIER.matcher(tail);
        while (multiplierMatcher.find()) {
            quantityLiters *= Double.parseDouble(multiplierMatcher.group(1));
            String multiplierUnit = multiplierMatcher.group(2) == null ? "" : multiplierMatcher.group(2);
            if (multiplierUnit.contains("次") || multiplierUnit.contains("卡")) {
                reviewRequired = true;
            }
        }

        if (normalized.contains("次卡") || normalized.contains("多次发货")) {
            reviewRequired = true;
        }
        return ProductSpecParseResult.parsed(quantityLiters, "L", reviewRequired);
    }

    private double toLiters(double value, String unit) {
        String normalizedUnit = unit.toLowerCase(Locale.ROOT);
        if ("ml".equals(normalizedUnit) || "毫升".equals(unit)) {
            return value / 1000D;
        }
        return value;
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
