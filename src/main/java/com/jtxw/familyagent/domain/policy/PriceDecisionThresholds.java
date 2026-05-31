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
            throw new IllegalArgumentException("goodPriceMedianFactor must be greater than 0 and less than 1");
        }
        if (expensivePriceMedianFactor <= 1) {
            throw new IllegalArgumentException("expensivePriceMedianFactor must be greater than 1");
        }
    }
}