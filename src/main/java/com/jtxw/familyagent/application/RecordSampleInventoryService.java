package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.RecordPurchaseCommand;
import com.jtxw.familyagent.domain.model.RecordPurchaseResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @Author: jtxw
 * @Date: 2026/11/17 19:42:04
 * @Description: 记录样本应用协调器，在同一事务内保存真实购买和增加库存，不介入批量历史导入或价格计算
 */
@Service
public class RecordSampleInventoryService {
    /** 原购买记录应用服务，负责原有归一化、价格与复核规则，非空。 */
    private final RecordPurchaseApplicationService purchases;
    /** 库存应用服务，负责标准单位及幂等明细入库，非空。 */
    private final InventoryApplicationService inventory;

    /**
     * 创建记录样本协调器，无副作用。
     * @param purchases 原购买记录用例
     * @param inventory 独立库存用例
     */
    public RecordSampleInventoryService(RecordPurchaseApplicationService purchases, InventoryApplicationService inventory) {
        this.purchases = purchases;
        this.inventory = inventory;
    }

    /**
     * 保存样本，可选同步入库；预览和用户标记历史补录时不改变库存。
     * @param command 原购买记录命令
     * @param receiveInventory 用户确认本次是真实入库，历史补录为false
     * @return 原购买记录响应；库存失败时整体回滚，不留下仅保存购买的半成功结果
     */
    @Transactional(rollbackFor = Exception.class)
    public RecordPurchaseResult record(RecordPurchaseCommand command, boolean receiveInventory) {
        var result = purchases.record(command);
        if (receiveInventory && !result.dryRun()) {
            for (var row : result.records()) {
                // 疑似重复和待复核记录沿用原有排除决定，不自动增加当前库存。
                if ("include".equals(row.decision()) && row.recordId() != null) { inventory.receivePurchase(row.recordId()); }
            }
        }
        return result;
    }
}
