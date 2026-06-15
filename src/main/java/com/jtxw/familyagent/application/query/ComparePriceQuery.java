package com.jtxw.familyagent.application.query;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 09:30:00
 * @Description: 商品价格分析查询，用于承载 compare-price 用例的基准线查询和价格比较两种输入模式。
 *
 * @param productName 原始商品名称，不允许为空，会在服务端进行本地规则归一化
 * @param price       当前购买总价；baseline-only 模式为空，compare 模式必须大于 0
 * @param quantity    当前购买数量；baseline-only 模式为空，compare 模式必须大于 0
 * @param unit        当前数量单位；baseline-only 模式为空，compare 模式必须为非空字符串
 */
public record ComparePriceQuery(
        String productName,
        Double price,
        Double quantity,
        String unit
) {
    /**
     * 价格参数组必须同时提供或同时省略，避免半完整输入进入价格分析服务。
     */
    public ComparePriceQuery {
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName 不能为空，必须是非空字符串");
        }
        boolean hasPrice = price != null;
        boolean hasQuantity = quantity != null;
        boolean hasUnit = unit != null;
        if (!(hasPrice == hasQuantity && hasPrice == hasUnit)) {
            throw new IllegalArgumentException("price、quantity、unit 必须同时提供，或同时省略。");
        }
        if (hasPrice && price <= 0D) {
            throw new IllegalArgumentException("price 必须大于 0");
        }
        if (hasQuantity && quantity <= 0D) {
            throw new IllegalArgumentException("quantity 必须大于 0");
        }
        if (hasUnit && unit.isBlank()) {
            throw new IllegalArgumentException("unit 不能为空");
        }
        productName = productName.trim();
        unit = hasUnit ? unit.trim() : null;
    }

    /**
     * 判断当前请求是否只查询历史价格基准线。
     *
     * @return 不包含当前价格样本时返回 true
     */
    public boolean baselineOnly() {
        return price == null && quantity == null && unit == null;
    }
}
