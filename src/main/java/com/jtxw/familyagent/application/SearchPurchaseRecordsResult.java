package com.jtxw.familyagent.application;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/12 13:09:27
 * @Description: 原始购买记录关键词检索结果，返回原始订单样本和查询范围说明，不表达价格基线语义。
 *
 * @param keyword       清洗后的查询关键词，不允许为空
 * @param scope         查询范围，FAMILY 表示全家庭样本，OWNER 表示指定归属人样本
 * @param owner         指定归属人；当 scope 为 FAMILY 时为空
 * @param matchedCount  符合查询条件的原始记录总数，不受 limit 截断影响
 * @param returnedCount 实际返回的记录数量，受 limit 截断影响
 * @param records       原始购买记录样本列表，无匹配时为空数组
 * @param warnings      面向 LLM 的使用风险提示，强调结果不是价格基线
 */
public record SearchPurchaseRecordsResult(
        String keyword,
        String scope,
        String owner,
        int matchedCount,
        int returnedCount,
        List<Item> records,
        List<String> warnings
) {
    /**
     * @Author: jtxw
     * @Date: 2026/06/12 13:09:27
     * @Description: 原始购买记录检索结果明细，保留订单原始字段供 LLM 做弱参考。
     *
     * @param recordId       购买记录主键 ID，来自 purchase_records.id
     * @param orderTime      订单发生时间，原始存储格式为 yyyy-MM-dd HH:mm:ss
     * @param platform       购买平台，允许为空或平台归一化后的短标识
     * @param owner          家庭成员或数据归属人标识，允许为空
     * @param productName    原始商品名称，来自导入文件或手动录入
     * @param sku            商品规格或 SKU 文本，允许为空
     * @param category       一级商品分类，允许为空
     * @param subCategory    二级商品分类，允许为空
     * @param quantity       原始数量，单位由 unit 表达，允许为空
     * @param unit           原始数量单位，允许为空
     * @param totalAmount    当前用于统计的总金额，单位为 currency，允许为空
     * @param currency       交易币种，通常为 CNY，允许为空
     * @param normalizedName 当前记录中的归一化名称，可能来自历史规则或导入兜底，不代表已完成可信归一化
     * @param unitPrice      当前记录中的单位价格，单位为元/记录单位，允许为空
     */
    public record Item(
            Long recordId,
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
            String currency,
            String normalizedName,
            Double unitPrice
    ) {
    }
}
