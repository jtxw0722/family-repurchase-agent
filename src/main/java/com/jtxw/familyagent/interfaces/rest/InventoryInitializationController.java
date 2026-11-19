package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.InventoryInitializationService;
import com.jtxw.familyagent.application.InventoryInitializationService.ConfirmCommand;
import com.jtxw.familyagent.application.InventoryInitializationService.Preview;
import com.jtxw.familyagent.application.InventoryInitializationService.SyncResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 22:20:17
 * @Description: 库存初始化REST适配器，接收历史区间查询与明确勾选命令，不访问仓储或承担数量计算
 */
@RestController
@RequestMapping("/api/inventory/initialization")
public class InventoryInitializationController {
    /** 库存初始化应用服务，非空，负责预览只读和确认事务边界。 */
    private final InventoryInitializationService service;

    /**
     * 创建初始化REST入口，无副作用。
     * @param service 初始化应用服务，非空
     */
    public InventoryInitializationController(InventoryInitializationService service) {
        this.service = service;
    }

    /**
     * 查询默认近一年或指定自然日闭区间的购买记录，不写数据库。
     * @param startDate 起始日，可空
     * @param endDate 结束日，可空，默认上海今日
     * @return 日期区间及可筛选、可勾选的明细，非法日期范围返回400
     */
    @GetMapping("/preview")
    public Preview preview(@RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate) {
        return service.preview(startDate, endDate);
    }

    /**
     * 将用户明确勾选的记录同步入库，复用事务及购买明细幂等，不修改购买数据。
     * @param command 勾选明细、预览区间及请求键
     * @return 实际同步和已同步跳过数量；参数、状态或单位失效时400并整批回滚
     */
    @PostMapping("/confirm")
    public SyncResult confirm(@RequestBody ConfirmCommand command) {
        return service.confirm(command);
    }

    /**
     * 预估勾选记录同步后各商品的今日剩余，POST仅承载选择参数，不写入库存。
     * @param command 起止日及勾选ID，不要求请求键
     * @return 各商品购买量、预计当前库存与待估算提示，失效选择返回400
     */
    @PostMapping("/estimate")
    public java.util.Map<String, Object> estimate(@RequestBody ConfirmCommand command) {
        return service.estimate(command);
    }
}
