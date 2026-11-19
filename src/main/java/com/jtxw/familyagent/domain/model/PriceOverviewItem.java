package com.jtxw.familyagent.domain.model;

import java.math.BigDecimal;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 19:39:30
 * @Description: 价格看板查询结果，汇总同一归一化商品和标准单位下的家庭实购单价
 *
 * @param normalizedName 归一化商品名称，非空
 * @param category 商品品类，允许为空
 * @param standardUnit 标准单位，未配置时价格字段均为空
 * @param historicalMin 历史最低单价，单位元/standardUnit，无样本时为空
 * @param historicalMedian 历史单价中位数，单位元/standardUnit，无样本时为空
 * @param latestUnitPrice 最近一笔有日期的有效购买单价，单位元/standardUnit，没有可定位的记录时为空
 * @param latestPurchaseDate 最近一笔有效购买的日期，格式 yyyy-MM-dd，没有可定位的记录时为空
 */
public record PriceOverviewItem(String normalizedName, String category, String standardUnit,
                                BigDecimal historicalMin, BigDecimal historicalMedian,
                                BigDecimal latestUnitPrice, String latestPurchaseDate) {
}
