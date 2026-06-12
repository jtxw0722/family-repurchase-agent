package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.PurchaseRecordSearchService;
import com.jtxw.familyagent.application.SearchPurchaseRecordsResult;
import com.jtxw.familyagent.interfaces.rest.request.SearchPurchaseRecordsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author: jtxw
 * @Date: 2026/06/12 13:09:27
 * @Description: 原始购买记录检索工具 Controller，暴露只读历史订单样本关键词查询接口。
 */
@Tag(name = "Agent Tool API", description = "家庭复购品价格决策工具接口")
@RestController
@RequestMapping("/api/tools")
public class PurchaseRecordSearchController {
    /**
     * 原始购买记录检索应用服务，负责查询参数清洗和只读样本返回。
     */
    private final PurchaseRecordSearchService purchaseRecordSearchService;

    /**
     * 创建原始购买记录检索工具 Controller。
     *
     * @param purchaseRecordSearchService 原始购买记录检索应用服务
     */
    public PurchaseRecordSearchController(PurchaseRecordSearchService purchaseRecordSearchService) {
        this.purchaseRecordSearchService = purchaseRecordSearchService;
    }

    /**
     * 按关键词检索原始购买记录样本。
     *
     * <p>该接口只返回历史订单样本，不生成 baseline，不参与正式价格决策。</p>
     *
     * @param request 原始购买记录检索请求
     * @return 原始购买记录检索结果
     */
    @Operation(summary = "检索原始购买记录", description = "按关键词检索原始历史购买记录样本，仅供无可靠基线时参考，不生成价格基线。")
    @PostMapping("/purchase-records/search")
    public SearchPurchaseRecordsResult searchPurchaseRecords(@Valid @RequestBody SearchPurchaseRecordsRequest request) {
        return purchaseRecordSearchService.search(request.toQuery());
    }
}
