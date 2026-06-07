package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/06/07 15:30:41
 * @Description: 商品归一化批次分析进度快照，用于后台任务执行过程中同步更新任务表。
 *
 * @param batchId                   导入批次 ID，对应 raw_import_batches.id
 * @param candidateCount            候选商品总数，单位为条
 * @param analyzedCount             已完成分析的候选商品数量，单位为条
 * @param autoExcludedCount         自动排除建议数量，单位为条
 * @param pendingBatchApprovalCount 等待批量确认建议数量，单位为条
 * @param pendingReviewCount        等待人工复核建议数量，单位为条
 * @param failedCount               分析失败建议数量，单位为条
 * @param currentBatchIndex         当前已处理到的 LLM 小批次序号，从 0 开始表示尚未进入首批
 * @param totalBatchCount           LLM 小批次总数，单位为批
 */
public record NormalizationAnalyzeProgress(long batchId,
                                           int candidateCount,
                                           int analyzedCount,
                                           int autoExcludedCount,
                                           int pendingBatchApprovalCount,
                                           int pendingReviewCount,
                                           int failedCount,
                                           int currentBatchIndex,
                                           int totalBatchCount) {
}
