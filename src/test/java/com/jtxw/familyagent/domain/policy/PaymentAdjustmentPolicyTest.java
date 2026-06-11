package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/05/20/01:29
 * @Description: 支付金额折算规则测试，覆盖购物金、礼品卡抵扣和金额异常动态容差场景。
 */
class PaymentAdjustmentPolicyTest {

    private final PaymentAdjustmentPolicy policy = new PaymentAdjustmentPolicy();

    // ========== 已有测试：0 元实付和正常订单 ==========

    @Test
    void shouldAdjustZeroPaidOrderWithProductAmount() {
        RawPurchaseRecord record = record(0D, 36.8D, 3.2D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.totalAmount()).isEqualTo(40D);
        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_PRODUCT_AMOUNT_ADJUSTED);
        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.reviewReasonCode()).isEqualTo(PaymentAdjustmentPolicy.REASON_PAYMENT_ADJUSTMENT);
    }

    @Test
    void shouldKeepPaidAmountForNormalOrder() {
        RawPurchaseRecord record = record(76.96D, 80D, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.totalAmount()).isEqualTo(76.96D);
        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_PAID_AMOUNT);
        assertThat(result.reviewRequired()).isFalse();
    }

    @Test
    void shouldKeepZeroAmountWhenProductAmountIsMissing() {
        RawPurchaseRecord record = record(0D, null, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.totalAmount()).isZero();
        assertThat(result.reviewRequired()).isFalse();
    }

    // ========== P2-1：动态容差测试 ==========

    @Test
    void shouldNotTriggerAnomalyForSmallDifference() {
        // 差额 0.02 元，低于最低容差 0.10 元
        RawPurchaseRecord record = record(37.42D, 37.40D, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_PAID_AMOUNT);
        assertThat(result.reviewRequired()).isFalse();
        assertThat(result.reviewReasonCode()).isNull();
    }

    @Test
    void shouldNotTriggerAnomalyForDifferenceWithin2Percent() {
        // 差额 2.31 元，约 1.24%，低于 2% 容差
        RawPurchaseRecord record = record(188.71D, 186.40D, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_PAID_AMOUNT);
        assertThat(result.reviewRequired()).isFalse();
        assertThat(result.reviewReasonCode()).isNull();
    }

    @Test
    void shouldNotTriggerAnomalyAtExact2PercentBoundary() {
        // 差额 2.00 元，等于 2%，不应触发（条件是 > 阈值）
        RawPurchaseRecord record = record(102.00D, 100.00D, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_PAID_AMOUNT);
        assertThat(result.reviewRequired()).isFalse();
        assertThat(result.reviewReasonCode()).isNull();
    }

    @Test
    void shouldTriggerAnomalyBeyond2PercentBoundary() {
        // 差额 2.01 元，超过 2% 阈值，应触发
        RawPurchaseRecord record = record(102.01D, 100.00D, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_ORDER_AMOUNT_ANOMALY_FALLBACK);
        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.reviewReasonCode()).isEqualTo(PaymentAdjustmentPolicy.REASON_ORDER_AMOUNT_ANOMALY);
    }

    @Test
    void shouldNotTriggerAnomalyAtExactMinTolerance() {
        // 差额 0.10 元，等于最低容差，不应触发（条件是 > 阈值）
        RawPurchaseRecord record = record(1.10D, 1.00D, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_PAID_AMOUNT);
        assertThat(result.reviewRequired()).isFalse();
        assertThat(result.reviewReasonCode()).isNull();
    }

    @Test
    void shouldTriggerAnomalyBeyondMinTolerance() {
        // 差额 0.11 元，超过最低容差 0.10 元，应触发
        RawPurchaseRecord record = record(1.11D, 1.00D, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_ORDER_AMOUNT_ANOMALY_FALLBACK);
        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.reviewReasonCode()).isEqualTo(PaymentAdjustmentPolicy.REASON_ORDER_AMOUNT_ANOMALY);
    }

    @Test
    void shouldTriggerAnomalyForRealOrderLevelAmountPollution() {
        // 真实订单级金额污染：差额 241.99 元，远超动态阈值
        RawPurchaseRecord record = record(320.84D, 78.85D, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_ORDER_AMOUNT_ANOMALY_FALLBACK);
        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.reviewReasonCode()).isEqualTo(PaymentAdjustmentPolicy.REASON_ORDER_AMOUNT_ANOMALY);
    }

    @Test
    void shouldRespectAmountSourceOverridePriority() {
        // amountSourceOverride 非空时，应优先返回 override 结果，不执行 anomaly 判断
        RawPurchaseRecord record = new RawPurchaseRecord(
                "2026-05-01", "taobao", "JTXW", "ORD001", "乳铁蛋白粉", "100g",
                "", "", 1D, "件", 78.85D, 78.85D, 320.84D, 0D, "CNY",
                false, null, null,
                false, "allocated_order_amount", false, null, null
        );

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.amountSource()).isEqualTo("allocated_order_amount");
        assertThat(result.reviewRequired()).isFalse();
        assertThat(result.reviewReasonCode()).isNull();
    }

    @Test
    void shouldHandleZeroPaidAmountWithoutTriggeringAnomaly() {
        // paidAmount=0 走 PAYMENT_ADJUSTMENT 分支，不走 ORDER_AMOUNT_ANOMALY_REVIEW
        RawPurchaseRecord record = record(0D, 100.00D, 0D);

        PaymentAdjustmentPolicy.PaymentAdjustmentResult result = policy.adjust(record);

        assertThat(result.amountSource()).isEqualTo(PaymentAdjustmentPolicy.SOURCE_PRODUCT_AMOUNT_ADJUSTED);
        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.reviewReasonCode()).isEqualTo(PaymentAdjustmentPolicy.REASON_PAYMENT_ADJUSTMENT);
    }

    private RawPurchaseRecord record(Double paidAmount, Double productAmount, Double shippingFee) {
        return new RawPurchaseRecord(
                "2026-05-01", "taobao", "JTXW", "乳铁蛋白粉", "100g",
                "", "", 1D, "件", paidAmount, productAmount, paidAmount, shippingFee, "CNY"
        );
    }
}
