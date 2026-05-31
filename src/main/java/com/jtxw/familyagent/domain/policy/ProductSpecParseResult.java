package com.jtxw.familyagent.domain.policy;


/**
 * @Author: jtxw
 * @Date: 2026/05/31/12:25
 * @Description: 商品规格解析结果，表示是否成功解析出标准数量和单位，以及是否需要人工复核。
 */

public class ProductSpecParseResult {

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

    public static ProductSpecParseResult requiresReview() {
        return new ProductSpecParseResult(false, null, null, true);
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
