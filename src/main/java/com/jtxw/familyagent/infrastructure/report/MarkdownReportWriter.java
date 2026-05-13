package com.jtxw.familyagent.infrastructure.report;

import com.jtxw.familyagent.domain.model.PurchaseRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/17:12
 * @Description: Markdown 报告写入器，负责生成本地月度消费分析报告。
 */
@Component
public class MarkdownReportWriter {
    private final Path reportsDir;

    public MarkdownReportWriter(@Value("${family-agent.reports-dir:./reports}") String reportsDir) {
        this.reportsDir = Path.of(reportsDir);
    }

    public String write(String month, List<PurchaseRecord> records, int pendingReviewCount) {
        try {
            Files.createDirectories(reportsDir);
            Path target = reportsDir.resolve(month + ".md");
            double total = records.stream().mapToDouble(PurchaseRecord::totalAmount).sum();
            Map<String, Double> categorySum = records.stream()
                    .collect(Collectors.groupingBy(r -> blankToUnknown(r.category()), Collectors.summingDouble(PurchaseRecord::totalAmount)));

            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(month).append(" 家庭消费报告\n\n");
            sb.append("## 概览\n\n");
            sb.append("- 统计记录数：").append(records.size()).append(" 条\n");
            sb.append("- 总支出：").append(String.format("%.2f", total)).append(" 元\n");
            sb.append("- 待复核记录：").append(pendingReviewCount).append(" 条\n\n");
            sb.append("## 分类汇总\n\n");
            sb.append("| 分类 | 金额 |\n|---|---:|\n");
            categorySum.forEach((category, amount) -> sb.append("| ").append(category).append(" | ")
                    .append(String.format("%.2f", amount)).append(" |\n"));
            sb.append("\n## 明细\n\n");
            sb.append("| 时间 | 商品 | 标准化名称 | 金额 | 单价 |\n|---|---|---|---:|---:|\n");
            for (PurchaseRecord record : records) {
                sb.append("| ").append(record.orderTime()).append(" | ")
                        .append(record.productName()).append(" | ")
                        .append(record.normalizedName()).append(" | ")
                        .append(String.format("%.2f", record.totalAmount())).append(" | ")
                        .append(String.format("%.4f", record.unitPrice())).append(" |")
                        .append("\n");
            }
            Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
            return target.toString();
        } catch (IOException e) {
            throw new IllegalStateException("生成报告失败", e);
        }
    }

    private String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "未分类" : value;
    }
}
