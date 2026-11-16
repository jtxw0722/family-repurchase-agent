package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/11/11 19:34:39
 * @Description: OCR 订单截图解析出的购买候选样本，字段对齐手工购买记录并等待用户确认
 *
 * @param productName OCR 识别的商品名称，无法识别时为空
 * @param sku OCR 识别的商品规格，无法识别时为空
 * @param price OCR 识别的实付金额，单位为元，无法识别或无法分摊时为空
 * @param quantity 一个购买单位内的标准计量含量，例如每袋 5kg 对应 5，不表示购买件数；无法识别时为空
 * @param unit 单件含量对应的标准计量单位，例如 kg、ml，无法识别时为空
 * @param platform 购买平台，优先使用请求值，允许为空
 * @param owner 订单归属人，仅用于溯源，允许为空
 * @param purchaseDate 购买日期或时间，优先使用请求值，允许为空
 * @param shopName OCR 识别的店铺名称，允许为空
 * @param note 精简解析备注，包含 OCR 来源、归一化状态和可见赠品详情
 * @param sourceText OCR 原文片段，最多 500 个字符；必要时在末尾保留明确订单号供后续查重
 * @param confidence 规则解析置信度，取值范围为 0.1 到 0.95
 * @param warnings 当前候选样本的解析警告，无警告时为空列表
 * @param normalization 候选样本的归一化规则预览，解析器直接返回时允许为空
 * @param purchaseQuantity 购买件数，来自截图中的 ×N 或购买数量标签；仅接受正整数，未识别或存在冲突时为空
 * @param giftDetails 赠品名称、规格和份数或未展开汇总说明，不参与主商品的数量、金额及归一化计算；无赠品时为空文本
 * @param duplicate 历史记录查重预览，尚未执行查询时为空，不表示本次解析已保存
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
        ParsedNormalizationPreview normalization,
        Double purchaseQuantity,
        String giftDetails,
        ParsedDuplicatePreview duplicate
) {
    /** 截图识别备注的固定来源标记。 */
    private static final String OCR_NOTE_PREFIX = "【OCR解析】-";

    /**
     * 兼容未执行历史查重的候选创建方式，不猜测候选是否已经保存。
     *
     * @param productName 商品名称，允许为空
     * @param sku 规格，允许为空
     * @param price 实付金额，单位元，允许为空
     * @param quantity 单件含量，允许为空
     * @param unit 含量单位，允许为空
     * @param platform 平台，允许为空
     * @param owner 归属人，允许为空
     * @param purchaseDate 购买日期，允许为空
     * @param shopName 店铺，允许为空
     * @param note 解析备注，允许为空
     * @param sourceText 原文片段，允许为空
     * @param confidence 解析置信度，允许为空
     * @param warnings 解析警告
     * @param normalization 归一化预览，允许为空
     * @param purchaseQuantity 购买件数，允许为空
     * @param giftDetails 赠品详情，允许为空
     */
    public ParsedPurchaseCandidate(String productName, String sku, Double price, Double quantity,
                                   String unit, String platform, String owner, String purchaseDate,
                                   String shopName, String note, String sourceText, Double confidence,
                                   List<String> warnings, ParsedNormalizationPreview normalization,
                                   Double purchaseQuantity, String giftDetails) {
        this(productName, sku, price, quantity, unit, platform, owner, purchaseDate, shopName,
                note, sourceText, confidence, warnings, normalization, purchaseQuantity, giftDetails, null);
    }
    /**
     * 创建尚未补充归一化预览的 OCR 候选样本，兼容现有解析器及调用方。
     *
     * @param productName OCR 识别的商品名称
     * @param sku OCR 识别的商品规格
     * @param price OCR 识别的实付金额，单位为元
     * @param quantity 一个购买单位内的标准计量含量，不表示购买件数
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
                shopName, note, sourceText, confidence, warnings, null, null, null);
    }

    /**
     * 兼容尚未传入购买件数的既有调用方，不根据单件规格猜测购买件数。
     *
     * @param productName 商品名称，允许为空
     * @param sku 商品规格，允许为空
     * @param price 实付金额，单位元，允许为空
     * @param quantity 单件标准计量含量，允许为空
     * @param unit 单件含量单位，允许为空
     * @param platform 购买平台，允许为空
     * @param owner 订单归属人，允许为空
     * @param purchaseDate 购买日期，允许为空
     * @param shopName 店铺名称，允许为空
     * @param note 候选说明，允许为空
     * @param sourceText 截图原文片段，允许为空
     * @param confidence 解析置信度，取值为 0 到 1，允许为空
     * @param warnings 解析警告，允许为空列表
     * @param normalization 归一化预览，允许为空
     */
    public ParsedPurchaseCandidate(String productName, String sku, Double price, Double quantity,
                                   String unit, String platform, String owner, String purchaseDate,
                                   String shopName, String note, String sourceText, Double confidence,
                                   List<String> warnings, ParsedNormalizationPreview normalization) {
        this(productName, sku, price, quantity, unit, platform, owner, purchaseDate,
                shopName, note, sourceText, confidence, warnings, normalization, null, null);
    }

    /**
     * 兼容未提供赠品详情的既有调用方，保留原始备注和购买件数。
     *
     * @param productName 商品名称，允许为空
     * @param sku 商品规格，允许为空
     * @param price 实付金额，单位元，允许为空
     * @param quantity 单件标准计量含量，允许为空
     * @param unit 单件含量单位，允许为空
     * @param platform 购买平台，允许为空
     * @param owner 订单归属人，允许为空
     * @param purchaseDate 购买日期，允许为空
     * @param shopName 店铺名称，允许为空
     * @param note 候选备注，允许为空
     * @param sourceText 截图原文片段，允许为空
     * @param confidence 解析置信度，取值为 0 到 1，允许为空
     * @param warnings 解析警告，允许为空列表
     * @param normalization 归一化预览，允许为空
     * @param purchaseQuantity 明确购买件数，允许为空
     */
    public ParsedPurchaseCandidate(String productName, String sku, Double price, Double quantity,
                                   String unit, String platform, String owner, String purchaseDate,
                                   String shopName, String note, String sourceText, Double confidence,
                                   List<String> warnings, ParsedNormalizationPreview normalization,
                                   Double purchaseQuantity) {
        this(productName, sku, price, quantity, unit, platform, owner, purchaseDate,
                shopName, note, sourceText, confidence, warnings, normalization, purchaseQuantity, null);
    }

    /**
     * 生成统一的截图解析备注，只保留来源、归一化状态与赠品详情，不写入技术警告。
     *
     * @param normalized 是否已命中归一化规则
     * @param giftDetails 可见赠品详情或汇总信息，无赠品时允许为空
     * @return 可直接写入购买记录的精简备注，无外部副作用
     */
    public static String formatOcrNote(boolean normalized, String giftDetails) {
        String note = OCR_NOTE_PREFIX + (normalized ? "【已归一化】" : "【未归一化】");
        return giftDetails == null || giftDetails.isBlank() ? note : note + "；赠品：" + giftDetails.trim();
    }

    /**
     * 返回附带归一化预览的新候选样本，并同步精简备注中的状态，保留赠品及购买件数。
     *
     * @param normalization 归一化规则命中预览，不允许为空
     * @return 保留原字段并附带归一化预览的新候选样本
     */
    public ParsedPurchaseCandidate withNormalization(ParsedNormalizationPreview normalization) {
        return new ParsedPurchaseCandidate(productName, sku, price, quantity, unit,
                platform, owner, purchaseDate, shopName,
                formatOcrNote(normalization != null && normalization.matched(), giftDetails), sourceText,
                confidence, warnings, normalization, purchaseQuantity, giftDetails, duplicate);
    }

    /**
     * 补充只读查重状态及包含订单号的溯源片段，不改变可见备注及商品明细。
     *
     * @param preview 查重预览，不允许为空
     * @param preservedSourceText 保留订单号后的原文片段，最多 500 字符
     * @return 附带查重预览的新候选
     */
    public ParsedPurchaseCandidate withDuplicatePreview(ParsedDuplicatePreview preview, String preservedSourceText) {
        return new ParsedPurchaseCandidate(productName, sku, price, quantity, unit, platform, owner,
                purchaseDate, shopName, note, preservedSourceText, confidence, warnings, normalization,
                purchaseQuantity, giftDetails, preview);
    }
}
