package com.jtxw.familyagent.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @Author: jtxw
 * @Date: 2026/05/17/10:24
 * @Description: 复核应用结果对象，返回复核项、订单记录和最终统计决策。
 */
@Schema(description = "人工复核应用结果")
public class ReviewApplyResult {
    /**
     * 被处理的复核项 ID
     */
    @Schema(description = "被处理的复核项 ID", example = "3")
    private final Long reviewId;
    /**
     * 复核项关联的消费记录 ID
     */
    @Schema(description = "复核项关联的消费记录 ID", example = "15")
    private final Long recordId;
    /**
     * 本次人工复核动作，取值 include 或 exclude
     */
    @Schema(description = "本次人工复核动作", example = "include", allowableValues = {"include", "exclude"})
    private final String action;
    /**
     * 应用到消费记录上的统计决策
     */
    @Schema(description = "应用到消费记录上的统计决策", example = "include", allowableValues = {"include", "exclude"})
    private final String decision;
    /**
     * 复核项处理后的状态
     */
    @Schema(description = "复核项处理后的状态", example = "resolved", allowableValues = {"pending", "resolved"})
    private final String status;
    /**
     * 面向调用方展示的处理结果说明
     */
    @Schema(description = "面向调用方展示的处理结果说明", example = "复核结果已应用")
    private final String message;

    public ReviewApplyResult(Long reviewId, Long recordId, String action, String decision, String status, String message) {
        this.reviewId = reviewId;
        this.recordId = recordId;
        this.action = action;
        this.decision = decision;
        this.status = status;
        this.message = message;
    }

    public Long reviewId() {
        return reviewId;
    }

    public Long recordId() {
        return recordId;
    }

    public String action() {
        return action;
    }

    public String decision() {
        return decision;
    }

    public String status() {
        return status;
    }

    public String message() {
        return message;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public Long getRecordId() {
        return recordId;
    }

    public String getAction() {
        return action;
    }

    public String getDecision() {
        return decision;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
