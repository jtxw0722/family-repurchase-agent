package com.jtxw.familyagent.interfaces.rest.request;


import com.jtxw.familyagent.application.command.BatchApplyNormalizationCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * @Author: jtxw
 * @Date: 2026/06/08/14:42
 * @Description: 归一化建议批量应用请求参数，属于 interfaces.rest.request 层，对应 /normalization-suggestions/batch-apply 接口。
 */

@Schema(description = "商品归一化建议批量应用请求")
public class BatchApplyNormalizationRequest {
    /**
     * 导入批次 ID。
     */
    @Schema(description = "导入批次 ID", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    @Positive
    private long batchId;
    /**
     * 批量动作，当前仅支持 approve_normalize。
     */
    @Schema(description = "批量动作", example = "approve_normalize", allowableValues = {"approve_normalize"}, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String action;
    /**
     * 最小置信度阈值。
     */
    @Schema(description = "最小置信度阈值", example = "0.92")
    private double minConfidence = 0.92D;
    /**
     * 只应用的 suggestion 状态，默认 pending_batch_approval。
     */
    @Schema(description = "只应用的 suggestion 状态", example = "pending_batch_approval")
    private String onlyStatus = "pending_batch_approval";

    public BatchApplyNormalizationRequest() {
    }

    public long batchId() {
        return batchId;
    }

    public String action() {
        return action;
    }

    public double minConfidence() {
        return minConfidence;
    }

    public String onlyStatus() {
        return onlyStatus;
    }

    public long getBatchId() {
        return batchId;
    }

    public void setBatchId(long batchId) {
        this.batchId = batchId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public double getMinConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public String getOnlyStatus() {
        return onlyStatus;
    }

    public void setOnlyStatus(String onlyStatus) {
        this.onlyStatus = onlyStatus;
    }

    /**
     * 将 REST 请求参数转换为应用层批量应用命令。
     *
     * <p>保留原有默认值：minConfidence=0.92D、onlyStatus=pending_batch_approval。</p>
     *
     * @return 归一化建议批量应用命令
     */
    public BatchApplyNormalizationCommand toCommand() {
        return new BatchApplyNormalizationCommand(batchId, action, minConfidence, onlyStatus);
    }
}
