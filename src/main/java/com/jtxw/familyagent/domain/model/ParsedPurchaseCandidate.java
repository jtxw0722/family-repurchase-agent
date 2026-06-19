package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 18:35:00
 * @Description: OCR 订单截图解析出的购买候选样本，字段对齐手工购买记录并等待用户确认
 *
 * @param productName OCR 识别的商品名称，无法识别时为空
 * @param sku OCR 识别的商品规格，无法识别时为空
 * @param price OCR 识别的实付金额，单位为元，无法识别或无法分摊时为空
 * @param quantity 从商品规格折算的总数量，无法识别时为空
 * @param unit 数量对应的标准单位，无法识别时为空
 * @param platform 购买平台，优先使用请求值，允许为空
 * @param owner 订单归属人，仅用于溯源，允许为空
 * @param purchaseDate 购买日期或时间，优先使用请求值，允许为空
 * @param shopName OCR 识别的店铺名称，允许为空
 * @param note 候选样本用途说明，固定提示需要用户确认后入库
 * @param sourceText OCR 原文片段，最多保留前 500 个字符
 * @param confidence 规则解析置信度，取值范围为 0.1 到 0.95
 * @param warnings 当前候选样本的解析警告，无警告时为空列表
 * @param normalization 候选样本的归一化规则预览，解析器直接返回时允许为空
 */
public record ParsedPurchaseCandidate(
        String productName,
        String sku,
        Double price,
        Double quantity,
        String unit,
        String platform,
        String owner,
        String purchaseDate,
        String shopName,
        String note,
        String sourceText,
        Double confidence,
        List<String> warnings,
        ParsedNormalizationPreview normalization
) {
    /**
     * 创建尚未补充归一化预览的 OCR 候选样本，兼容现有解析器及调用方。
     *
     * @param productName OCR 识别的商品名称
     * @param sku OCR 识别的商品规格
     * @param price OCR 识别的实付金额，单位为元
     * @param quantity 从商品规格折算的总数量
     * @param unit 数量对应的标准单位
     * @param platform 购买平台
     * @param owner 订单归属人
     * @param purchaseDate 购买日期或时间
     * @param shopName OCR 识别的店铺名称
     * @param note 候选样本用途说明
     * @param sourceText OCR 原文长度限制片段
     * @param confidence 规则解析置信度
     * @param warnings 当前候选样本的解析警告
     */
    public ParsedPurchaseCandidate(String productName,
                                   String sku,
                                   Double price,
                                   Double quantity,
                                   String unit,
                                   String platform,
                                   String owner,
                                   String purchaseDate,
                                   String shopName,
                                   String note,
                                   String sourceText,
                                   Double confidence,
                                   List<String> warnings) {
        this(productName, sku, price, quantity, unit, platform, owner, purchaseDate,
                shopName, note, sourceText, confidence, warnings, null);
    }

    /**
     * 返回附带归一化预览的新候选样本，不修改原候选对象。
     *
     * @param normalization 归一化规则命中预览，不允许为空
     * @return 保留原字段并附带归一化预览的新候选样本
     */
    public ParsedPurchaseCandidate withNormalization(ParsedNormalizationPreview normalization) {
        return new ParsedPurchaseCandidate(productName, sku, price, quantity, unit,
                platform, owner, purchaseDate, shopName, note, sourceText,
                confidence, warnings, normalization);
    }
}
