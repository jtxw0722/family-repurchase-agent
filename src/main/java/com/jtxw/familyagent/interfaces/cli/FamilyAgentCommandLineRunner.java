package com.jtxw.familyagent.interfaces.cli;

import com.jtxw.familyagent.application.ImportApplicationService;
import com.jtxw.familyagent.application.PriceAnalysisApplicationService;
import com.jtxw.familyagent.application.ReportApplicationService;
import com.jtxw.familyagent.application.ReviewApplicationService;
import com.jtxw.familyagent.domain.model.*;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/13/11:46
 * @Description: CLI 辅助入口，支持本地导入、比价、报告和复核查询命令。
 */
@Component
public class FamilyAgentCommandLineRunner implements ApplicationRunner {
    private final ConfigurableApplicationContext applicationContext;
    private final DatabaseInitializer databaseInitializer;
    private final ImportApplicationService importApplicationService;
    private final PriceAnalysisApplicationService priceAnalysisApplicationService;
    private final ReportApplicationService reportApplicationService;
    private final ReviewApplicationService reviewApplicationService;

    public FamilyAgentCommandLineRunner(ConfigurableApplicationContext applicationContext,
                                        DatabaseInitializer databaseInitializer,
                                        ImportApplicationService importApplicationService,
                                        PriceAnalysisApplicationService priceAnalysisApplicationService,
                                        ReportApplicationService reportApplicationService,
                                        ReviewApplicationService reviewApplicationService) {
        this.applicationContext = applicationContext;
        this.databaseInitializer = databaseInitializer;
        this.importApplicationService = importApplicationService;
        this.priceAnalysisApplicationService = priceAnalysisApplicationService;
        this.reportApplicationService = reportApplicationService;
        this.reviewApplicationService = reviewApplicationService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> sourceArgs = args.getNonOptionArgs();
        if (sourceArgs.isEmpty()) {
            return;
        }
        String command = sourceArgs.get(0);
        int exitCode = 0;
        try {
            switch (command) {
                case "init" -> init();
                case "import" -> importFile(sourceArgs, args);
                case "price" -> price(sourceArgs, args);
                case "report" -> report(args);
                case "review" -> review(sourceArgs, args);
                default -> printHelp();
            }
        } catch (RuntimeException e) {
            System.err.println("执行失败：" + e.getMessage());
            exitCode = 1;
        } finally {
            int finalExitCode = exitCode;
            SpringApplication.exit(applicationContext, () -> finalExitCode);
            System.exit(finalExitCode);
        }
    }

    private void init() {
        databaseInitializer.initialize();
        System.out.println("初始化完成：SQLite 数据库已准备好。");
    }

    private void importFile(List<String> sourceArgs, ApplicationArguments args) {
        if (sourceArgs.size() < 2) {
            System.out.println("请提供文件路径，例如：import examples/sample_orders.csv --owner=jtxw");
            return;
        }
        ImportResult result = importApplicationService.importCsv(Path.of(sourceArgs.get(1)), getString(args, "owner", null));
        System.out.println(result.message());
        System.out.println("批次 ID：" + result.batchId());
    }

    private void price(List<String> sourceArgs, ApplicationArguments args) {
        if (sourceArgs.size() < 2) {
            System.out.println("请提供商品名称，例如：price 猫砂 --price 89 --quantity 12 --unit kg");
            return;
        }
        String productName = sourceArgs.get(1);
        double price = getDouble(args, "price");
        double quantity = getDouble(args, "quantity");
        String unit = getString(args, "unit", "件");
        PriceDecisionResult result = priceAnalysisApplicationService.comparePrice(productName, price, quantity, unit);
        System.out.println("商品：" + result.productName());
        System.out.println("标准化名称：" + result.normalizedName());
        System.out.printf("当前单价：%.4f 元/%s%n", result.currentUnitPrice(), result.unit());
        System.out.println("历史最低单价：" + valueOrDash(result.historicalMin()));
        System.out.println("历史中位单价：" + valueOrDash(result.historicalMedian()));
        System.out.println("历史平均单价：" + valueOrDash(result.historicalAverage()));
        System.out.println("历史样本数：" + result.sampleSize() + " 条");
        System.out.println("判断：" + result.decisionText());
        System.out.println("原因：" + result.reason());
    }

    private void report(ApplicationArguments args) {
        String month = getString(args, "month", null);
        if (month == null) {
            System.out.println("请提供月份，例如：report --month 2026-05");
            return;
        }
        MonthlyReportResult result = reportApplicationService.generateMonthlyReport(month);
        System.out.println("报告已生成：" + result.reportPath());
        System.out.println("统计记录数：" + result.recordCount() + " 条");
        System.out.printf("总支出：%.2f 元%n", result.totalAmount());
        System.out.println("待复核记录：" + result.pendingReviewCount() + " 条");
    }

    private void review(List<String> sourceArgs, ApplicationArguments args) {
        if (sourceArgs.size() >= 2 && "list".equals(sourceArgs.get(1))) {
            List<ReviewItemDetail> items = reviewApplicationService.listPending();
            if (items.isEmpty()) {
                System.out.println("当前没有待复核记录。");
                return;
            }
            for (ReviewItemDetail item : items) {
                System.out.printf("#%d record=%d reason=%s status=%s product=%s amount=%.2f paid=%s source=%s%n",
                        item.id(), item.recordId(), item.reasonCode(), item.status(), valueOrDash(item.productName()),
                        item.totalAmount() == null ? 0D : item.totalAmount(), valueOrDash(item.paidAmount()),
                        valueOrDash(item.amountSource()));
                System.out.printf("  owner=%s time=%s sku=%s unitPrice=%s file=%s%n",
                        valueOrDash(item.owner()), valueOrDash(item.orderTime()), valueOrDash(item.sku()),
                        valueOrDash(item.unitPrice()), valueOrDash(item.sourceFile()));
                System.out.println("  message=" + item.reasonMessage());
            }
            return;
        }
        if (sourceArgs.size() >= 3 && "apply".equals(sourceArgs.get(1))) {
            long reviewId = Long.parseLong(sourceArgs.get(2));
            String action = getString(args, "action", null);
            String note = getString(args, "note", null);
            ReviewApplyResult result = reviewApplicationService.apply(reviewId, action, note);
            System.out.println(result.message());
            System.out.println("复核 ID：" + result.reviewId());
            System.out.println("记录 ID：" + result.recordId());
            System.out.println("动作：" + result.action());
            System.out.println("统计决策：" + result.decision());
            return;
        }
        System.out.println("用法：review list | review apply <id> --action=include|exclude [--note=说明]");
    }

    private void printHelp() {
        System.out.println("Family Consumption Agent");
        System.out.println("用法：");
        System.out.println("  init");
        System.out.println("  import <csv-or-xlsx-file> [--owner=<归属人>]");
        System.out.println("  price <商品名> --price <金额> --quantity <数量> --unit <单位>");
        System.out.println("  report --month YYYY-MM");
        System.out.println("  review list");
        System.out.println("  review apply <id> --action=include|exclude [--note <说明>]");
    }

    private double getDouble(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("缺少参数 --" + name);
        }
        return Double.parseDouble(values.get(0));
    }

    private String getString(ApplicationArguments args, String name, String defaultValue) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        return values.get(0);
    }

    private String valueOrDash(Double value) {
        return value == null ? "-" : String.format("%.4f", value);
    }

    private String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
