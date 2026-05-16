package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/01:32
 * @Description: 待复核事项实体，记录异常消费记录的复核原因和处理状态。
 */
public class ReviewItem {
    private final Long id;
    private final Long recordId;
    private final String reasonCode;
    private final String reasonMessage;
    private final String status;
    private final String reviewDecision;
    private final String reviewNote;
    private final String createdAt;
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
