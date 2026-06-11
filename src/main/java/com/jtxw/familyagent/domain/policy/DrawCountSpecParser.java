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
    private static final Pattern DRAW_PACK = Pattern.compile("(\\d+)\\s*抽\\s*(?:[xX*×]?\\s*)(\\d+)\\s*包");
    private static final Pattern PACKAGE_ONLY = Pattern.compile("\\d+\\s*包");
    private static final Pattern PACKAGE_COUNT = Pattern.compile("(\\d+)\\s*包");

    @Override
    public boolean supports(UnitFamily unitFamily) {
        return unitFamily == UnitFamily.DRAW_COUNT;
    }

    @Override
    public ProductSpecParseResult parse(String sku, String productName) {
        ProductSpecParseResult skuResult = parseOne(sku);
        if (skuResult.parsed()) {
            return skuResult;
        }
        ProductSpecParseResult combinedResult = parsePackageCountWithTitleDraws(sku, productName);
        if (combinedResult.parsed()) {
            return combinedResult;
        }
        ProductSpecParseResult productNameResult = parseOne(productName);
        if (productNameResult.parsed() || productNameResult.reviewRequired()) {
            return productNameResult;
        }
        return skuResult.reviewRequired() ? skuResult : ProductSpecParseResult.notParsed();
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

    /**
     * 处理“SKU 只有总包数、标题包含每包抽数”的纸巾规格。
     *
     * <p>淘宝 SKU 常写成“48包”，而标题写“130抽8包”。此时 SKU 的 48 包才是本次购买包数，
     * 标题中的 130 抽提供每包抽数，不能把 48 包误判为多次权益。</p>
     *
     * @param sku         商品 SKU 文本，允许为空
     * @param productName 商品标题文本，允许为空
     * @return 可解析时返回抽数总量，否则返回未解析
     */
    private ProductSpecParseResult parsePackageCountWithTitleDraws(String sku, String productName) {
        Integer skuPackages = parsePackageCount(sku);
        if (skuPackages == null || productName == null || productName.isBlank()) {
            return ProductSpecParseResult.notParsed();
        }
        Matcher matcher = DRAW_PACK.matcher(normalizeText(productName));
        if (!matcher.find()) {
            return ProductSpecParseResult.notParsed();
        }
        double drawsPerPack = Double.parseDouble(matcher.group(1));
        return ProductSpecParseResult.parsed(drawsPerPack * skuPackages, "抽", false);
    }

    /**
     * 从纯包装 SKU 中读取包数。
     *
     * @param text SKU 或规格文本，允许为空
     * @return 包数；无法解析时返回 null
     */
    private Integer parsePackageCount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = PACKAGE_COUNT.matcher(normalizeText(text));
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
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
