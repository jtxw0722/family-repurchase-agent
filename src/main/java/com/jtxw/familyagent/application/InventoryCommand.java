package com.jtxw.familyagent.application;

import java.math.BigDecimal;

/**
 * @Author: jtxw
 * @Date: 2026/11/17 19:42:04
 * @Description: 库存应用命令，承载独立库存操作输入，不含任何必填价格字段
 * @param requestKey 每次操作唯一幂等键，非空，最多120字符
 * @param normalizedProductId 兼容旧请求的归一化规则ID，数量维护以路径库存ID为准
 * @param quantity 非负库存数量，设置时null表示未知，增加时必须正数
 * @param unit 数量单位，创建和数量操作时必填
 * @param source AUTO或MANUAL，仅设置时必填
 * @param referenceQuantity 人工参考数量，MANUAL时正数
 * @param referenceUnit 人工参考单位，MANUAL时必填
 * @param referenceDays 人工预计使用自然日数，MANUAL时正数
 * @param leadDays 提前提醒自然日数，设置时必填且非负
 * @param snoozeDays 延后自然日数，延后操作时正整数
 */
public record InventoryCommand(String requestKey, Long normalizedProductId, BigDecimal quantity, String unit,
        String source, BigDecimal referenceQuantity, String referenceUnit, BigDecimal referenceDays,
        Integer leadDays, Integer snoozeDays) { }
