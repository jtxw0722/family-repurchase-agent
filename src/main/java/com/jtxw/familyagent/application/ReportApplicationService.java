package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.PriceReportResult;
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
 * @Description: 报告应用服务，汇总复购品价格样本记录并生成 Markdown 报告。
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

    /**
     * 生成指定月份的本地复购品价格报告。
     *
     * <p>报告只统计正式统计口径内的购买记录，并附带当前待复核记录数量。</p>
     *
     * @param month 报告月份，格式为 yyyy-MM
     * @return 价格报告生成结果
     */
    public PriceReportResult generatePriceReport(String month) {
        databaseInitializer.initialize();
        List<PurchaseRecord> records = purchaseRecordRepository.listIncludedByMonth(month);
        int pendingReviewCount = reviewItemRepository.countPending();
        String reportPath = markdownReportWriter.write(month, records, pendingReviewCount);
        double total = records.stream().mapToDouble(PurchaseRecord::totalAmount).sum();
        return new PriceReportResult(month, records.size(), total, pendingReviewCount, reportPath);
    }
}
