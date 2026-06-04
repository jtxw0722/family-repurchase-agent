package com.jtxw.familyagent.domain.policy;


/**
 * @Author: jtxw
 * @Date: 2026/05/31/8:44
 * @Description: 价格判断阈值
 */

public record PriceDecisionThresholds(
        double goodPriceMedianFactor,
        double expensivePriceMedianFactor
) {
    public PriceDecisionThresholds {
        if (goodPriceMedianFactor <= 0 || goodPriceMedianFactor >= 1) {
            throw new IllegalArgumentException("goodPriceMedianFactor 必须大于 0 且小于 1");
        }
        if (expensivePriceMedianFactor <= 1) {
            throw new IllegalArgumentException("expensivePriceMedianFactor 必须大于 1");
        }
    }
}
