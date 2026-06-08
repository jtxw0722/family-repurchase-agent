package com.jtxw.familyagent.interfaces.rest.request;


import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @Author: jtxw
 * @Date: 2026/06/08/14:42
 * @Description:
 */

@Schema(description = "商品归一化复核应用请求")
public class ApplyNormalizationReviewRequest {
    /**
     * 归一化复核动作，取值 confirm、reject 或 ignore
     */
    @Schema(description = "归一化复核动作，confirm 确认、reject 拒绝、ignore 忽略",
            example = "confirm", allowableValues = {"confirm", "reject", "ignore"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String action;
    /**
     * 人工确认的标准品类
     */
    @Schema(description = "confirm 时人工确认的标准品类", example = "沐浴露")
    private String normalizedName;
    /**
     * 标准单位
     */
    @Schema(description = "confirm 时标准单位；为空时使用购买记录当前单位", example = "L")
    private String targetUnit;
    /**
     * 是否同步纳入价格基准
     */
    @Schema(description = "confirm 时是否同步将购买记录 decision 改为 include", example = "true")
    private boolean includeInBaseline;
    /**
     * 被拒绝的标准品类
     */
    @Schema(description = "reject 时被拒绝的标准品类；为空时使用购买记录当前 normalized_name", example = "猫砂")
    private String rejectedNormalizedName;
    /**
     * 复核备注
     */
    @Schema(description = "人工复核备注")
    private String note;

    public ApplyNormalizationReviewRequest() {
    }

    public String action() {
        return action;
    }

    public String normalizedName() {
        return normalizedName;
    }

    public String targetUnit() {
        return targetUnit;
    }

    public boolean includeInBaseline() {
        return includeInBaseline;
    }

    public String rejectedNormalizedName() {
        return rejectedNormalizedName;
    }

    public String note() {
        return note;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public void setNormalizedName(String normalizedName) {
        this.normalizedName = normalizedName;
    }

    public String getTargetUnit() {
        return targetUnit;
    }

    public void setTargetUnit(String targetUnit) {
        this.targetUnit = targetUnit;
    }

    public boolean isIncludeInBaseline() {
        return includeInBaseline;
    }

    public void setIncludeInBaseline(boolean includeInBaseline) {
        this.includeInBaseline = includeInBaseline;
    }

    public String getRejectedNormalizedName() {
        return rejectedNormalizedName;
    }

    public void setRejectedNormalizedName(String rejectedNormalizedName) {
        this.rejectedNormalizedName = rejectedNormalizedName;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
