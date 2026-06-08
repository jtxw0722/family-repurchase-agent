package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.RecordPurchaseApplicationService;
import com.jtxw.familyagent.domain.model.RecordPurchaseResult;
import com.jtxw.familyagent.interfaces.rest.request.RecordPurchaseRequest;
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
 * @Description: 手动购买记录录入工具 Controller，暴露结构化购买记录录入接口。
 */
@Tag(name = "Agent Tool API", description = "家庭复购品价格决策工具接口")
@RestController
@RequestMapping("/api/tools")
public class RecordPurchaseToolController {
    /**
     * 手动购买记录录入应用服务，负责结构化记录校验和入库。
     */
    private final RecordPurchaseApplicationService recordPurchaseApplicationService;

    /**
     * 创建手动购买记录录入工具 Controller。
     *
     * @param recordPurchaseApplicationService 手动记录录入应用服务
     */
    public RecordPurchaseToolController(RecordPurchaseApplicationService recordPurchaseApplicationService) {
        this.recordPurchaseApplicationService = recordPurchaseApplicationService;
    }

    /**
     * 录入手动或自然语言抽取后的结构化购买记录。
     *
     * <p>Controller 只负责暴露工具型 REST API，业务校验、归一化、单价计算、去重和复核创建
     * 均由 RecordPurchaseApplicationService 完成。</p>
     *
     * @param request 手动购买记录录入请求
     * @return 逐条录入结果
     */
    @Operation(summary = "录入购买记录", description = "录入 Claude 已抽取好的结构化购买记录，并由后端完成归一化、单价计算、去重、入库或复核。")
    @PostMapping("/record-purchase")
    public RecordPurchaseResult recordPurchase(@Valid @RequestBody RecordPurchaseRequest request) {
        return recordPurchaseApplicationService.record(request.toCommand());
    }
}
