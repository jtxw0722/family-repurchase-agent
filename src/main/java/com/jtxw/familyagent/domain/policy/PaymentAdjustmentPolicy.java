package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * @Author: jtxw
 * @Date: 2026/05/20/01:29
 * @Description: 支付金额折算规则，处理购物金、礼品卡等导致实付金额异常的订单。
 */
@Component
public class PaymentAdjustmentPolicy {
    public static final String SOURCE_PAID_AMOUNT = "paid_amount";
    public static final String SOURCE_PRODUCT_AMOUNT_ADJUSTED = "product_amount_adjusted";
    public static final String REASON_PAYMENT_ADJUSTMENT = "PAYMENT_ADJUSTMENT";

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
