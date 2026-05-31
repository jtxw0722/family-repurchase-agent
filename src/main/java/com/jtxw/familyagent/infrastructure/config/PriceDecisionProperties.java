package com.jtxw.familyagent.infrastructure.config;


import com.jtxw.familyagent.domain.policy.PriceDecisionThresholds;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @Author: jtxw
 * @Date: 2026/05/31/8:43
 * @Description: 价格判断相关配置
 */
@ConfigurationProperties(prefix = "family-agent.price-decision")
public class PriceDecisionProperties {
    /**
     * 好价判断系数。
     * 当前单位价格低于历史中位价乘以该系数时，可判断为好价
     * 默认值 0.92 表示低于历史中位价约 8%
     */
    private double goodPriceMedianFactor = 0.92D;
    /**
     * 偏贵判断系数。
     * 当前单位价格高于历史中位价乘以该系数时，可判断为偏贵
     * 默认值 1.12 表示高于历史中位价约 12%
     */
    private double expensivePriceMedianFactor = 1.12D;

    public double getGoodPriceMedianFactor() {
        return goodPriceMedianFactor;
    }

    public void setGoodPriceMedianFactor(double goodPriceMedianFactor) {
        this.goodPriceMedianFactor = goodPriceMedianFactor;
    }

    public double getExpensivePriceMedianFactor() {
        return expensivePriceMedianFactor;
    }

    public void setExpensivePriceMedianFactor(double expensivePriceMedianFactor) {
        this.expensivePriceMedianFactor = expensivePriceMedianFactor;
    }

    public PriceDecisionThresholds toThresholds() {
        return new PriceDecisionThresholds(goodPriceMedianFactor, expensivePriceMedianFactor);
    }
}
