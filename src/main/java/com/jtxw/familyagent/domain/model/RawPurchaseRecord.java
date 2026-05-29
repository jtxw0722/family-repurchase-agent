package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/01:05
 * @Description: CSV 原始订单记录对象，保留导入阶段解析出的基础字段。
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
     * 交易币种
     */
    private final String currency;

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
        this.orderTime = orderTime;
        this.platform = platform;
        this.owner = owner;
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
        this.currency = currency;
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

    public String currency() {
        return currency;
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

    public String getCurrency() {
        return currency;
    }
}
