package com.jtxw.familyagent.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/04
 * @Description: 手动购买记录录入结果。
 */
@Schema(description = "手动购买记录录入结果")
public record RecordPurchaseResult(
        @Schema(description = "是否只预览不写入数据库")
        boolean dryRun,
        @Schema(description = "实际写入 purchase_records 的记录数")
        int savedCount,
        @Schema(description = "生成的复核项数量")
        int reviewCount,
        @Schema(description = "逐条处理结果")
        List<RecordResult> records
) {
    /**
     * @Author: jtxw
     * @Date: 2026/06/04
     * @Description: 单条手动购买记录处理结果。
     */
    public record RecordResult(
            @Schema(description = "购买记录 ID；dryRun 时为空")
            Long recordId,
            @Schema(description = "原始商品名称")
            String productName,
            @Schema(description = "归一化商品名称")
            String normalizedName,
            @Schema(description = "购买总价")
            double price,
            @Schema(description = "最终入库数量")
            double quantity,
            @Schema(description = "最终入库单位")
            String unit,
            @Schema(description = "单位价格")
            double unitPrice,
            @Schema(description = "统计决策，include 或 exclude")
            String decision,
            @Schema(description = "是否需要人工复核")
            boolean reviewRequired,
            @Schema(description = "复核原因列表")
            List<String> reviewReasons
    ) {
    }
}
