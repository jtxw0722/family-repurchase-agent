package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/07 15:16:32
 * @Description: 商品归一化异步分析任务查询结果，承载任务状态、筛选条件、进度计数和错误信息。
 *
 * @param taskId                    任务 ID，来自 normalization_analysis_tasks.id，不允许为空
 * @param batchId                   导入批次 ID，对应 raw_import_batches.id
 * @param status                    任务状态，取值为 pending、running、completed、failed
 * @param limit                     本次任务最多分析的候选商品数量，小于等于 0 的请求会落库为默认值 100
 * @param forceReanalyze            是否强制重新分析已存在建议的商品
 * @param includeKeywords           包含关键词列表，命中商品名或 SKU 才进入候选集，允许为空列表
 * @param excludeKeywords           排除关键词列表，命中商品名或 SKU 时排除，允许为空列表
 * @param onlyFailed                是否只重试已有 failed suggestion 对应的候选商品
 * @param candidateCount            候选商品总数，单位为条
 * @param analyzedCount             已完成分析的候选商品数量，单位为条
 * @param autoExcludedCount         自动排除建议数量，单位为条
 * @param pendingBatchApprovalCount 等待批量确认建议数量，单位为条
 * @param pendingReviewCount        等待人工复核建议数量，单位为条
 * @param failedCount               分析失败建议数量，单位为条
 * @param currentBatchIndex         当前已处理到的 LLM 小批次序号，从 0 开始表示尚未进入首批
 * @param totalBatchCount           LLM 小批次总数，单位为批
 * @param message                   任务完成说明，完成前允许为空
 * @param errorMessage              任务级失败错误信息，非 failed 状态允许为空，必须避免包含 API Key
 * @param createdAt                 任务创建时间，格式 yyyy-MM-dd HH:mm:ss
 * @param startedAt                 任务开始执行时间，未运行前允许为空
 * @param finishedAt                任务结束时间，未结束前允许为空
 * @param updatedAt                 任务最近更新时间，格式 yyyy-MM-dd HH:mm:ss
 */
public record NormalizationAnalysisTask(Long taskId,
                                        long batchId,
                                        String status,
                                        int limit,
                                        boolean forceReanalyze,
                                        List<String> includeKeywords,
                                        List<String> excludeKeywords,
                                        boolean onlyFailed,
                                        int candidateCount,
                                        int analyzedCount,
                                        int autoExcludedCount,
                                        int pendingBatchApprovalCount,
                                        int pendingReviewCount,
                                        int failedCount,
                                        int currentBatchIndex,
                                        int totalBatchCount,
                                        String message,
                                        String errorMessage,
                                        String createdAt,
                                        String startedAt,
                                        String finishedAt,
                                        String updatedAt) {
}
