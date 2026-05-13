package com.jtxw.familyagent.interfaces.cli;

import com.jtxw.familyagent.application.ImportApplicationService;
import com.jtxw.familyagent.application.PriceAnalysisApplicationService;
import com.jtxw.familyagent.application.ReportApplicationService;
import com.jtxw.familyagent.application.ReviewApplicationService;
import com.jtxw.familyagent.domain.model.ImportResult;
import com.jtxw.familyagent.domain.model.MonthlyReportResult;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.ReviewItem;
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
        try {
            switch (command) {
                case "init" -> init();
                case "import" -> importFile(sourceArgs);
                case "price" -> price(sourceArgs, args);
                case "report" -> report(args);
                case "review" -> review(sourceArgs);
                default -> printHelp();
            }
        } finally {
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }

    private void init() {
        databaseInitializer.initialize();
        System.out.println("初始化完成：SQLite 数据库已准备好。");
    }

    private void importFile(List<String> sourceArgs) {
        if (sourceArgs.size() < 2) {
            System.out.println("请提供文件路径，例如：import examples/sample_orders.csv");
            return;
        }
        ImportResult result = importApplicationService.importCsv(Path.of(sourceArgs.get(1)));
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

    private void review(List<String> sourceArgs) {
        if (sourceArgs.size() >= 2 && "list".equals(sourceArgs.get(1))) {
            List<ReviewItem> items = reviewApplicationService.listPending();
            if (items.isEmpty()) {
                System.out.println("当前没有待复核记录。");
                return;
            }
            for (ReviewItem item : items) {
                System.out.printf("#%d record=%d reason=%s message=%s status=%s%n",
                        item.id(), item.recordId(), item.reasonCode(), item.reasonMessage(), item.status());
            }
            return;
        }
        System.out.println("用法：review list");
    }

    private void printHelp() {
        System.out.println("Family Consumption Agent");
        System.out.println("用法：");
        System.out.println("  init");
        System.out.println("  import <csv-file>");
        System.out.println("  price <商品名> --price <金额> --quantity <数量> --unit <单位>");
        System.out.println("  report --month YYYY-MM");
        System.out.println("  review list");
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
}
