package com.jtxw.familyagent.application.query;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 15:58:00
 * @Description: 历史价格基准线查询，用于承载 get-price-baseline 用例的输入参数。
 *
 * @param productName 原始商品名称，会在服务端进行本地规则归一化
 * @param unit        可选统计单位，例如 kg、抽、L；为空时使用商品规则中的标准单位
 */
public record GetPriceBaselineQuery(
        String productName,
        String unit
) {
}
