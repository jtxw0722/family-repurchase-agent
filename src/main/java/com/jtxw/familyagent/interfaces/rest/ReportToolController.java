package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.ReportApplicationService;
import com.jtxw.familyagent.domain.model.PriceReportResult;
import com.jtxw.familyagent.interfaces.rest.request.GenerateReportRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 17:45:00
 * @Description: 报告工具 Controller，暴露本地 Markdown 复购品价格报告生成接口。
 */
@Tag(name = "Agent Tool API", description = "家庭复购品价格决策工具接口")
@RestController
@RequestMapping("/api/tools")
public class ReportToolController {
    /**
     * 报告应用服务，负责生成本地价格报告。
     */
    private final ReportApplicationService reportApplicationService;

    /**
     * 创建报告工具 Controller。
     *
     * @param reportApplicationService 报告应用服务
     */
    public ReportToolController(ReportApplicationService reportApplicationService) {
        this.reportApplicationService = reportApplicationService;
    }

    /**
     * 生成指定月份的本地 Markdown 复购品价格报告。
     *
     * <p>报告文件会写入本地 reports 目录，统计口径由应用服务和仓储层统一控制。</p>
     *
     * @param request 价格报告请求
     * @return 报告生成结果，包括统计记录数、总金额和报告路径
     */
    @Operation(summary = "生成复购品价格报告", description = "根据指定月份生成 Markdown 价格报告。")
    @PostMapping("/generate-report")
    public PriceReportResult generateReport(@Valid @RequestBody GenerateReportRequest request) {
        return reportApplicationService.generatePriceReport(request.month());
    }
}
