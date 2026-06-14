package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:27:12
 * @Description: 商品归一化建议批量应用结果。
 */
public record NormalizationBatchApplyResult(
        /**
         * 导入批次 ID。
         */
        long batchId,
        /**
         * 符合筛选条件的建议数量。
         */
        int matchedCount,
        /**
         * 实际确认的建议数量；alias 主链路废弃后不再代表别名表写入数量。
         */
        int appliedCount,
        /**
         * 批量处理后的说明信息。
         */
        String message
) {
}
