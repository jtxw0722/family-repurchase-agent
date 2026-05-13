package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/01:05
 * @Description: CSV 原始订单记录对象，保留导入阶段解析出的基础字段。
 */
public record RawPurchaseRecord(
        String orderTime,
        String platform,
        String owner,
        String productName,
        String sku,
        String category,
        String subCategory,
        Double quantity,
        String unit,
        Double totalAmount,
        String currency
) {}
