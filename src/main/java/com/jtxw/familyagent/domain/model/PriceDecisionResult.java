package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/00:36
 * @Description: 价格判断结果对象，承载当前单价、历史统计和决策说明。
 */
public record PriceDecisionResult(
        String productName,
        String normalizedName,
        double currentUnitPrice,
        String unit,
        Double historicalMin,
        Double historicalMedian,
        Double historicalAverage,
        int sampleSize,
        String decision,
        String decisionText,
        String reason
) {}
