package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:27:12
 * @Description: 批次商品归一化 LLM 分析结果。
 */
public record NormalizationAnalyzeResult(
        /**
         * 导入批次 ID。
         */
        long batchId,
        /**
         * 符合 legacy_fallback 条件且去重后的候选数量。
         */
        int candidateCount,
        /**
         * 本次实际完成 LLM 分析并保存建议的数量。
         */
        int analyzedCount,
        /**
         * 高置信静默排除建议数量。
         */
        int autoExcludedCount,
        /**
         * 高置信归一化且等待批量确认的建议数量。
         */
        int pendingBatchApprovalCount,
        /**
         * 需要人工复核的建议数量。
         */
        int pendingReviewCount,
        /**
         * LLM 调用或 JSON 解析失败的建议数量。
         */
        int failedCount,
        /**
         * 面向工具调用方的处理说明。
         */
        String message
) {
}
