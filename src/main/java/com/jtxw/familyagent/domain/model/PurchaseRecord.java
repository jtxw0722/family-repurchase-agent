package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/01:18
 * @Description: 标准化后的购买记录实体，表示可持久化和统计的订单明细。
 */
public class PurchaseRecord {
    /**
     * 购买记录主键 ID
     */
    private final Long id;
    /**
     * 所属导入批次 ID
     */
    private final Long batchId;
    /**
     * 订单发生时间
     */
    private final String orderTime;
    /**
     * 购买平台
     */
    private final String platform;
    /**
     * 家庭成员或数据归属人标识
     */
    private final String owner;
    /**
     * 原始商品名称
     */
    private final String productName;
    /**
     * 归一化后的商品名称
     */
    private final String normalizedName;
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
     * 当前用于统计的总金额
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
     * 统计金额来源，例如 paid_amount 或 product_amount_adjusted
     */
    private final String amountSource;
    /**
     * 按数量和单位折算后的单位价格
     */
    private final Double unitPrice;
    /**
     * 交易币种
     */
    private final String currency;
    /**
     * 统计决策，include 表示纳入统计，exclude 表示排除
     */
    private final String decision;
    /**
     * 是否被判定为重复订单
     */
    private final boolean duplicate;
    /**
     * 去重状态，例如 unique 或 duplicate
     */
    private final String dedupeStatus;
    /**
     * 来源文件路径
     */
    private final String sourceFile;
    /**
     * 记录创建时间
     */
    private final String createdAt;

    public PurchaseRecord(Long id,
                          Long batchId,
                          String orderTime,
                          String platform,
                          String owner,
                          String productName,
                          String normalizedName,
                          String sku,
                          String category,
                          String subCategory,
                          Double quantity,
                          String unit,
                          Double totalAmount,
                          Double unitPrice,
                          String currency,
                          String decision,
                          boolean duplicate,
                          String dedupeStatus,
                          String sourceFile,
                          String createdAt) {
        this(id, batchId, orderTime, platform, owner, productName, normalizedName, sku, category, subCategory,
                quantity, unit, totalAmount, totalAmount, totalAmount, null, "paid_amount", unitPrice, currency,
                decision, duplicate, dedupeStatus, sourceFile, createdAt);
    }

    public PurchaseRecord(Long id,
                          Long batchId,
                          String orderTime,
                          String platform,
                          String owner,
                          String productName,
                          String normalizedName,
                          String sku,
                          String category,
                          String subCategory,
                          Double quantity,
                          String unit,
                          Double totalAmount,
                          Double productAmount,
                          Double paidAmount,
                          Double shippingFee,
                          String amountSource,
                          Double unitPrice,
                          String currency,
                          String decision,
                          boolean duplicate,
                          String dedupeStatus,
                          String sourceFile,
                          String createdAt) {
        this.id = id;
        this.batchId = batchId;
        this.orderTime = orderTime;
        this.platform = platform;
        this.owner = owner;
        this.productName = productName;
        this.normalizedName = normalizedName;
        this.sku = sku;
        this.category = category;
        this.subCategory = subCategory;
        this.quantity = quantity;
        this.unit = unit;
        this.totalAmount = totalAmount;
        this.productAmount = productAmount;
        this.paidAmount = paidAmount;
        this.shippingFee = shippingFee;
        this.amountSource = amountSource == null || amountSource.isBlank() ? "paid_amount" : amountSource;
        this.unitPrice = unitPrice;
        this.currency = currency;
        this.decision = decision;
        this.duplicate = duplicate;
        this.dedupeStatus = dedupeStatus;
        this.sourceFile = sourceFile;
        this.createdAt = createdAt;
    }

    public Long id() {
        return id;
    }

    public Long batchId() {
        return batchId;
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

    public String normalizedName() {
        return normalizedName;
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

    public String amountSource() {
        return amountSource;
    }

    public Double unitPrice() {
        return unitPrice;
    }

    public String currency() {
        return currency;
    }

    public String decision() {
        return decision;
    }

    public boolean duplicate() {
        return duplicate;
    }

    public String dedupeStatus() {
        return dedupeStatus;
    }

    public String sourceFile() {
        return sourceFile;
    }

    public String createdAt() {
        return createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getBatchId() {
        return batchId;
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

    public String getNormalizedName() {
        return normalizedName;
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

    public String getAmountSource() {
        return amountSource;
    }

    public Double getUnitPrice() {
        return unitPrice;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDecision() {
        return decision;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public String getDedupeStatus() {
        return dedupeStatus;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}
