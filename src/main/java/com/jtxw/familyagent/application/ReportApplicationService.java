package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.MonthlyReportResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import com.jtxw.familyagent.infrastructure.report.MarkdownReportWriter;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/12/14:18
 * @Description: 报告应用服务，汇总月度消费记录并生成 Markdown 报告。
 */
@Service
public class ReportApplicationService {
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

    public MonthlyReportResult generateMonthlyReport(String month) {
        databaseInitializer.initialize();
        List<PurchaseRecord> records = purchaseRecordRepository.listIncludedByMonth(month);
        int pendingReviewCount = reviewItemRepository.countPending();
        String reportPath = markdownReportWriter.write(month, records, pendingReviewCount);
        double total = records.stream().mapToDouble(PurchaseRecord::totalAmount).sum();
        return new MonthlyReportResult(month, records.size(), total, pendingReviewCount, reportPath);
    }
}
