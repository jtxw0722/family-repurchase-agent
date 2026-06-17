package com.jtxw.familyagent.interfaces.rest.request;


import com.jtxw.familyagent.application.command.ApplyReviewCommand;
import com.jtxw.familyagent.application.command.ApplyNormalizationReviewCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * @Author: jtxw
 * @Date: 2026/06/16 08:22:00
 * @Description: 人工复核统一应用请求参数，属于 interfaces.rest.request 层，对应 /review-items/{id}/apply 接口。
 */

@Schema(description = "人工复核应用请求")
public class ReviewApplyRequest {
    /**
     * 复核动作，普通统计复核支持 include 或 exclude，商品归一化复核支持 confirm_normalization、reject_normalization 或 ignore_normalization。
     */
    @Schema(description = "复核动作，include/exclude 用于普通统计复核，confirm_normalization/reject_normalization/ignore_normalization 用于商品归一化复核",
            example = "include", allowableValues = {"include", "exclude", "confirm_normalization",
            "reject_normalization", "ignore_normalization"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String action;
    /**
     * confirm_normalization 时人工确认的归一化商品名称，普通统计复核和 ignore_normalization 动作允许为空。
     */
    @Schema(description = "confirm_normalization 时人工确认的归一化商品名称", example = "沐浴露")
    private String normalizedName;
    /**
     * confirm_normalization 时人工确认的标准单位，普通统计复核和 ignore_normalization 动作允许为空。
     */
    @Schema(description = "confirm_normalization 时人工确认的标准单位", example = "L")
    private String targetUnit;
    /**
     * confirm_normalization 时是否同步纳入价格基准，默认 false。
     */
    @Schema(description = "confirm_normalization 时是否同步将购买记录纳入价格基准", example = "true")
    private boolean includeInBaseline;
    /**
     * reject_normalization 时被拒绝的归一化商品名称，允许为空；为空时应用层使用购买记录当前归一化名称。
     */
    @Schema(description = "reject_normalization 时被拒绝的归一化商品名称；为空时使用购买记录当前归一化名称", example = "猫砂")
    private String rejectedNormalizedName;
    /**
     * 复核备注，允许为空。
     */
    @Schema(description = "人工复核备注", example = "确认是正常家庭消耗品购买记录")
    private String note;

    public ReviewApplyRequest() {
    }

    public String action() {
        return action;
    }

    public String note() {
        return note;
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

    /**
     * 判断当前请求是否为普通统计决策复核。
     *
     * @return action 为 include 或 exclude 时返回 true
     */
    public boolean isStatisticalDecisionAction() {
        if (action == null) {
            return false;
        }
        String normalizedAction = action.trim();
        return "include".equalsIgnoreCase(normalizedAction) || "exclude".equalsIgnoreCase(normalizedAction);
    }

    /**
     * 将 REST 请求参数转换为应用层复核命令。
     *
     * @param reviewId 复核项 ID，由路径变量传入
     * @return 人工复核应用命令
     */
    public ApplyReviewCommand toCommand(long reviewId) {
        return new ApplyReviewCommand(reviewId, action, note);
    }

    /**
     * 将 REST 请求参数转换为商品归一化复核命令。
     *
     * @param reviewId 复核项 ID，由路径变量传入
     * @return 商品归一化复核应用命令
     */
    public ApplyNormalizationReviewCommand toNormalizationCommand(long reviewId) {
        return new ApplyNormalizationReviewCommand(reviewId, action, normalizedName, targetUnit,
                includeInBaseline, rejectedNormalizedName, note);
    }
}
