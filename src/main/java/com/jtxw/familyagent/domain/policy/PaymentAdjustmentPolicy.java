package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 17:19:22
 * @Description: 支付金额折算规则，处理购物金、礼品卡和订单级金额分摊结果等导入金额口径。
 */
@Component
public class PaymentAdjustmentPolicy {
    public static final String SOURCE_PAID_AMOUNT = "paid_amount";
    public static final String SOURCE_PRODUCT_AMOUNT_ADJUSTED = "product_amount_adjusted";
    public static final String SOURCE_ORDER_AMOUNT_ANOMALY_FALLBACK = "order_amount_anomaly_fallback";
    public static final String REASON_PAYMENT_ADJUSTMENT = "PAYMENT_ADJUSTMENT";
    public static final String REASON_ORDER_AMOUNT_ANOMALY = "ORDER_AMOUNT_ANOMALY_REVIEW";
    /**
     * 金额异常最低容差，用于容忍平台展示、四舍五入和优惠拆分产生的小额差异。
     */
    private static final BigDecimal MIN_AMOUNT_ANOMALY_TOLERANCE = new BigDecimal("0.10");
    /**
     * 金额异常百分比容差，用于容忍商品金额 2% 以内的展示误差。
     */
    private static final BigDecimal AMOUNT_ANOMALY_RATIO = new BigDecimal("0.02");

    /**
     * 根据原始金额字段确定用于统计和单价计算的金额。
     *
     * <p>当前只处理保守场景：实付金额为 0 且商品金额大于 0 时，
     * 按商品金额加运费折算为统计金额，并创建待复核项。</p>
     *
     * @param record 原始订单记录
     * @return 金额折算结果
     */
    public PaymentAdjustmentResult adjust(RawPurchaseRecord record) {
        if (record.amountSourceOverride() != null && !record.amountSourceOverride().isBlank()) {
            return new PaymentAdjustmentResult(
                    record.totalAmount(),
                    record.amountSourceOverride(),
                    record.amountReviewRequired(),
                    record.amountReviewReasonCode(),
                    record.amountReviewReasonMessage()
            );
        }
        if (isAbnormallyGreaterThanLineAmount(record)) {
            return new PaymentAdjustmentResult(
                    record.totalAmount(),
                    SOURCE_ORDER_AMOUNT_ANOMALY_FALLBACK,
                    true,
                    REASON_ORDER_AMOUNT_ANOMALY,
                    "实付金额明显大于当前商品金额和运费，疑似订单级金额未被安全分摊；已默认排除价格基准，需人工复核。"
            );
        }
        if (isZero(record.paidAmount()) && isPositive(record.productAmount())) {
            // 购物金或礼品卡可能把现金实付冲为 0，此时用商品金额和运费恢复统计口径
            Double adjustedAmount = add(record.productAmount(), record.shippingFee());
            return new PaymentAdjustmentResult(
                    adjustedAmount,
                    SOURCE_PRODUCT_AMOUNT_ADJUSTED,
                    true,
                    REASON_PAYMENT_ADJUSTMENT,
                    "实付金额为 0，但商品金额大于 0，疑似购物金、礼品卡或组合支付抵扣；已按商品金额和运费折算为统计金额，需人工确认。"
            );
        }
        return new PaymentAdjustmentResult(record.totalAmount(), SOURCE_PAID_AMOUNT, false, null, null);
    }

    /**
     * 判断实付金额是否明显大于商品金额和运费之和。
     *
     * <p>使用动态容差：取最低 0.10 元和商品金额 2% 的较大值，
     * 容忍平台展示、四舍五入、优惠拆分等小额差异，
     * 仅在差额超过动态阈值时判定为订单级金额异常。</p>
     */
    private boolean isAbnormallyGreaterThanLineAmount(RawPurchaseRecord record) {
        if (!isPositive(record.productAmount()) || record.paidAmount() == null) {
            return false;
        }
        BigDecimal paidAmount = BigDecimal.valueOf(record.paidAmount());
        BigDecimal productAmount = BigDecimal.valueOf(record.productAmount());
        BigDecimal shippingFee = record.shippingFee() == null ? BigDecimal.ZERO : BigDecimal.valueOf(record.shippingFee());
        BigDecimal difference = paidAmount.subtract(productAmount).subtract(shippingFee);
        BigDecimal dynamicTolerance = amountAnomalyTolerance(productAmount);
        return difference.compareTo(dynamicTolerance) > 0;
    }

    /**
     * 计算金额异常动态容差，取最低容差和商品金额百分比容差的较大值。
     *
     * @param productAmount 商品金额
     * @return 动态容差值
     */
    private BigDecimal amountAnomalyTolerance(BigDecimal productAmount) {
        BigDecimal ratioTolerance = productAmount.multiply(AMOUNT_ANOMALY_RATIO);
        return ratioTolerance.compareTo(MIN_AMOUNT_ANOMALY_TOLERANCE) > 0
                ? ratioTolerance
                : MIN_AMOUNT_ANOMALY_TOLERANCE;
    }

    private boolean isZero(Double value) {
        return value != null && BigDecimal.valueOf(value).compareTo(BigDecimal.ZERO) == 0;
    }

    private boolean isPositive(Double value) {
        return value != null && BigDecimal.valueOf(value).compareTo(BigDecimal.ZERO) > 0;
    }

    private Double add(Double productAmount, Double shippingFee) {
        BigDecimal product = productAmount == null ? BigDecimal.ZERO : BigDecimal.valueOf(productAmount);
        BigDecimal shipping = shippingFee == null ? BigDecimal.ZERO : BigDecimal.valueOf(shippingFee);
        return product.add(shipping).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 金额折算结果，描述最终统计金额及是否需要人工复核。
     */
    public static class PaymentAdjustmentResult {
        private final Double totalAmount;
        private final String amountSource;
        private final boolean reviewRequired;
        private final String reviewReasonCode;
        private final String reviewReasonMessage;

        public PaymentAdjustmentResult(Double totalAmount,
                                       String amountSource,
                                       boolean reviewRequired,
                                       String reviewReasonCode,
                                       String reviewReasonMessage) {
            this.totalAmount = totalAmount;
            this.amountSource = amountSource;
            this.reviewRequired = reviewRequired;
            this.reviewReasonCode = reviewReasonCode;
            this.reviewReasonMessage = reviewReasonMessage;
        }

        public Double totalAmount() {
            return totalAmount;
        }

        public String amountSource() {
            return amountSource;
        }

        public boolean reviewRequired() {
            return reviewRequired;
        }

        public String reviewReasonCode() {
            return reviewReasonCode;
        }

        public String reviewReasonMessage() {
            return reviewReasonMessage;
        }
    }
}
