package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:27:12
 * @Description: LLM Advisor 单条商品归一化结构化输出结果。
 */
public record NormalizationAdvisorResult(
        /**
         * 原始商品名称，仅用于商品归一化判断。
         */
        String rawProductName,
        /**
         * 商品规格或 SKU 文本，允许为空。
         */
        String sku,
        /**
         * 建议动作，允许值为 NORMALIZE、EXCLUDE、NEW_CATEGORY、REVIEW。
         */
        String action,
        /**
         * NORMALIZE 动作建议的标准品类。
         */
        String suggestedNormalizedName,
        /**
         * EXCLUDE 或 reject 场景下被拒绝的标准品类。
         */
        String rejectedNormalizedName,
        /**
         * 商品类型，用于区分复购消耗品、耐用品、非复购品和券/定金。
         */
        String productType,
        /**
         * 建议目标单位，允许为空。
         */
        String targetUnit,
        /**
         * 建议单位族，允许值为 WEIGHT、VOLUME、COUNT、PIECE、UNKNOWN。
         */
        String unitFamily,
        /**
         * LLM 建议置信度，取值范围 0.0 到 1.0。
         */
        double confidence,
        /**
         * 是否仍需要人工复核。
         */
        boolean reviewRequired,
        /**
         * 建议原因，说明为什么这样处理。
         */
        String reason,
        /**
         * 支撑建议的证据文本列表。
         */
        List<String> evidence,
        /**
         * 是否为 LLM 调用或 JSON 解析失败的兜底结果。
         */
        boolean failed
) {
}
