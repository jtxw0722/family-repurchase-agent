package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/05/20/01:29
 * @Description: 支付金额折算规则测试，覆盖购物金和礼品卡抵扣场景。
 */
class PaymentAdjustmentPolicyTest {
    @Test
    void shouldAdjustZeroPaidOrderWithProductAmount() {
        RawPurchaseRecord record = record(0D, 36.8D, 3.2D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = new PaymentAdjustmentPolicy().adjust(record);

        assertThat(result.totalAmount()).isEqualTo(40D);
        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_PRODUCT_AMOUNT_ADJUSTED);
        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.reviewReasonCode()).isEqualTo(PaymentAdjustmentPolicy.REASON_PAYMENT_ADJUSTMENT);
    }

    @Test
    void shouldKeepPaidAmountForNormalOrder() {
        RawPurchaseRecord record = record(76.96D, 80D, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = new PaymentAdjustmentPolicy().adjust(record);

        assertThat(result.totalAmount()).isEqualTo(76.96D);
        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_PAID_AMOUNT);
        assertThat(result.reviewRequired()).isFalse();
    }

    @Test
    void shouldKeepZeroAmountWhenProductAmountIsMissing() {
        RawPurchaseRecord record = record(0D, null, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = new PaymentAdjustmentPolicy().adjust(record);

        assertThat(result.totalAmount()).isZero();
        assertThat(result.reviewRequired()).isFalse();
    }

    private RawPurchaseRecord record(Double paidAmount, Double productAmount, Double shippingFee) {
        return new RawPurchaseRecord(
                "2026-05-01", "taobao", "JTXW", "乳铁蛋白粉", "100g",
                "", "", 1D, "件", paidAmount, productAmount, paidAmount, shippingFee, "CNY"
        );
    }
}
