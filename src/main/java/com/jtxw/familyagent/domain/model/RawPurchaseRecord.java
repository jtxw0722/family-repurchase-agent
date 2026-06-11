package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 17:19:22
 * @Description: 原始订单记录实体，保留文件导入阶段解析出的订单分组、商品、金额和规格复核字段。
 */
public class RawPurchaseRecord {
    /**
     * 订单发生时间，来自导入文件
     */
    private final String orderTime;
    /**
     * 购买平台，例如 taobao、jd
     */
    private final String platform;
    /**
     * 家庭成员或数据归属人标识
     */
    private final String owner;
    /**
     * 订单分组键，优先来自订单编号；为空时表示无法可靠识别同一订单下的多条商品行。
     */
    private final String orderGroupKey;
    /**
     * 导入文件中的原始商品名称
     */
    private final String productName;
    /**
     * 商品规格或 SKU 文本
     */
    private final String sku;
    /**
     * 一级商品分类
     */
    private final String category;
    /**
     * 二级商品分类
     */
    private final String subCategory;
    /**
     * 商品数量
     */
    private final Double quantity;
    /**
     * 商品数量单位
     */
    private final String unit;
    /**
     * 当前用于统计的总金额，导入阶段默认等于实付金额
     */
    private final Double totalAmount;
    /**
     * 商品金额，未扣除购物金、礼品卡等支付抵扣
     */
    private final Double productAmount;
    /**
     * 导入文件中的实付金额
     */
    private final Double paidAmount;
    /**
     * 导入文件中的运费
     */
    private final Double shippingFee;
    /**
     * 实付金额是否已经包含运费，true 表示 totalAmount 应直接使用分摊后的 paidAmount，默认 false。
     */
    private final boolean paidAmountIncludesShipping;
    /**
     * 金额来源覆盖值，非空时表示导入前已完成订单级金额分摊或分摊失败复核标记。
     */
    private final String amountSourceOverride;
    /**
     * 金额分摊是否需要人工复核，true 表示订单级金额无法安全分摊，默认 false。
     */
    private final boolean amountReviewRequired;
    /**
     * 金额分摊复核原因码，amountReviewRequired 为 true 时不允许为空。
     */
    private final String amountReviewReasonCode;
    /**
     * 金额分摊复核提示文案，amountReviewRequired 为 true 时用于写入 review_items。
     */
    private final String amountReviewReasonMessage;
    /**
     * 交易币种
     */
    private final String currency;
    private final boolean specReviewRequired;
    private final String specReviewReasonCode;
    private final String specReviewReasonMessage;

    public RawPurchaseRecord(String orderTime,
                             String platform,
                             String owner,
                             String productName,
                             String sku,
                             String category,
                             String subCategory,
                             Double quantity,
                             String unit,
                             Double totalAmount,
                             String currency) {
        this(orderTime, platform, owner, productName, sku, category, subCategory, quantity, unit,
                totalAmount, totalAmount, totalAmount, null, currency);
    }

    public RawPurchaseRecord(String orderTime,
                             String platform,
                             String owner,
                             String productName,
                             String sku,
                             String category,
                             String subCategory,
                             Double quantity,
                             String unit,
                             Double totalAmount,
                             Double productAmount,
                             Double paidAmount,
                             Double shippingFee,
                             String currency) {
        this(orderTime, platform, owner, productName, sku, category, subCategory, quantity, unit,
                totalAmount, productAmount, paidAmount, shippingFee, currency, false, null, null);
    }

    public RawPurchaseRecord(String orderTime,
                             String platform,
                             String owner,
                             String productName,
                             String sku,
                             String category,
                             String subCategory,
                             Double quantity,
                             String unit,
                             Double totalAmount,
                             Double productAmount,
                             Double paidAmount,
                             Double shippingFee,
                             String currency,
                             boolean specReviewRequired,
                             String specReviewReasonCode,
                             String specReviewReasonMessage) {
        this(orderTime, platform, owner, null, productName, sku, category, subCategory, quantity, unit,
                totalAmount, productAmount, paidAmount, shippingFee, currency, specReviewRequired,
                specReviewReasonCode, specReviewReasonMessage, false, null, false, null, null);
    }

    public RawPurchaseRecord(String orderTime,
                             String platform,
                             String owner,
                             String orderGroupKey,
                             String productName,
                             String sku,
                             String category,
                             String subCategory,
                             Double quantity,
                             String unit,
                             Double totalAmount,
                             Double productAmount,
                             Double paidAmount,
                             Double shippingFee,
                             String currency,
                             boolean specReviewRequired,
                             String specReviewReasonCode,
                             String specReviewReasonMessage,
                             String amountSourceOverride,
                             boolean amountReviewRequired,
                             String amountReviewReasonCode,
                             String amountReviewReasonMessage) {
        this(orderTime, platform, owner, orderGroupKey, productName, sku, category, subCategory, quantity, unit,
                totalAmount, productAmount, paidAmount, shippingFee, currency, specReviewRequired,
                specReviewReasonCode, specReviewReasonMessage, false, amountSourceOverride, amountReviewRequired,
                amountReviewReasonCode, amountReviewReasonMessage);
    }

    public RawPurchaseRecord(String orderTime,
                             String platform,
                             String owner,
                             String orderGroupKey,
                             String productName,
                             String sku,
                             String category,
                             String subCategory,
                             Double quantity,
                             String unit,
                             Double totalAmount,
                             Double productAmount,
                             Double paidAmount,
                             Double shippingFee,
                             String currency,
                             boolean specReviewRequired,
                             String specReviewReasonCode,
                             String specReviewReasonMessage,
                             boolean paidAmountIncludesShipping,
                             String amountSourceOverride,
                             boolean amountReviewRequired,
                             String amountReviewReasonCode,
                             String amountReviewReasonMessage) {
        this.orderTime = orderTime;
        this.platform = platform;
        this.owner = owner;
        this.orderGroupKey = orderGroupKey;
        this.productName = productName;
        this.sku = sku;
        this.category = category;
        this.subCategory = subCategory;
        this.quantity = quantity;
        this.unit = unit;
        this.totalAmount = totalAmount;
        this.productAmount = productAmount;
        this.paidAmount = paidAmount;
        this.shippingFee = shippingFee;
        this.paidAmountIncludesShipping = paidAmountIncludesShipping;
        this.amountSourceOverride = amountSourceOverride;
        this.amountReviewRequired = amountReviewRequired;
        this.amountReviewReasonCode = amountReviewReasonCode;
        this.amountReviewReasonMessage = amountReviewReasonMessage;
        this.currency = currency;
        this.specReviewRequired = specReviewRequired;
        this.specReviewReasonCode = specReviewReasonCode;
        this.specReviewReasonMessage = specReviewReasonMessage;
    }

    /**
     * 创建金额字段已被订单级分摊结果覆盖的新原始记录。
     *
     * @param totalAmount          当前商品行最终统计金额，单位为元
     * @param paidAmount           当前商品行分摊后的实付金额，单位为元
     * @param shippingFee          当前商品行分摊后的运费，单位为元
     * @param amountSourceOverride 金额来源覆盖值，用于区分订单级金额分摊和原始行级实付
     * @return 金额字段更新后的原始订单记录
     */
    public RawPurchaseRecord withAllocatedAmount(Double totalAmount,
                                                 Double paidAmount,
                                                 Double shippingFee,
                                                 String amountSourceOverride) {
        return new RawPurchaseRecord(orderTime, platform, owner, orderGroupKey, productName, sku, category,
                subCategory, quantity, unit, totalAmount, productAmount, paidAmount, shippingFee, currency,
                specReviewRequired, specReviewReasonCode, specReviewReasonMessage, paidAmountIncludesShipping, amountSourceOverride,
                false, null, null);
    }

    /**
     * 创建需要人工复核金额分摊失败原因的新原始记录。
     *
     * @param amountSourceOverride 金额来源覆盖值，表示当前记录沿用原始金额但分摊失败
     * @param reviewReasonCode     复核原因码
     * @param reviewReasonMessage  复核提示文案
     * @return 带金额复核标记的原始订单记录
     */
    public RawPurchaseRecord withAmountReview(String amountSourceOverride,
                                              String reviewReasonCode,
                                              String reviewReasonMessage) {
        return new RawPurchaseRecord(orderTime, platform, owner, orderGroupKey, productName, sku, category,
                subCategory, quantity, unit, totalAmount, productAmount, paidAmount, shippingFee, currency,
                specReviewRequired, specReviewReasonCode, specReviewReasonMessage, paidAmountIncludesShipping, amountSourceOverride,
                true, reviewReasonCode, reviewReasonMessage);
    }

    /**
     * 创建实付金额已含运费的新原始记录。
     *
     * @return 带实付含运费标记的原始订单记录
     */
    public RawPurchaseRecord withPaidAmountIncludesShipping() {
        return new RawPurchaseRecord(orderTime, platform, owner, orderGroupKey, productName, sku, category,
                subCategory, quantity, unit, totalAmount, productAmount, paidAmount, shippingFee, currency,
                specReviewRequired, specReviewReasonCode, specReviewReasonMessage, true, amountSourceOverride,
                amountReviewRequired, amountReviewReasonCode, amountReviewReasonMessage);
    }

    public String orderTime() {
        return orderTime;
    }

    public String platform() {
        return platform;
    }

    public String owner() {
        return owner;
    }

    public String orderGroupKey() {
        return orderGroupKey;
    }

    public String productName() {
        return productName;
    }

    public String sku() {
        return sku;
    }

    public String category() {
        return category;
    }

    public String subCategory() {
        return subCategory;
    }

    public Double quantity() {
        return quantity;
    }

    public String unit() {
        return unit;
    }

    public Double totalAmount() {
        return totalAmount;
    }

    public Double productAmount() {
        return productAmount;
    }

    public Double paidAmount() {
        return paidAmount;
    }

    public Double shippingFee() {
        return shippingFee;
    }

    public boolean paidAmountIncludesShipping() {
        return paidAmountIncludesShipping;
    }

    public String amountSourceOverride() {
        return amountSourceOverride;
    }

    public boolean amountReviewRequired() {
        return amountReviewRequired;
    }

    public String amountReviewReasonCode() {
        return amountReviewReasonCode;
    }

    public String amountReviewReasonMessage() {
        return amountReviewReasonMessage;
    }

    public String currency() {
        return currency;
    }

    public boolean specReviewRequired() {
        return specReviewRequired;
    }

    public String specReviewReasonCode() {
        return specReviewReasonCode;
    }

    public String specReviewReasonMessage() {
        return specReviewReasonMessage;
    }

    public String getOrderTime() {
        return orderTime;
    }

    public String getPlatform() {
        return platform;
    }

    public String getOwner() {
        return owner;
    }

    public String getOrderGroupKey() {
        return orderGroupKey;
    }

    public String getProductName() {
        return productName;
    }

    public String getSku() {
        return sku;
    }

    public String getCategory() {
        return category;
    }

    public String getSubCategory() {
        return subCategory;
    }

    public Double getQuantity() {
        return quantity;
    }

    public String getUnit() {
        return unit;
    }

    public Double getTotalAmount() {
        return totalAmount;
    }

    public Double getProductAmount() {
        return productAmount;
    }

    public Double getPaidAmount() {
        return paidAmount;
    }

    public Double getShippingFee() {
        return shippingFee;
    }

    public boolean isPaidAmountIncludesShipping() {
        return paidAmountIncludesShipping;
    }

    public String getAmountSourceOverride() {
        return amountSourceOverride;
    }

    public boolean isAmountReviewRequired() {
        return amountReviewRequired;
    }

    public String getAmountReviewReasonCode() {
        return amountReviewReasonCode;
    }

    public String getAmountReviewReasonMessage() {
        return amountReviewReasonMessage;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isSpecReviewRequired() {
        return specReviewRequired;
    }

    public String getSpecReviewReasonCode() {
        return specReviewReasonCode;
    }

    public String getSpecReviewReasonMessage() {
        return specReviewReasonMessage;
    }
}
