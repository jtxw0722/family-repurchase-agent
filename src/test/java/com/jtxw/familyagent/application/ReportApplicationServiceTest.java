package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.PriceReportResult;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import com.jtxw.familyagent.infrastructure.report.MarkdownReportWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @Author: jtxw
 * @Date: 2026/05/31/13:05
 * @Description: 报告应用服务测试。
 */
class ReportApplicationServiceTest {
    /**
     * 验证无统计记录时仍返回清晰提示，并生成空报告结果。
     */
    @Test
    void shouldReturnClearMessageWhenMonthHasNoIncludedRecords() {
        DatabaseInitializer databaseInitializer = mock(DatabaseInitializer.class);
        PurchaseRecordRepository purchaseRecordRepository = mock(PurchaseRecordRepository.class);
        ReviewItemRepository reviewItemRepository = mock(ReviewItemRepository.class);
        MarkdownReportWriter markdownReportWriter = mock(MarkdownReportWriter.class);
        ReportApplicationService service = new ReportApplicationService(
                databaseInitializer,
                purchaseRecordRepository,
                reviewItemRepository,
                markdownReportWriter
        );

        when(purchaseRecordRepository.listIncludedByMonth("2026-06")).thenReturn(List.of());
        when(reviewItemRepository.countPending()).thenReturn(0);
        when(markdownReportWriter.write("2026-06", List.of(), 0)).thenReturn("reports/2026-06.md");

        PriceReportResult result = service.generatePriceReport("2026-06");

        assertThat(result.month()).isEqualTo("2026-06");
        assertThat(result.recordCount()).isZero();
        assertThat(result.totalAmount()).isZero();
        assertThat(result.pendingReviewCount()).isZero();
        assertThat(result.reportPath()).isEqualTo("reports/2026-06.md");
        assertThat(result.message()).isEqualTo("指定月份没有可统计的购买记录，已生成空报告。");
        verify(databaseInitializer).initialize();
    }

    /**
     * 验证 2026/05 这类非法月份会在写报告前被拒绝，避免生成嵌套路径。
     */
    @Test
    void shouldRejectMonthWithSlashBeforeWritingReport() {
        DatabaseInitializer databaseInitializer = mock(DatabaseInitializer.class);
        PurchaseRecordRepository purchaseRecordRepository = mock(PurchaseRecordRepository.class);
        ReviewItemRepository reviewItemRepository = mock(ReviewItemRepository.class);
        MarkdownReportWriter markdownReportWriter = mock(MarkdownReportWriter.class);
        ReportApplicationService service = new ReportApplicationService(
                databaseInitializer,
                purchaseRecordRepository,
                reviewItemRepository,
                markdownReportWriter
        );

        assertThatThrownBy(() -> service.generatePriceReport("2026/05"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("报告月份格式错误，请使用 yyyy-MM，例如 2026-05。");

        verifyNoInteractions(databaseInitializer, purchaseRecordRepository, reviewItemRepository, markdownReportWriter);
    }
}
