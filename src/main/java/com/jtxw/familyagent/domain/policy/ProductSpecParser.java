package com.jtxw.familyagent.domain.policy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/29/22:30
 * @Description: 从 SKU 和商品名称解析可比价的复购品规格，并按单位族分发到具体解析器。
 */
@Component
public class ProductSpecParser {
    private final ProductNormalizer productNormalizer;
    private final List<UnitSpecParser> parsers;

    @Autowired
    public ProductSpecParser(ProductNormalizer productNormalizer, List<UnitSpecParser> parsers) {
        this.productNormalizer = productNormalizer;
        this.parsers = parsers == null ? List.of() : List.copyOf(parsers);
    }

    public ProductSpecParseResult parse(String sku, String productName) {
        ProductNormalizationResult normalization = productNormalizer.normalizeProduct(productName);
        ProductSpecParseResult result = parseByFamily(sku, productName, normalization.preferredUnitFamily());
        if (result.parsed() || result.reviewRequired()) {
            return result;
        }
        if (normalization.preferredUnitFamily() == UnitFamily.UNKNOWN) {
            return parseByFamily(sku, productName, UnitFamily.WEIGHT);
        }
        return result;
    }

    public ProductSpecParseResult parse(String sku, String productName, ProductRuleMatchResult ruleMatch) {
        if (ruleMatch == null) {
            return parse(sku, productName);
        }
        ProductSpecParseResult result = parseByFamily(sku, productName, ruleMatch.unitFamily());
        if (result.parsed() || result.reviewRequired()) {
            return result;
        }
        if (ruleMatch.unitFamily() == UnitFamily.UNKNOWN) {
            return parseByFamily(sku, productName, UnitFamily.WEIGHT);
        }
        return result;
    }

    private ProductSpecParseResult parseByFamily(String sku, String productName, UnitFamily unitFamily) {
        for (UnitSpecParser parser : parsers) {
            if (parser.supports(unitFamily)) {
                return parser.parse(sku, productName);
            }
        }
        return ProductSpecParseResult.notParsed();
    }

}
