package com.jtxw.familyagent.application.query;

/**
 * @Author: jtxw
 * @Date: 2026/06/12 13:09:27
 * @Description: 原始购买记录关键词检索查询，用于承载只读历史订单样本检索的输入参数。
 *
 * @param keyword  查询关键词，按商品名称、SKU、分类和归一化名称做模糊匹配，trim 后不允许为空
 * @param owner    可选订单归属人，空值表示查询全家庭样本，有值时仅查询指定 owner
 * @param limit    可选返回条数，空值使用默认值，超过最大值时由应用服务截断
 * @param fromDate 可选开始日期，格式 yyyy-MM-dd，按订单时间下界过滤
 * @param toDate   可选结束日期，格式 yyyy-MM-dd，按订单时间上界过滤
 */
public record SearchPurchaseRecordsQuery(
        String keyword,
        String owner,
        Integer limit,
        String fromDate,
        String toDate
) {
}
