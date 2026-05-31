package com.jtxw.familyagent.infrastructure.report;

import com.jtxw.familyagent.domain.model.PurchaseRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/05/27/18:57
 * @Description: Markdown 复购品价格报告写入器测试，覆盖增强后的报告结构。
 */
class MarkdownReportWriterTest {
    @Test
    void shouldWriteEnhancedMonthlyReport() throws Exception {
        Path reportsDir = Path.of("target", "markdown-report-writer-test");
        MarkdownReportWriter writer = new MarkdownReportWriter(reportsDir.toString());

        String reportPath = writer.write("2026-05", List.of(
                record("2026-05-01", "JTXW", "宠物用品", "混合猫砂 12kg", "12kg", 12D, "kg",
                        89D, 89D, "paid_amount", 7.4167D),
                record("2026-05-02", "JTXW", "日用品", "纸巾 24包", "24包", 24D, "pack",
                        39.9D, 0D, "product_amount_adjusted", 1.6625D)
        ), 2);

        String content = Files.readString(Path.of(reportPath));

        assertThat(content)
                .contains("## 概览")
                .contains("## 样本归属汇总")
                .contains("## 金额来源")
                .contains("## Top 复购品")
                .contains("## 复核提醒")
                .contains("商品金额折算")
                .contains("存在购物金、礼品卡或组合支付折算记录");
    }

    @Test
    void shouldWriteEmptyMonthlyReportWithNoRecordsHint() throws Exception {
        Path reportsDir = Path.of("target", "markdown-empty-report-writer-test");
        MarkdownReportWriter writer = new MarkdownReportWriter(reportsDir.toString());

        String reportPath = writer.write("2026-06", List.of(), 0);

        String content = Files.readString(Path.of(reportPath));
        assertThat(content)
                .contains("- 统计记录数：0 条")
                .contains("- 统计金额合计：0.00 元")
                .contains("本月没有纳入统计口径的购买记录")
                .contains("| 暂无数据 | 0.00 | 0.00% |")
                .contains("当前没有待复核记录。");
    }

    private PurchaseRecord record(String orderTime,
                                  String owner,
                                  String category,
                                  String productName,
                                  String sku,
                                  Double quantity,
                                  String unit,
                                  Double totalAmount,
                                  Double paidAmount,
                                  String amountSource,
                                  Double unitPrice) {
        return new PurchaseRecord(
                null, 1L, orderTime, "taobao", owner, productName, productName,
                sku, category, "", quantity, unit, totalAmount, totalAmount, paidAmount,
                0D, amountSource, unitPrice, "CNY", "include", false, "unique",
                "examples/sample_orders.csv", "2026-05-27 18:56:00"
        );
    }
}
