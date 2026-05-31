package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.PriceReportResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import com.jtxw.familyagent.infrastructure.report.MarkdownReportWriter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/05/12/14:18
 * @Description: 报告应用服务，汇总复购品价格样本记录并生成 Markdown 报告。
 */
@Service
public class ReportApplicationService {
    /**
     * 报告月份只允许 yyyy-MM，避免非法分隔符被当作报告文件子目录。
     */
    private static final Pattern REPORT_MONTH_PATTERN = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final DatabaseInitializer databaseInitializer;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final MarkdownReportWriter markdownReportWriter;

    public ReportApplicationService(DatabaseInitializer databaseInitializer,
                                    PurchaseRecordRepository purchaseRecordRepository,
                                    ReviewItemRepository reviewItemRepository,
                                    MarkdownReportWriter markdownReportWriter) {
        this.databaseInitializer = databaseInitializer;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.markdownReportWriter = markdownReportWriter;
    }

    /**
     * 生成指定月份的本地复购品价格报告。
     *
     * <p>报告只统计正式统计口径内的购买记录，并附带当前待复核记录数量。</p>
     *
     * @param month 报告月份，格式为 yyyy-MM
     * @return 价格报告生成结果
     */
    public PriceReportResult generatePriceReport(String month) {
        String normalizedMonth = validateMonth(month);
        databaseInitializer.initialize();
        List<PurchaseRecord> records = purchaseRecordRepository.listIncludedByMonth(normalizedMonth);
        int pendingReviewCount = reviewItemRepository.countPending();
        String reportPath = markdownReportWriter.write(normalizedMonth, records, pendingReviewCount);
        double total = records.stream().mapToDouble(PurchaseRecord::totalAmount).sum();
        String message = records.isEmpty()
                ? "指定月份没有可统计的购买记录，已生成空报告。"
                : "报告生成成功。";
        return new PriceReportResult(normalizedMonth, records.size(), total, pendingReviewCount, reportPath, message);
    }

    /**
     * 校验并返回可用于查询和报告文件名的月份字符串。
     *
     * @param month 用户传入的报告月份
     * @return 去除首尾空白后的 yyyy-MM 月份
     * @throws IllegalArgumentException 月份为空或格式不是 yyyy-MM 时抛出
     */
    private String validateMonth(String month) {
        if (month == null || month.isBlank()) {
            throw new IllegalArgumentException("报告月份不能为空，请使用 yyyy-MM，例如 2026-05。");
        }
        String normalizedMonth = month.trim();
        if (!REPORT_MONTH_PATTERN.matcher(normalizedMonth).matches()) {
            throw new IllegalArgumentException("报告月份格式错误，请使用 yyyy-MM，例如 2026-05。");
        }
        return normalizedMonth;
    }
}
