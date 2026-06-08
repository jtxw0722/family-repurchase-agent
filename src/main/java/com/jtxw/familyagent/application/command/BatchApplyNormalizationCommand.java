package com.jtxw.familyagent.application.command;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 15:58:00
 * @Description: 归一化建议批量应用命令，用于承载 batch-apply 用例的输入参数。
 *
 * @param batchId       导入批次 ID
 * @param action        批量动作，当前仅支持 approve_normalize
 * @param minConfidence 最小置信度阈值
 * @param onlyStatus    只应用的 suggestion 状态，默认 pending_batch_approval
 */
public record BatchApplyNormalizationCommand(
        long batchId,
        String action,
        double minConfidence,
        String onlyStatus
) {
}
