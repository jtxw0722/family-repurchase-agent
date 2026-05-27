package com.jtxw.familyagent.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @Author: jtxw
 * @Date: 2026/05/21/14:37
 * @Description: 待复核详情视图，组合复核事项和关联订单的关键信息。
 */
@Schema(description = "待复核详情，包含复核原因和关联订单信息")
public class ReviewItemDetail {
    /**
     * 复核项主键 ID
     */
    @Schema(description = "复核项 ID", example = "359")
    private final Long id;
    /**
     * 关联消费记录 ID
     */
    @Schema(description = "关联消费记录 ID", example = "1427")
    private final Long recordId;
    /**
     * 复核原因编码
     */
    @Schema(description = "复核原因编码", example = "PAYMENT_ADJUSTMENT")
    private final String reasonCode;
    /**
     * 复核原因说明
     */
    @Schema(description = "复核原因说明")
    private final String reasonMessage;
    /**
     * 复核状态
     */
    @Schema(description = "复核状态", example = "pending")
    private final String status;
    /**
     * 人工复核动作
     */
    @Schema(description = "人工复核动作", example = "include", nullable = true)
    private final String reviewDecision;
    /**
     * 人工复核备注
     */
    @Schema(description = "人工复核备注", nullable = true)
    private final String reviewNote;
    /**
     * 复核项创建时间
     */
    @Schema(description = "复核项创建时间")
    private final String createdAt;
    /**
     * 复核项处理完成时间
     */
    @Schema(description = "复核项处理完成时间", nullable = true)
    private final String resolvedAt;
    /**
     * 所属导入批次 ID
     */
    @Schema(description = "所属导入批次 ID", example = "10", nullable = true)
    private final Long batchId;
    /**
     * 订单发生时间
     */
    @Schema(description = "订单发生时间", example = "2026-05-01 12:30:00", nullable = true)
    private final String orderTime;
    /**
     * 购买平台
     */
    @Schema(description = "购买平台", example = "taobao", nullable = true)
    private final String platform;
    /**
     * 订单归属人
     */
    @Schema(description = "订单归属人", example = "JTXW", nullable = true)
    private final String owner;
    /**
     * 原始商品名称
     */
    @Schema(description = "原始商品名称", example = "混合猫砂 12kg", nullable = true)
    private final String productName;
    /**
     * 归一化商品名称
     */
    @Schema(description = "归一化商品名称", example = "猫砂", nullable = true)
    private final String normalizedName;
    /**
     * 商品规格或 SKU
     */
    @Schema(description = "商品规格或 SKU", example = "12kg", nullable = true)
    private final String sku;
    /**
     * 一级消费分类
     */
    @Schema(description = "一级消费分类", example = "宠物用品", nullable = true)
    private final String category;
    /**
     * 二级消费分类
     */
    @Schema(description = "二级消费分类", example = "猫砂", nullable = true)
    private final String subCategory;
    /**
     * 商品数量
     */
    @Schema(description = "商品数量", example = "12", nullable = true)
    private final Double quantity;
    /**
     * 数量单位
     */
    @Schema(description = "数量单位", example = "kg", nullable = true)
    private final String unit;
    /**
     * 当前用于统计的总金额
     */
    @Schema(description = "当前用于统计的总金额", example = "89.00", nullable = true)
    private final Double totalAmount;
    /**
     * 商品金额
     */
    @Schema(description = "商品金额，未扣除购物金、礼品卡等支付抵扣", example = "89.00", nullable = true)
    private final Double productAmount;
    /**
     * 导入文件中的实付金额
     */
    @Schema(description = "导入文件中的实付金额", example = "0.00", nullable = true)
    private final Double paidAmount;
    /**
     * 导入文件中的运费
     */
    @Schema(description = "导入文件中的运费", example = "0.00", nullable = true)
    private final Double shippingFee;
    /**
     * 统计金额来源
     */
    @Schema(description = "统计金额来源", example = "product_amount_adjusted", nullable = true)
    private final String amountSource;
    /**
     * 单位价格
     */
    @Schema(description = "单位价格", example = "7.42", nullable = true)
    private final Double unitPrice;
    /**
     * 交易币种
     */
    @Schema(description = "交易币种", example = "CNY", nullable = true)
    private final String currency;
    /**
     * 当前统计决策
     */
    @Schema(description = "当前统计决策", example = "include", nullable = true)
    private final String decision;
    /**
     * 是否被识别为重复订单
     */
    @Schema(description = "是否被识别为重复订单", example = "false")
    private final boolean duplicate;
    /**
     * 去重状态
     */
    @Schema(description = "去重状态", example = "unique", nullable = true)
    private final String dedupeStatus;
    /**
     * 来源文件路径
     */
    @Schema(description = "来源文件路径", example = "订单数据.csv", nullable = true)
    private final String sourceFile;
    /**
     * 消费记录创建时间
     */
    @Schema(description = "消费记录创建时间", nullable = true)
    private final String recordCreatedAt;

    public ReviewItemDetail(Long id,
                            Long recordId,
                            String reasonCode,
                            String reasonMessage,
                            String status,
                            String reviewDecision,
                            String reviewNote,
                            String createdAt,
                            String resolvedAt,
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
                            String recordCreatedAt) {
        this.id = id;
        this.recordId = recordId;
        this.reasonCode = reasonCode;
        this.reasonMessage = reasonMessage;
        this.status = status;
        this.reviewDecision = reviewDecision;
        this.reviewNote = reviewNote;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
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
        this.amountSource = amountSource;
        this.unitPrice = unitPrice;
        this.currency = currency;
        this.decision = decision;
        this.duplicate = duplicate;
        this.dedupeStatus = dedupeStatus;
        this.sourceFile = sourceFile;
        this.recordCreatedAt = recordCreatedAt;
    }

    public Long id() {
        return id;
    }

    public Long recordId() {
        return recordId;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String reasonMessage() {
        return reasonMessage;
    }

    public String status() {
        return status;
    }

    public String reviewDecision() {
        return reviewDecision;
    }

    public String reviewNote() {
        return reviewNote;
    }

    public String createdAt() {
        return createdAt;
    }

    public String resolvedAt() {
        return resolvedAt;
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

    public String recordCreatedAt() {
        return recordCreatedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getRecordId() {
        return recordId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonMessage() {
        return reasonMessage;
    }

    public String getStatus() {
        return status;
    }

    public String getReviewDecision() {
        return reviewDecision;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getResolvedAt() {
        return resolvedAt;
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

    public String getRecordCreatedAt() {
        return recordCreatedAt;
    }
}
