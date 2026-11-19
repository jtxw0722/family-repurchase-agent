package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.RecordSampleInventoryService;
import com.jtxw.familyagent.domain.model.RecordPurchaseResult;
import com.jtxw.familyagent.interfaces.rest.request.RecordPurchaseRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/17 19:42:04
 * @Description: 记录样本库存REST适配器，接收显式真实入库选择，保持原购买工具及MCP协议不变
 */
@RestController
public class RecordSampleInventoryController {
    /** 购买与库存事务协调器，非空。 */
    private final RecordSampleInventoryService service;

    /** @param service 记录样本应用协调器，无写入副作用 */
    public RecordSampleInventoryController(RecordSampleInventoryService service) { this.service = service; }

    /**
     * 保存记录样本并按用户确认自动入库；未明确确认默认只保存历史样本。
     * @param request 原购买记录输入，预览不写库存
     * @param receiveInventory 本次真实入库确认，历史补录必须为false
     * @return 原购买结果，事务保护两种写入；无可靠单位换算时400且回滚
     */
    @PostMapping("/api/inventory/record-sample")
    public RecordPurchaseResult record(@Valid @RequestBody RecordPurchaseRequest request,
            @RequestParam(defaultValue = "false") boolean receiveInventory) {
        return service.record(request.toCommand(), receiveInventory);
    }
}
