package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 17:19:22
 * @Description: 订单金额分摊策略测试，覆盖订单级实付金额和运费按商品行金额比例分摊的核心场景。
 */
class OrderAmountAllocationPolicyTest {

    private final OrderAmountAllocationPolicy policy = new OrderAmountAllocationPolicy();

    @Test
    void shouldAllocateSingleProductOrderPaidAndShippingToLineAmount() {
        RawPurchaseRecord record = record("order-1", "猫砂", "10kg", 100D, 90D, 10D, 90D);

        RawPurchaseRecord allocatedRecord = policy.allocate(List.of(record)).get(0);
        Double unitPrice = new UnitPriceCalculator().calculate(allocatedRecord.totalAmount(), 10D);

        assertThat(allocatedRecord.paidAmount()).isEqualTo(90D);
        assertThat(allocatedRecord.shippingFee()).isEqualTo(10D);
        assertThat(allocatedRecord.totalAmount()).isEqualTo(100D);
        assertThat(unitPrice).isEqualTo(10D);
        assertThat(allocatedRecord.amountSourceOverride())
                .isEqualTo(OrderAmountAllocationPolicy.SOURCE_ALLOCATED_ORDER_AMOUNT);
    }

    @Test
    void shouldAllocateMultiProductOrderByProductAmountRatio() {
        List<RawPurchaseRecord> records = List.of(
                record("order-2", "A 商品", "100g", 100D, 360D, 40D, 360D),
                record("order-2", "B 商品", "300g", 300D, 360D, 40D, 360D)
        );

        List<RawPurchaseRecord> allocatedRecords = policy.allocate(records);

        assertThat(allocatedRecords.get(0).paidAmount()).isEqualTo(90D);
        assertThat(allocatedRecords.get(0).shippingFee()).isEqualTo(10D);
        assertThat(allocatedRecords.get(0).totalAmount()).isEqualTo(100D);
        assertThat(allocatedRecords.get(1).paidAmount()).isEqualTo(270D);
        assertThat(allocatedRecords.get(1).shippingFee()).isEqualTo(30D);
        assertThat(allocatedRecords.get(1).totalAmount()).isEqualTo(300D);
    }

    @Test
    void shouldKeepLineLevelAmountsWhenMultiLineOrderAlreadyAllocated() {
        List<RawPurchaseRecord> records = List.of(
                record("order-line-level", "A 商品", "100g", 100D, 90D, 10D, 100D),
                record("order-line-level", "B 商品", "300g", 300D, 270D, 30D, 300D)
        );

        List<RawPurchaseRecord> allocatedRecords = policy.allocate(records);

        assertThat(allocatedRecords.get(0).paidAmount()).isEqualTo(90D);
        assertThat(allocatedRecords.get(0).shippingFee()).isEqualTo(10D);
        assertThat(allocatedRecords.get(0).totalAmount()).isEqualTo(100D);
        assertThat(allocatedRecords.get(0).amountSourceOverride()).isNull();
        assertThat(allocatedRecords.get(1).paidAmount()).isEqualTo(270D);
        assertThat(allocatedRecords.get(1).shippingFee()).isEqualTo(30D);
        assertThat(allocatedRecords.get(1).totalAmount()).isEqualTo(300D);
        assertThat(allocatedRecords.get(1).amountSourceOverride()).isNull();
    }

    @Test
    void shouldExcludeNewMemberProductFromAllocationBase() {
        List<RawPurchaseRecord> records = List.of(
                record("order-3", "A 商品", "100g", 100D, 300D, 9.9D, 300D),
                record("order-3", "B 商品", "200g", 200D, 300D, 9.9D, 300D),
                record("order-3", "0.01元新会员专享商品", "新会员", 0.01D, 300D, 9.9D, 300D)
        );

        List<RawPurchaseRecord> allocatedRecords = policy.allocate(records);

        assertThat(allocatedRecords.get(0).shippingFee()).isEqualTo(3.3D);
        assertThat(allocatedRecords.get(1).shippingFee()).isEqualTo(6.6D);
        assertThat(allocatedRecords.get(2).amountReviewRequired()).isTrue();
        assertThat(allocatedRecords.get(2).amountSourceOverride())
                .isEqualTo(OrderAmountAllocationPolicy.SOURCE_ORDER_AMOUNT_ALLOCATION_FALLBACK);
    }

    @Test
    void shouldAvoidUsingOrderPaidAmountAsCatLitterLineUnitPrice() {
        List<RawPurchaseRecord> records = List.of(
                record("order-4", "猫砂", "15kg", 78.85D, 320.84D, 0D, 320.84D),
                record("order-4", "其他复购品", "1件", 241.99D, 320.84D, 0D, 320.84D)
        );

        RawPurchaseRecord catLitterRecord = policy.allocate(records).get(0);
        Double unitPrice = new UnitPriceCalculator().calculate(catLitterRecord.totalAmount(), 15D);

        assertThat(catLitterRecord.totalAmount()).isEqualTo(78.85D);
        assertThat(unitPrice).isCloseTo(5.256667D, offset(0.000001D));
        assertThat(unitPrice).isNotCloseTo(21.3893D, offset(0.0001D));
    }

    @Test
    void shouldFallbackWithReviewWhenEffectiveAmountSumIsNotPositive() {
        List<RawPurchaseRecord> records = List.of(
                record("order-5", "赠品", "赠品", 50D, 150D, 0D, 150D),
                record("order-5", "邮费补拍", "邮费", 100D, 150D, 0D, 150D)
        );

        List<RawPurchaseRecord> allocatedRecords = policy.allocate(records);

        assertThat(allocatedRecords)
                .extracting(RawPurchaseRecord::amountReviewRequired)
                .containsOnly(true);
        assertThat(allocatedRecords)
                .extracting(RawPurchaseRecord::amountSourceOverride)
                .containsOnly(OrderAmountAllocationPolicy.SOURCE_ORDER_AMOUNT_ALLOCATION_FALLBACK);
    }

    private RawPurchaseRecord record(String orderGroupKey,
                                     String productName,
                                     String sku,
                                     Double productAmount,
                                     Double paidAmount,
                                     Double shippingFee,
                                     Double totalAmount) {
        return new RawPurchaseRecord(
                "2026-05-01 10:00:00", "taobao", "jtxw", orderGroupKey, productName, sku,
                "", "", 1D, "件", totalAmount, productAmount, paidAmount, shippingFee,
                "CNY", false, null, null, null, false, null, null
        );
    }
}
