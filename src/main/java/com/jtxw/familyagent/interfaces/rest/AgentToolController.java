package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.ImportApplicationService;
import com.jtxw.familyagent.application.PriceAnalysisApplicationService;
import com.jtxw.familyagent.application.ReportApplicationService;
import com.jtxw.familyagent.application.ReviewApplicationService;
import com.jtxw.familyagent.domain.model.ImportResult;
import com.jtxw.familyagent.domain.model.MonthlyReportResult;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.ReviewItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/13/10:28
 * @Description: REST Tool API 控制器，暴露导入、比价、报告和复核查询接口。
 */
@RestController
@RequestMapping("/api/tools")
public class AgentToolController {
    private final ImportApplicationService importApplicationService;
    private final PriceAnalysisApplicationService priceAnalysisApplicationService;
    private final ReportApplicationService reportApplicationService;
    private final ReviewApplicationService reviewApplicationService;

    public AgentToolController(ImportApplicationService importApplicationService,
                               PriceAnalysisApplicationService priceAnalysisApplicationService,
                               ReportApplicationService reportApplicationService,
                               ReviewApplicationService reviewApplicationService) {
        this.importApplicationService = importApplicationService;
        this.priceAnalysisApplicationService = priceAnalysisApplicationService;
        this.reportApplicationService = reportApplicationService;
        this.reviewApplicationService = reviewApplicationService;
    }

    @PostMapping("/import-file")
    public ImportResult importFile(@Valid @RequestBody ImportFileRequest request) {
        return importApplicationService.importCsv(Path.of(request.filePath()));
    }

    @PostMapping("/compare-price")
    public PriceDecisionResult comparePrice(@Valid @RequestBody ComparePriceRequest request) {
        return priceAnalysisApplicationService.comparePrice(request.productName(), request.price(), request.quantity(), request.unit());
    }

    @PostMapping("/generate-report")
    public MonthlyReportResult generateReport(@Valid @RequestBody GenerateReportRequest request) {
        return reportApplicationService.generateMonthlyReport(request.month());
    }

    @GetMapping("/review-items")
    public List<ReviewItem> listReviewItems() {
        return reviewApplicationService.listPending();
    }

    public record ImportFileRequest(@NotBlank String filePath) {}
    public record ComparePriceRequest(@NotBlank String productName, @Positive double price, @Positive double quantity, @NotBlank String unit) {}
    public record GenerateReportRequest(@NotBlank String month) {}
}
