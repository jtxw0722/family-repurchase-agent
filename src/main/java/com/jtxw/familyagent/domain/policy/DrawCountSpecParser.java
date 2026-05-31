package com.jtxw.familyagent.domain.policy;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/05/31/11:45
 * @Description: 抽纸类商品抽数规格解析器。
 */
@Component
public class DrawCountSpecParser implements UnitSpecParser {
    private static final Pattern DRAW_PACK = Pattern.compile("(\\d+)\\s*抽\\s*(?:[xX*×]\\s*)?(\\d+)\\s*包");
    private static final Pattern PACKAGE_ONLY = Pattern.compile("\\d+\\s*包");

    @Override
    public boolean supports(UnitFamily unitFamily) {
        return unitFamily == UnitFamily.DRAW_COUNT;
    }

    @Override
    public ProductSpecParseResult parse(String sku, String productName) {
        ProductSpecParseResult skuResult = parseOne(sku);
        if (skuResult.parsed() || skuResult.reviewRequired()) {
            return skuResult;
        }
        return parseOne(productName);
    }

    private ProductSpecParseResult parseOne(String text) {
        if (text == null || text.isBlank()) {
            return ProductSpecParseResult.notParsed();
        }
        String normalized = normalizeText(text);
        Matcher matcher = DRAW_PACK.matcher(normalized);
        if (matcher.find()) {
            double drawsPerPack = Double.parseDouble(matcher.group(1));
            double packs = Double.parseDouble(matcher.group(2));
            return ProductSpecParseResult.parsed(drawsPerPack * packs, "抽", false);
        }
        if (PACKAGE_ONLY.matcher(normalized).find()) {
            return ProductSpecParseResult.requiresReview();
        }
        return ProductSpecParseResult.notParsed();
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
