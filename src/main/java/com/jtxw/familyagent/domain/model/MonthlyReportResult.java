package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/00:44
 * @Description: 月度报告生成结果对象，返回统计汇总和报告路径。
 */
public record MonthlyReportResult(
        String month,
        int recordCount,
        double totalAmount,
        int pendingReviewCount,
        String reportPath
) {}
