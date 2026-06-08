package com.jtxw.familyagent.application.command;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 14:59:00
 * @Description: 手动购买记录录入应用层命令，用于承载 record-purchase 用例的输入参数。
 *
 * @param dryRun  是否只预览不写入数据库；true 表示仅执行校验和价格分析，不落库
 * @param records 待录入的购买记录明细列表
 */
public record RecordPurchaseCommand(
        Boolean dryRun,
        List<Item> records
) {
    /**
     * @param productName       原始商品名称，例如“猫砂”“纸巾”“洗衣液”
     * @param price             购买总价
     * @param quantity          购买数量
     * @param unit              数量单位，例如 kg、L、抽、包
     * @param platform          购买平台；为空时由应用服务按默认平台处理
     * @param purchaseDate      购买日期，格式建议为 yyyy-MM-dd；为空时由应用服务按默认日期处理
     * @param owner             订单归属人；为空时由应用服务按默认归属人处理
     * @param shopName          店铺名称
     * @param sku               商品规格或 SKU，例如“6kg*4包”
     * @param note              人工备注
     * @param sourceText        Claude 抽取前的原始自然语言文本，用于审计和问题排查
     * @param confirmOutOfRange 是否确认接受偏离历史价格区间的样本
     *
     * <p>该对象表示应用服务处理一条购买记录所需的结构化字段。
     * 字段可以来自用户手动填写，也可以来自 Claude 等 LLM 对自然语言文本的结构化抽取结果。</p>
     */
    public record Item(
            String productName,
            Double price,
            Double quantity,
            String unit,
            String platform,
            String purchaseDate,
            String owner,
            String shopName,
            String sku,
            String note,
            String sourceText,
            Boolean confirmOutOfRange
    ) {
        /**
         * 创建单条手动购买记录录入命令明细。
         *
         * <p>该构造方法用于兼容不需要显式传入 confirmOutOfRange 的调用场景，
         * 默认不确认接受偏离历史价格区间的样本。</p>
         *
         * @param productName  原始商品名称
         * @param price        购买总价
         * @param quantity     购买数量
         * @param unit         数量单位
         * @param platform     购买平台
         * @param purchaseDate 购买日期
         * @param owner        订单归属人
         * @param shopName     店铺名称
         * @param sku          商品规格或 SKU
         * @param note         人工备注
         * @param sourceText   Claude 抽取前的原始自然语言文本
         */
        public Item(String productName,
                    Double price,
                    Double quantity,
                    String unit,
                    String platform,
                    String purchaseDate,
                    String owner,
                    String shopName,
                    String sku,
                    String note,
                    String sourceText) {
            this(productName, price, quantity, unit, platform, purchaseDate, owner,
                    shopName, sku, note, sourceText, false);
        }
    }
}