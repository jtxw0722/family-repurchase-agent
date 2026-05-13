package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/01:18
 * @Description: 标准化后的消费记录实体，表示可持久化和统计的订单明细。
 */
public class PurchaseRecord {
    private final Long id;
    private final Long batchId;
    private final String orderTime;
    private final String platform;
    private final String owner;
    private final String productName;
    private final String normalizedName;
    private final String sku;
    private final String category;
    private final String subCategory;
    private final Double quantity;
    private final String unit;
    private final Double totalAmount;
    private final Double unitPrice;
    private final String currency;
    private final String decision;
    private final boolean duplicate;
    private final String dedupeStatus;
    private final String sourceFile;
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
