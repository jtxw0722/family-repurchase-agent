package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 11:45:16
 * @Description: LLM Advisor 单个候选商品请求，只包含允许传给 LLM 的隐私安全字段。
 */
public record NormalizationAdvisorRequest(
        /**
         * 原始商品名称。
         */
        String productName,
        /**
         * 商品规格或 SKU 文本，允许为空。
         */
        String sku,
        /**
         * 电商一级分类，允许为空。
         */
        String category,
        /**
         * 电商二级分类，允许为空。
         */
        String subCategory,
        /**
         * 本地轻量 RAG 证据上下文。
         */
        NormalizationRagContext context
) {
}
