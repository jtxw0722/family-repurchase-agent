package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/06/07 15:17:00
 * @Description: 商品归一化异步分析任务创建结果，供 analyze-normalization 接口快速返回任务入口。
 *
 * @param taskId  新建任务 ID，来自 normalization_analysis_tasks.id，不允许为空
 * @param batchId 导入批次 ID，对应 raw_import_batches.id
 * @param status  初始任务状态，默认返回 pending
 * @param message 创建结果提示文案
 */
public record NormalizationAnalysisTaskCreateResult(Long taskId,
                                                    long batchId,
                                                    String status,
                                                    String message) {
}
