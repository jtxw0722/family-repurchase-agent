package com.jtxw.familyagent.application;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 15:22:31
 * @Description: LLM 商品归一化单条输出 DTO，兼容 compact schema 和 legacy schema 字段。
 */
public record LlmNormalizationItem(
        /**
         * compact schema 的 1-based 请求序号，用于乱序输出回填，允许为空。
         */
        Integer index,
        /**
         * legacy schema 返回的原始商品名称，compact schema 通常为空。
         */
        String rawProductName,
        /**
         * legacy schema 返回的商品规格或 SKU，compact schema 通常为空。
         */
        String sku,
        /**
         * 模型建议动作，期望值为 NORMALIZE、EXCLUDE、NEW_CATEGORY、REVIEW。
         */
        String action,
        /**
         * 模型判断的商品类型，期望值为复购消耗品、非复购品、耐用品、券/定金或未知。
         */
        String productType,
        /**
         * compact schema 建议的标准品类名称。
         */
        String normalizedName,
        /**
         * legacy schema 建议的标准品类名称。
         */
        String suggestedNormalizedName,
        /**
         * EXCLUDE 或 reject 场景下被拒绝的标准品类名称。
         */
        String rejectedNormalizedName,
        /**
         * 模型建议的目标单位，允许为空。
         */
        String targetUnit,
        /**
         * 模型建议的单位族，期望值为 WEIGHT、VOLUME、COUNT、PIECE、UNKNOWN。
         */
        String unitFamily,
        /**
         * 模型建议置信度，期望范围为 0 到 1。
         */
        Double confidence,
        /**
         * 模型是否要求人工复核，缺失时按需要复核处理。
         */
        Boolean reviewRequired,
        /**
         * compact schema 返回的解释码，用于映射短展示文案。
         */
        String reasonCode,
        /**
         * compact schema 返回的短原因，用于 reasonCode 无法识别时兜底。
         */
        String shortReason,
        /**
         * legacy schema 返回的解释原因。
         */
        String reason,
        /**
         * legacy schema 返回的证据文本列表，当前不会进入最终建议结果。
         */
        List<String> evidence
) {
}
