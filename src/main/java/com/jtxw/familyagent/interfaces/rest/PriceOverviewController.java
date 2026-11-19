package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.PriceOverviewApplicationService;
import com.jtxw.familyagent.domain.model.PriceOverviewItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 19:39:30
 * @Description: 首页历史价格看板的 REST 查询入口，将请求委托给价格看板应用服务
 */
@RestController
public class PriceOverviewController {
    /** 提供看板价格查询的应用服务，非空。 */
    private final PriceOverviewApplicationService priceOverviewService;

    /**
     * 注入首页价格看板应用服务。
     *
     * @param priceOverviewService 价格看板应用服务
     */
    public PriceOverviewController(PriceOverviewApplicationService priceOverviewService) {
        this.priceOverviewService = priceOverviewService;
    }

    /**
     * 返回规则库商品对应的历史价格，用于首页看板展示。
     *
     * @return 全家庭历史价格列表，暂无有效样本的商品价格为空
     */
    @GetMapping("/api/dashboard/prices")
    public List<PriceOverviewItem> listPrices() {
        return priceOverviewService.listPrices();
    }
}
