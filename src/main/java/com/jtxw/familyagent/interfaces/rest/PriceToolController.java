package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.PriceAnalysisApplicationService;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.interfaces.rest.request.ComparePriceRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 09:30:00
 * @Description: 价格分析工具 Controller，暴露统一 compare-price 接口并支持历史基准线查询和当前价格比较。
 */
@Tag(name = "Agent Tool API", description = "家庭复购品价格决策工具接口")
@RestController
@RequestMapping("/api/tools")
public class PriceToolController {
    /**
     * 价格分析应用服务，负责比价和历史基准线查询。
     */
    private final PriceAnalysisApplicationService priceAnalysisApplicationService;

    /**
     * 创建价格分析工具 Controller。
     *
     * @param priceAnalysisApplicationService 价格分析应用服务
     */
    public PriceToolController(PriceAnalysisApplicationService priceAnalysisApplicationService) {
        this.priceAnalysisApplicationService = priceAnalysisApplicationService;
    }

    /**
     * 查询历史价格基准线，或比较当前商品价格与历史价格。
     *
     * @param request 价格分析请求；只传 productName 时为 baseline-only 模式，同时传 price、quantity、unit 时为 compare 模式
     * @return 价格分析结果；baseline-only 模式下 current 和 decision 为 null，compare 模式下包含当前价格和判断结论
     */
    @Operation(summary = "价格分析", description = "不传 price、quantity、unit 时返回历史价格基准线；同时传入三者时返回价格基准线和价格比较结果。")
    @PostMapping("/compare-price")
    public PriceDecisionResult comparePrice(@Valid @RequestBody ComparePriceRequest request) {
        return priceAnalysisApplicationService.comparePrice(request.toQuery());
    }
}
