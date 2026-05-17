package com.jtxw.familyagent.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/00:36
 * @Description: 价格判断结果对象，承载当前单价、历史统计和决策说明。
 */
@Schema(description = "价格判断结果")
public class PriceDecisionResult {
    /**
     * 原始商品名称
     */
    @Schema(description = "原始商品名称", example = "猫砂")
    private final String productName;
    /**
     * 归一化后的商品名称
     */
    @Schema(description = "归一化后的商品名称，用于匹配本地历史价格样本", example = "猫砂")
    private final String normalizedName;
    /**
     * 本次输入价格折算后的单位价格
     */
    @Schema(description = "本次输入价格折算后的单位价格", example = "7.42")
    private final double currentUnitPrice;
    /**
     * 单位价格使用的计量单位
     */
    @Schema(description = "单位价格使用的计量单位", example = "kg")
    private final String unit;
    /**
     * 历史最低单位价格；样本不足时为空
     */
    @Schema(description = "历史最低单位价格，样本不足时为空", example = "6.90", nullable = true)
    private final Double historicalMin;
    /**
     * 历史单位价格中位数；样本不足时为空
     */
    @Schema(description = "历史单位价格中位数，样本不足时为空", example = "7.50", nullable = true)
    private final Double historicalMedian;
    /**
     * 历史单位价格平均值；样本不足时为空
     */
    @Schema(description = "历史单位价格平均值，样本不足时为空", example = "7.62", nullable = true)
    private final Double historicalAverage;
    /**
     * 用于本次价格判断的历史样本数量
     */
    @Schema(description = "用于本次价格判断的历史样本数量", example = "5")
    private final int sampleSize;
    /**
     * 机器可读的价格判断编码
     */
    @Schema(description = "机器可读的价格判断编码", example = "normal", allowableValues = {"no_history", "good_price", "normal", "expensive"})
    private final String decision;
    /**
     * 面向用户展示的价格判断文案
     */
    @Schema(description = "面向用户展示的价格判断文案", example = "价格正常")
    private final String decisionText;
    /**
     * 价格判断原因说明
     */
    @Schema(description = "价格判断原因说明", example = "当前单位价格接近历史中位数")
    private final String reason;

    public PriceDecisionResult(String productName,
                               String normalizedName,
                               double currentUnitPrice,
                               String unit,
                               Double historicalMin,
                               Double historicalMedian,
                               Double historicalAverage,
                               int sampleSize,
                               String decision,
                               String decisionText,
                               String reason) {
        this.productName = productName;
        this.normalizedName = normalizedName;
        this.currentUnitPrice = currentUnitPrice;
        this.unit = unit;
        this.historicalMin = historicalMin;
        this.historicalMedian = historicalMedian;
        this.historicalAverage = historicalAverage;
        this.sampleSize = sampleSize;
        this.decision = decision;
        this.decisionText = decisionText;
        this.reason = reason;
    }

    public String productName() {
        return productName;
    }

    public String normalizedName() {
        return normalizedName;
    }

    public double currentUnitPrice() {
        return currentUnitPrice;
    }

    public String unit() {
        return unit;
    }

    public Double historicalMin() {
        return historicalMin;
    }

    public Double historicalMedian() {
        return historicalMedian;
    }

    public Double historicalAverage() {
        return historicalAverage;
    }

    public int sampleSize() {
        return sampleSize;
    }

    public String decision() {
        return decision;
    }

    public String decisionText() {
        return decisionText;
    }

    public String reason() {
        return reason;
    }

    public String getProductName() {
        return productName;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public double getCurrentUnitPrice() {
        return currentUnitPrice;
    }

    public String getUnit() {
        return unit;
    }

    public Double getHistoricalMin() {
        return historicalMin;
    }

    public Double getHistoricalMedian() {
        return historicalMedian;
    }

    public Double getHistoricalAverage() {
        return historicalAverage;
    }

    public int getSampleSize() {
        return sampleSize;
    }

    public String getDecision() {
        return decision;
    }

    public String getDecisionText() {
        return decisionText;
    }

    public String getReason() {
        return reason;
    }
}
