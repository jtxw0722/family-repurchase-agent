package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/11/11 19:34:39
 * @Description: 截图候选商品的历史记录查重预览，只表达匹配状态，不代表本次解析已写入数据库
 *
 * @param status saved 表示订单号及商品明细一致，possible 表示明细相同但缺少订单号证据，none 表示未找到，unavailable 表示未完成核对
 * @param matchedRecordId 命中的历史购买记录 ID；未命中或未核对时为空
 */
public record ParsedDuplicatePreview(String status, Long matchedRecordId) {
    /** 订单号与商品明细一致，已存在历史记录。 */
    public static final String SAVED = "saved";
    /** 商品明细一致，但不能仅凭同日同价确认是同一订单。 */
    public static final String POSSIBLE = "possible";
    /** 已完成查询，未发现匹配记录。 */
    public static final String NONE = "none";
    /** 必要字段缺失或查询失败，不能判定是否重复。 */
    public static final String UNAVAILABLE = "unavailable";
}
