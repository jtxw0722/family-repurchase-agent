package com.jtxw.familyagent.application.query;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 15:58:00
 * @Description: 商品价格比较查询，用于承载 compare-price 用例的输入参数。
 *
 * @param productName 原始商品名称，会在服务端进行本地规则归一化
 * @param price       当前购买总价
 * @param quantity    当前购买数量
 * @param unit        数量单位，用于计算单位价格
 */
public record ComparePriceQuery(
        String productName,
        double price,
        double quantity,
        String unit
) {
}
