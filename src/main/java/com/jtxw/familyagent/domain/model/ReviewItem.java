package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/01:32
 * @Description: 待复核事项实体，记录异常购买记录的复核原因和处理状态。
 */
public class ReviewItem {
    /**
     * 复核项主键 ID
     */
    private final Long id;
    /**
     * 关联的购买记录 ID
     */
    private final Long recordId;
    /**
     * 复核原因编码
     */
    private final String reasonCode;
    /**
     * 复核原因说明
     */
    private final String reasonMessage;
    /**
     * 复核状态，pending 表示待处理，resolved 表示已处理
     */
    private final String status;
    /**
     * 人工复核动作，取值 include 或 exclude
     */
    private final String reviewDecision;
    /**
     * 人工复核备注
     */
    private final String reviewNote;
    /**
     * 复核项创建时间
     */
    private final String createdAt;
    /**
     * 复核项处理完成时间
     */
    private final String resolvedAt;

    public ReviewItem(Long id,
                      Long recordId,
                      String reasonCode,
                      String reasonMessage,
                      String status,
                      String reviewDecision,
                      String reviewNote,
                      String createdAt,
                      String resolvedAt) {
        this.id = id;
        this.recordId = recordId;
        this.reasonCode = reasonCode;
        this.reasonMessage = reasonMessage;
        this.status = status;
        this.reviewDecision = reviewDecision;
        this.reviewNote = reviewNote;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    public Long id() {
        return id;
    }

    public Long recordId() {
        return recordId;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String reasonMessage() {
        return reasonMessage;
    }

    public String status() {
        return status;
    }

    public String reviewDecision() {
        return reviewDecision;
    }

    public String reviewNote() {
        return reviewNote;
    }

    public String createdAt() {
        return createdAt;
    }

    public String resolvedAt() {
        return resolvedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getRecordId() {
        return recordId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonMessage() {
        return reasonMessage;
    }

    public String getStatus() {
        return status;
    }

    public String getReviewDecision() {
        return reviewDecision;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getResolvedAt() {
        return resolvedAt;
    }
}
