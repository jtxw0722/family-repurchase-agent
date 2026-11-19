package com.jtxw.familyagent.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 22:20:17
 * @Description: 手动入库应用命令，仅接收库存数量及可选入库信息，不承载购买关联或价格
 * @param requestKey 幂等键，非空且不超过120字符
 * @param normalizedProductId 已有归一化规则ID，非空
 * @param quantity 非负入库数量，非空，零可用于初始化空库存
 * @param unit 用户数量单位，非空
 * @param specification 可选明确单包装净含量，如4.5kg/袋，不推断复杂包装
 * @param receiptDate 可选实际入库日期，默认上海今日，不允许未来；从该自然日起算消耗
 * @param note 可选入库备注，最多500字符，仅保留最近一次
 */
public record InventoryReceiptCommand(String requestKey, Long normalizedProductId, BigDecimal quantity,
        String unit, String specification, LocalDate receiptDate, String note) { }
