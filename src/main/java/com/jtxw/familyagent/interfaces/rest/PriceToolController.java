package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.PriceAnalysisApplicationService;
import com.jtxw.familyagent.domain.model.PriceBaselineResult;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.interfaces.rest.request.ComparePriceRequest;
import com.jtxw.familyagent.interfaces.rest.request.GetPriceBaselineRequest;
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
 * @Description: 价格分析工具 Controller，暴露历史基准线查询和当前价格比较接口。
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
     * 查询商品历史价格基准线。
     *
     * @param request 历史价格基准线查询请求
     * @return 历史价格基准线，包括历史最低价、中位价、平均价、样本数量和证据
     */
    @Operation(summary = "查询历史价格基准线", description = "查询指定复购品的本地历史价格基准线，包括历史最低价、中位价、平均价、样本数量和证据。")
    @PostMapping("/get-price-baseline")
    public PriceBaselineResult getPriceBaseline(@Valid @RequestBody GetPriceBaselineRequest request) {
        return priceAnalysisApplicationService.getPriceBaseline(request.toQuery());
    }

    /**
     * 比较当前商品价格与历史价格，返回价格判断结果。
     *
     * @param request 当前价格比较请求
     * @return 价格判断结果，包括当前单位价格、历史统计值和判断说明
     */
    @Operation(summary = "比较当前价格", description = "比较当前商品单位价格与本地历史价格，返回价格判断结果。")
    @PostMapping("/compare-price")
    public PriceDecisionResult comparePrice(@Valid @RequestBody ComparePriceRequest request) {
        return priceAnalysisApplicationService.comparePrice(request.toQuery());
    }
}
