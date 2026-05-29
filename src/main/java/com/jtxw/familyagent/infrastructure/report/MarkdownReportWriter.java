package com.jtxw.familyagent.infrastructure.report;

import com.jtxw.familyagent.domain.model.PurchaseRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/17:12
 * @Description: Markdown 报告写入器，负责生成本地月度价格报告。
 */
@Component
public class MarkdownReportWriter {
    private final Path reportsDir;

    public MarkdownReportWriter(@Value("${family-agent.reports-dir:./reports}") String reportsDir) {
        this.reportsDir = Path.of(reportsDir);
    }

    /**
     * 将月度复购品价格统计写入本地 Markdown 报告文件。
     *
     * @param month              报告月份，格式为 yyyy-MM
     * @param records            已按正式统计口径筛选出的购买记录
     * @param pendingReviewCount 当前待复核记录数
     * @return 报告文件路径
     */
    public String write(String month, List<PurchaseRecord> records, int pendingReviewCount) {
        try {
            Files.createDirectories(reportsDir);
            Path target = reportsDir.resolve(month + ".md");
            double total = records.stream().mapToDouble(PurchaseRecord::totalAmount).sum();
            double average = records.isEmpty() ? 0D : total / records.size();
            Map<String, Double> categorySum = sumBy(records, r -> blankToUnknown(r.category()));
            Map<String, Double> ownerSum = sumBy(records, r -> blankToUnknown(r.owner()));
            Map<String, Double> amountSourceSum = sumBy(records, r -> blankToUnknown(r.amountSource()));
            long adjustedCount = records.stream()
                    .filter(record -> "product_amount_adjusted".equals(record.amountSource()))
                    .count();

            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(month).append(" 家庭复购品价格报告\n\n");
            sb.append("## 概览\n\n");
            sb.append("- 统计记录数：").append(records.size()).append(" 条\n");
            sb.append("- 统计金额合计：").append(String.format("%.2f", total)).append(" 元\n");
            sb.append("- 平均单笔：").append(String.format("%.2f", average)).append(" 元\n");
            sb.append("- 待复核记录：").append(pendingReviewCount).append(" 条\n");
            if (adjustedCount > 0) {
                sb.append("- 金额折算记录：").append(adjustedCount).append(" 条\n");
            }
            sb.append("\n");

            sb.append("## 分类汇总\n\n");
            appendAmountTable(sb, "分类", categorySum, total);

            sb.append("## 样本归属汇总\n\n");
            appendAmountTable(sb, "成员", ownerSum, total);

            sb.append("## 金额来源\n\n");
            appendAmountSourceTable(sb, amountSourceSum, total);
            if (adjustedCount > 0) {
                sb.append("\n> 存在购物金、礼品卡或组合支付折算记录，建议结合待复核列表确认金额口径。\n");
            }

            sb.append("\n## Top 复购品\n\n");
            appendTopRecords(sb, records);

            sb.append("## 复核提醒\n\n");
            if (pendingReviewCount == 0) {
                sb.append("当前没有待复核记录。\n\n");
            } else {
                sb.append("当前还有 ").append(pendingReviewCount)
                        .append(" 条待复核记录，建议优先处理后再查看最终价格统计。\n\n");
            }

            sb.append("## 明细\n\n");
            sb.append("| 时间 | 成员 | 分类 | 商品 | 规格 | 数量 | 金额 | 实付 | 单价 | 金额来源 |\n");
            sb.append("|---|---|---|---|---|---:|---:|---:|---:|---|\n");
            for (PurchaseRecord record : records) {
                sb.append("| ").append(escape(record.orderTime())).append(" | ")
                        .append(escape(record.owner())).append(" | ")
                        .append(escape(blankToUnknown(record.category()))).append(" | ")
                        .append(escape(record.productName())).append(" | ")
                        .append(escape(record.sku())).append(" | ")
                        .append(formatQuantity(record.quantity(), record.unit())).append(" | ")
                        .append(formatAmount(record.totalAmount())).append(" | ")
                        .append(formatAmount(record.paidAmount())).append(" | ")
                        .append(formatUnitPrice(record.unitPrice())).append(" | ")
                        .append(escape(amountSourceName(record.amountSource()))).append(" |")
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

    private Map<String, Double> sumBy(List<PurchaseRecord> records, java.util.function.Function<PurchaseRecord, String> classifier) {
        return records.stream()
                .collect(Collectors.groupingBy(classifier, Collectors.summingDouble(PurchaseRecord::totalAmount)))
                .entrySet()
                .stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
    }

    private void appendAmountTable(StringBuilder sb, String name, Map<String, Double> amountMap, double total) {
        sb.append("| ").append(name).append(" | 金额 | 占比 |\n|---|---:|---:|\n");
        if (amountMap.isEmpty()) {
            sb.append("| 暂无数据 | 0.00 | 0.00% |\n\n");
            return;
        }
        amountMap.forEach((key, amount) -> sb.append("| ").append(escape(key)).append(" | ")
                .append(formatAmount(amount)).append(" | ")
                .append(formatPercent(amount, total)).append(" |\n"));
        sb.append("\n");
    }

    private void appendAmountSourceTable(StringBuilder sb, Map<String, Double> amountSourceSum, double total) {
        sb.append("| 金额来源 | 金额 | 占比 |\n|---|---:|---:|\n");
        if (amountSourceSum.isEmpty()) {
            sb.append("| 暂无数据 | 0.00 | 0.00% |\n");
            return;
        }
        amountSourceSum.forEach((source, amount) -> sb.append("| ").append(escape(amountSourceName(source))).append(" | ")
                .append(formatAmount(amount)).append(" | ")
                .append(formatPercent(amount, total)).append(" |\n"));
    }

    private void appendTopRecords(StringBuilder sb, List<PurchaseRecord> records) {
        sb.append("| 排名 | 商品 | 分类 | 成员 | 金额 | 单价 |\n|---:|---|---|---|---:|---:|\n");
        if (records.isEmpty()) {
            sb.append("| - | 暂无数据 | - | - | 0.00 | - |\n\n");
            return;
        }
        List<PurchaseRecord> topRecords = records.stream()
                .sorted(Comparator.comparing(PurchaseRecord::totalAmount, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .toList();
        for (int index = 0; index < topRecords.size(); index++) {
            PurchaseRecord record = topRecords.get(index);
            sb.append("| ").append(index + 1).append(" | ")
                    .append(escape(record.productName())).append(" | ")
                    .append(escape(blankToUnknown(record.category()))).append(" | ")
                    .append(escape(record.owner())).append(" | ")
                    .append(formatAmount(record.totalAmount())).append(" | ")
                    .append(formatUnitPrice(record.unitPrice())).append(" |\n");
        }
        sb.append("\n");
    }

    private String amountSourceName(String source) {
        if ("product_amount_adjusted".equals(source)) {
            return "商品金额折算";
        }
        if ("paid_amount".equals(source)) {
            return "实付金额";
        }
        return blankToUnknown(source);
    }

    private String formatAmount(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.2f", value);
    }

    private String formatUnitPrice(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.4f", value);
    }

    private String formatPercent(double amount, double total) {
        if (total <= 0D) {
            return "0.00%";
        }
        return String.format(Locale.ROOT, "%.2f%%", amount * 100D / total);
    }

    private String formatQuantity(Double quantity, String unit) {
        if (quantity == null) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.4f", quantity) + escape(unit == null ? "" : unit);
    }

    private String escape(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
