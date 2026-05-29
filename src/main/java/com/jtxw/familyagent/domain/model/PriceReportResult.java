package com.jtxw.familyagent.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/00:44
 * @Description: 价格报告生成结果对象，返回统计汇总和报告路径。
 */
@Schema(description = "价格报告生成结果")
public class PriceReportResult {
    /**
     * 报告月份，格式为 yyyy-MM
     */
    @Schema(description = "报告月份，格式为 yyyy-MM", example = "2026-05")
    private final String month;
    /**
     * 纳入本次价格报告的价格样本记录数
     */
    @Schema(description = "纳入本次价格报告的价格样本记录数", example = "18")
    private final int recordCount;
    /**
     * 本次价格报告纳入统计的金额合计
     */
    @Schema(description = "本次价格报告纳入统计的金额合计", example = "1280.50")
    private final double totalAmount;
    /**
     * 当前仍待人工复核的记录数
     */
    @Schema(description = "当前仍待人工复核的记录数", example = "2")
    private final int pendingReviewCount;
    /**
     * 生成的 Markdown 报告文件路径
     */
    @Schema(description = "生成的 Markdown 报告文件路径", example = "reports/2026-05.md")
    private final String reportPath;

    public PriceReportResult(String month, int recordCount, double totalAmount, int pendingReviewCount, String reportPath) {
        this.month = month;
        this.recordCount = recordCount;
        this.totalAmount = totalAmount;
        this.pendingReviewCount = pendingReviewCount;
        this.reportPath = reportPath;
    }

    public String month() {
        return month;
    }

    public int recordCount() {
        return recordCount;
    }

    public double totalAmount() {
        return totalAmount;
    }

    public int pendingReviewCount() {
        return pendingReviewCount;
    }

    public String reportPath() {
        return reportPath;
    }

    public String getMonth() {
        return month;
    }

    public int getRecordCount() {
        return recordCount;
    }

    public double getTotalAmount() {
        return totalAmount;
    }

    public int getPendingReviewCount() {
        return pendingReviewCount;
    }

    public String getReportPath() {
        return reportPath;
    }
}
