package com.jtxw.familyagent.application;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/12 12:01:23
 * @Description: 自动排除归一化建议查询结果，承载只读接口返回的批次统计、类型分布和明细数据
 */
public record AutoExcludedNormalizationSuggestionResult(
        /**
         * 导入批次 ID，来自请求参数 batchId，必须大于 0。
         */
        long batchId,
        /**
         * 自动排除记录的最低置信度阈值，取值范围为 0.0 到 1.0，默认值为 0.9。
         */
        double minConfidence,
        /**
         * 满足查询条件的自动排除建议总数，等于 items 的元素数量。
         */
        int total,
        /**
         * 按商品类型聚合的记录数量，只包含当前批次实际命中的自动排除类型。
         */
        List<TypeCount> typeCounts,
        /**
         * 自动排除建议明细列表，不包含 evidenceJson，避免只读接口响应过大。
         */
        List<Item> items
) {
    /**
     * @Author: jtxw
     * @Date: 2026/06/12 12:01:23
     * @Description: 自动排除归一化建议的商品类型数量统计项，用于展示各类 EXCLUDE 记录分布
     */
    public record TypeCount(
            /**
             * 商品类型，当前只会返回 DURABLE、COUPON_OR_DEPOSIT、NON_REPURCHASE。
             */
            String productType,
            /**
             * 当前商品类型在本次查询结果中的记录数量，必须大于 0。
             */
            long count
    ) {
    }

    /**
     * @Author: jtxw
     * @Date: 2026/06/12 12:01:23
     * @Description: 自动排除归一化建议明细项，用于只读展示单条高置信 EXCLUDE 建议
     */
    public record Item(
            /**
             * 归一化建议记录 ID，对应 normalization_suggestions.id。
             */
            Long suggestionId,
            /**
             * 原始商品名称，来自导入订单中的商品标题。
             */
            String rawProductName,
            /**
             * 商品规格或 SKU 文本，允许为空。
             */
            String sku,
            /**
             * 商品标题和 SKU 清洗后的稳定匹配键。
             */
            String aliasKey,
            /**
             * LLM 建议动作，本接口固定返回 EXCLUDE。
             */
            String action,
            /**
             * 商品类型，本接口限定为耐用品、非复购品或券定金权益类。
             */
            String productType,
            /**
             * LLM 建议置信度，取值范围为 0.0 到 1.0，必须大于等于请求阈值。
             */
            double confidence,
            /**
             * 是否需要人工复核，本接口固定返回 false。
             */
            boolean reviewRequired,
            /**
             * 建议状态，本接口固定返回 auto_excluded。
             */
            String status,
            /**
             * 自动排除原因，说明后处理或 LLM 判断依据。
             */
            String reason,
            /**
             * 建议创建时间，按 REST 响应展示为 yyyy-MM-dd'T'HH:mm:ss 文本。
             */
            String createdAt
    ) {
    }
}
