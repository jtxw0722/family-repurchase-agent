package com.jtxw.familyagent.application.command;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 15:58:00
 * @Description: 人工复核应用命令，用于承载 review-items/{id}/apply 用例的输入参数。
 *
 * @param reviewId 复核项 ID，对应 review_items.id
 * @param action   复核动作，取值 include 或 exclude
 * @param note     人工复核备注
 */
public record ApplyReviewCommand(
        long reviewId,
        String action,
        String note
) {
}
