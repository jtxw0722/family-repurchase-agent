package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:27:12
 * @Description: 商品归一化 LLM 建议实体，对应 normalization_suggestions 审计表。
 */
public record NormalizationSuggestion(
        /**
         * 建议记录主键 ID。
         */
        Long id,
        /**
         * 关联导入批次 ID，允许为空以兼容未来非批次来源。
         */
        Long batchId,
        /**
         * 原始商品名称，不包含订单金额、店铺、owner 等隐私字段。
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
         * LLM 建议动作，允许值为 NORMALIZE、EXCLUDE、NEW_CATEGORY、REVIEW。
         */
        String action,
        /**
         * 建议归一化后的标准品类，NORMALIZE 时必填。
         */
        String suggestedNormalizedName,
        /**
         * 建议排除或拒绝的标准品类，允许为空。
         */
        String rejectedNormalizedName,
        /**
         * 商品类型，允许值为 REPURCHASE_CONSUMABLE、NON_REPURCHASE、DURABLE、COUPON_OR_DEPOSIT、UNKNOWN。
         */
        String productType,
        /**
         * 建议标准单位，例如 kg、L、片、抽，允许为空。
         */
        String targetUnit,
        /**
         * 单位族，允许值为 WEIGHT、VOLUME、COUNT、PIECE、UNKNOWN。
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
         * 建议原因，说明业务判断依据。
         */
        String reason,
        /**
         * LLM 使用的证据 JSON，通常为字符串数组。
         */
        String evidenceJson,
        /**
         * LLM 服务提供方，例如 openai。
         */
        String llmProvider,
        /**
         * LLM 模型名称。
         */
        String llmModel,
        /**
         * 提示词版本，用于审计和回放。
         */
        String promptVersion,
        /**
         * 建议状态，允许值为 auto_excluded、pending_batch_approval、pending_review、approved、rejected、failed。
         */
        String status,
        /**
         * 建议创建时间，格式 yyyy-MM-dd HH:mm:ss。
         */
        String createdAt,
        /**
         * 人工或批量处理时间，未处理时为空。
         */
        String reviewedAt
) {
}
