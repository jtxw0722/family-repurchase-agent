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
         * LLM compact schema 返回的解释码，仅用于后端安全降级和展示文案，不反推业务分类。
         */
        String reasonCode,
        /**
         * LLM compact schema 返回的短原因，用于识别模型自身要求复核的场景。
         */
        String shortReason,
        /**
         * 是否为 LLM 调用或 JSON 解析失败的兜底结果。
         */
        boolean failed
) {
    /**
     * 兼容旧构造签名，未提供 reasonCode / shortReason 时按空值处理。
     *
     * @param rawProductName          原始商品名称
     * @param sku                     商品规格或 SKU 文本
     * @param action                  建议动作
     * @param suggestedNormalizedName 建议标准品类
     * @param rejectedNormalizedName  被拒绝的标准品类
     * @param productType             商品类型
     * @param targetUnit              建议目标单位
     * @param unitFamily              建议单位族
     * @param confidence              置信度
     * @param reviewRequired          是否需要人工复核
     * @param reason                  建议原因
     * @param evidence                证据文本
     * @param failed                  是否为失败兜底结果
     */
    public NormalizationAdvisorResult(String rawProductName,
                                      String sku,
                                      String action,
                                      String suggestedNormalizedName,
                                      String rejectedNormalizedName,
                                      String productType,
                                      String targetUnit,
                                      String unitFamily,
                                      double confidence,
                                      boolean reviewRequired,
                                      String reason,
                                      List<String> evidence,
                                      boolean failed) {
        this(rawProductName, sku, action, suggestedNormalizedName, rejectedNormalizedName, productType, targetUnit,
                unitFamily, confidence, reviewRequired, reason, evidence, null, null, failed);
    }
}
