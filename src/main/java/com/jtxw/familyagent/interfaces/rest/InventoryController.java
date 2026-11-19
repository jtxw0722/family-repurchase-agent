package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.InventoryApplicationService;
import com.jtxw.familyagent.application.InventoryCommand;
import com.jtxw.familyagent.application.InventoryReceiptCommand;
import com.jtxw.familyagent.domain.model.ReplenishmentOverviewItem;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 19:39:30
 * @Description: 库存REST适配器，接收库存命令并转换响应，不访问购买仓储或承担领域计算
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {
    /** 独立库存应用服务，非空。 */
    private final InventoryApplicationService service;

    /** @param service 库存应用服务 */
    public InventoryController(InventoryApplicationService service) { this.service = service; }

    /** @return 已有归一化商品目录，无写入副作用 */
    @GetMapping("/products")
    public List<Map<String, Object>> products() { return service.products(); }

    /** @return 动态库存和提醒列表，刷新自动缓存，不扣减数据库库存 */
    @GetMapping
    public List<Map<String, Object>> list() { return service.list(); }

    /**
     * 查询首页所需的补货提醒摘要。
     *
     * @return 已触发和即将触发的提醒，读取时不更新库存或预测缓存
     */
    @GetMapping("/replenishment-overview")
    public List<ReplenishmentOverviewItem> replenishmentOverview() {
        return service.replenishmentOverview();
    }

    /** @param id 库存ID @return 商品详情，刷新自动估算；不存在抛参数异常 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) { return service.detail(id); }

    /** @param command 手动入库参数，不要求价格 @return 创建或累加库存，幂等且事务写入；输入错误抛参数异常 */
    @PostMapping
    public Map<String, Object> create(@RequestBody InventoryReceiptCommand command) { return service.receive(command); }

    /**
     * 接收库存命令并委托应用服务事务处理，不直接访问库存或购买仓储。
     * @param id 库存ID，reset仅作用于该商品档案
     * @param operation set/add/reset/settings/snooze，reset将库存归零并允许重新同步购买记录
     * @param command 操作参数；reset仅需非空requestKey，重复键不会重复重置
     * @return 更新后详情；非法输入抛参数异常，写库失败时整个事务回滚
     */
    @PostMapping("/{id}/{operation}")
    public Map<String, Object> change(@PathVariable long id, @PathVariable String operation, @RequestBody InventoryCommand command) {
        return service.execute(id, operation, command);
    }
}
