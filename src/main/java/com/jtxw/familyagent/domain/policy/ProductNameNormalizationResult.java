package com.jtxw.familyagent.domain.policy;

/**
 * @Author: jtxw
 * @Date: 2026/06/04/19:39
 * @Description: 商品名称归一化结果，携带标准品类、目标单位、置信度和复核标记。
 */
public record ProductNameNormalizationResult(
        /**
         * 归一化后的标准品类名称，例如“安睡裤”“卫生巾”“猫砂”。
         */
        String normalizedName,
        /**
         * 该品类用于价格比较的目标单位，例如“条”“片”“kg”。
         */
        String targetUnit,
        /**
         * 当前规则命中的置信度，主要用于人工复核时解释来源。
         */
        double confidence,
        /**
         * 命中的规则标识；未命中新规则时会保留旧归一化规则的 ruleId 或 fallback 标识。
         */
        String matchedRule,
        /**
         * 是否需要人工复核。低置信样本不应直接进入正式 price baseline。
         */
        boolean needReview
) {
}
