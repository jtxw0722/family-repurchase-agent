package com.jtxw.familyagent.domain.policy;

import org.springframework.stereotype.Component;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/10:16
 * @Description: 单位价格计算规则，负责根据金额、数量和单位计算可比较单价。
 */
@Component
public class UnitPriceCalculator {
    /**
     * 根据统计金额和数量计算可比较的单位价格。
     *
     * @param totalAmount 当前统计金额
     * @param quantity    商品数量，必须大于 0
     * @return 单位价格
     */
    public double calculate(double totalAmount, double quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于 0");
        }
        return totalAmount / quantity;
    }
}
