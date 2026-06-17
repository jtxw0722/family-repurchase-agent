package com.jtxw.familyagent.application.command;

/**
 * @Author: jtxw
 * @Date: 2026/06/16 08:22:00
 * @Description: 商品归一化复核应用命令，用于承载 review-items/{id}/apply 统一入口中的归一化复核参数。
 *
 * @param reviewId               复核项 ID，对应 review_items.id
 * @param action                 归一化复核动作，支持 confirm_normalization/reject_normalization/ignore_normalization
 * @param normalizedName         confirm_normalization 时人工确认的标准品类
 * @param targetUnit             confirm_normalization 时标准单位，不允许为空
 * @param includeInBaseline      confirm_normalization 时是否同步纳入价格基准，默认 false
 * @param rejectedNormalizedName reject_normalization 时被拒绝的标准品类
 * @param note                   人工复核备注
 */
public record ApplyNormalizationReviewCommand(
        long reviewId,
        String action,
        String normalizedName,
        String targetUnit,
        boolean includeInBaseline,
        String rejectedNormalizedName,
        String note
) {
}
