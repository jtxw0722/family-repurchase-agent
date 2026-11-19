package com.jtxw.familyagent.domain.policy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 22:20:17
 * @Description: 库存时间线领域测试，验证按入库日和分段消耗速度重放、断货截断以及缺少可靠消耗参数时的未知数量
 */
class InventoryTimelinePolicyTest {
    /** 固定时间线起点，所有数量及消耗速度均使用相同基础单位，不依赖系统当前日期。 */
    private static final LocalDate START = LocalDate.of(2026, 11, 1);

    /** 首个可靠消耗速度可追溯到历史入库日；使用最早日期表达初次历史重建的适用范围。 */
    private static final LocalDate INITIAL_RATE_DATE = LocalDate.MIN;

    /** 同商品每天只扣减一次；输入乱序及同日多笔不能改变合并后的剩余数量。 */
    @Test
    void mergesSameDayReceiptsAndConsumesOnceAlongTheChronologicalTimeline() {
        var projection = InventoryPolicy.project(BigDecimal.ZERO, START,
                List.of(purchase(9, "5"), purchase(0, "2"), purchase(0, "3")),
                List.of(period(INITIAL_RATE_DATE, "0.2")), START.plusDays(17));

        assertThat(projection.quantity()).isEqualByComparingTo("6.6");
        assertThat(projection.receivedQuantity()).isEqualByComparingTo("10");
        assertThat(projection.pending()).isFalse();
        assertThat(projection.depletionDate()).isEqualTo(START.plusDays(50));
    }

    /** 断货期间不累计消耗债务，后续入库不能替此前没有库存的日期补扣数量。 */
    @Test
    void emptyStockDoesNotAccumulateConsumptionDebt() {
        var projection = InventoryPolicy.project(BigDecimal.ZERO, START,
                List.of(purchase(0, "1"), purchase(10, "2")),
                List.of(period(INITIAL_RATE_DATE, "1")), START.plusDays(11));

        assertThat(projection.quantity()).isEqualByComparingTo("1");
        assertThat(projection.receivedQuantity()).isEqualByComparingTo("3");
        assertThat(projection.pending()).isFalse();
        assertThat(projection.depletionDate()).isEqualTo(START.plusDays(12));
    }

    /** 修改速度从生效日开始按新速度消耗，不能让新参数追溯覆盖此前已结算的区间。 */
    @Test
    void appliesRateChangesOnlyToTheirRespectiveDateSegments() {
        var projection = InventoryPolicy.project(new BigDecimal("10"), START, List.of(),
                List.of(period(START.plusDays(4), "0.5"), period(INITIAL_RATE_DATE, "1")),
                START.plusDays(8));

        assertThat(projection.quantity()).isEqualByComparingTo("4");
        assertThat(projection.receivedQuantity()).isEqualByComparingTo("10");
        assertThat(projection.pending()).isFalse();
        assertThat(projection.depletionDate()).isEqualTo(START.plusDays(16));
    }

    /** 有历史入库但没有可靠速度时，原始入库总量只作待估算信息，不伪装成今日剩余。 */
    @Test
    void historicalStockWithoutAReliableRateRemainsPending() {
        var projection = InventoryPolicy.project(BigDecimal.ZERO, START,
                List.of(purchase(0, "5"), purchase(5, "5")), List.of(), START.plusDays(7));

        assertThat(projection.quantity()).isNull();
        assertThat(projection.receivedQuantity()).isEqualByComparingTo("10");
        assertThat(projection.pending()).isTrue();
        assertThat(projection.depletionDate()).isNull();
    }

    /** 晚于首笔入库才生效的速度不能填补此前缺口；首次补充参数可明确追溯重建历史。 */
    @Test
    void firstReliableRateCanRebuildPreviouslyPendingHistory() {
        var receipts = List.of(purchase(0, "5"));
        var incomplete = InventoryPolicy.project(BigDecimal.ZERO, START, receipts,
                List.of(period(START.plusDays(5), "0.2")), START.plusDays(10));
        assertThat(incomplete.quantity()).isNull();
        assertThat(incomplete.pending()).isTrue();
        assertThat(incomplete.depletionDate()).isNull();

        var rebuilt = InventoryPolicy.project(BigDecimal.ZERO, START, receipts,
                List.of(period(INITIAL_RATE_DATE, "0.2")), START.plusDays(10));
        assertThat(rebuilt.quantity()).isEqualByComparingTo("3");
        assertThat(rebuilt.receivedQuantity()).isEqualByComparingTo("5");
        assertThat(rebuilt.pending()).isFalse();
        assertThat(rebuilt.depletionDate()).isEqualTo(START.plusDays(25));
    }

    /** 缺少速度的时间段内若始终为零，则今天新入库的数量仍然明确，不需要猜测历史消耗。 */
    @Test
    void zeroStockGapDoesNotMakeTodaysReceiptUnknown() {
        var projection = InventoryPolicy.project(BigDecimal.ZERO, START,
                List.of(purchase(10, "5")), List.of(), START.plusDays(10));

        assertThat(projection.quantity()).isEqualByComparingTo("5");
        assertThat(projection.receivedQuantity()).isEqualByComparingTo("5");
        assertThat(projection.pending()).isFalse();
        assertThat(projection.depletionDate()).isNull();
    }

    /** 人工确认的当天实存数量无需历史消耗速度即可展示，但没有速度时不生成补货日期。 */
    @Test
    void aConfirmedQuantityForTodayDoesNotRequireAnEarlierRate() {
        var projection = InventoryPolicy.project(new BigDecimal("2.7"), START, List.of(), List.of(), START);

        assertThat(projection.quantity()).isEqualByComparingTo("2.7");
        assertThat(projection.receivedQuantity()).isEqualByComparingTo("2.7");
        assertThat(projection.pending()).isFalse();
        assertThat(projection.depletionDate()).isNull();
    }

    /** 库存未知与库存零不同，新增入库和可靠速度都不能凭空确定此前的实存数量。 */
    @Test
    void anUnknownAnchorRemainsUnknownAfterAdditionalReceipts() {
        var projection = InventoryPolicy.project(null, START,
                List.of(purchase(2, "2")), List.of(period(INITIAL_RATE_DATE, "0.2")), START.plusDays(4));

        assertThat(projection.quantity()).isNull();
        assertThat(projection.pending()).isFalse();
        assertThat(projection.depletionDate()).isNull();
    }

    /** 当前数量锚点只重放锚点后的入库与消耗，历史区间不能再次扣到已确认实存数量上。 */
    @Test
    void startsFromTheConfirmedQuantityAndItsOwnDate() {
        var projection = InventoryPolicy.project(new BigDecimal("6"), START.plusDays(10),
                List.of(purchase(12, "2")), List.of(period(INITIAL_RATE_DATE, "0.2")), START.plusDays(15));

        assertThat(projection.quantity()).isEqualByComparingTo("7");
        assertThat(projection.receivedQuantity()).isEqualByComparingTo("8");
        assertThat(projection.pending()).isFalse();
        assertThat(projection.depletionDate()).isEqualTo(START.plusDays(50));
    }

    /** 耗尽日期采用完整可用自然日向下取整，不能把不足一天的剩余量补成完整一天。 */
    @Test
    void depletionDateUsesCompleteNaturalDays() {
        var projection = InventoryPolicy.project(new BigDecimal("10"), START, List.of(),
                List.of(period(INITIAL_RATE_DATE, "3")), START.plusDays(1));

        assertThat(projection.quantity()).isEqualByComparingTo("7");
        assertThat(projection.pending()).isFalse();
        assertThat(projection.depletionDate()).isEqualTo(START.plusDays(3));
    }

    /**
     * 创建相对固定起点的入库明细，不含价格字段。
     * @param offset 自然日偏移，非负
     * @param quantity 精确正数文本，单位与测试中的速度一致
     * @return 已统一单位的入库样本
     */
    private InventoryPolicy.Purchase purchase(int offset, String quantity) {
        return new InventoryPolicy.Purchase(START.plusDays(offset), new BigDecimal(quantity));
    }

    /**
     * 创建消耗速度生效区间的起点。
     * @param effectiveDate 生效自然日，非空
     * @param dailyRate 精确正数日均消耗文本，非空
     * @return 供时间线按日期排序使用的消耗速度节点
     */
    private InventoryPolicy.RatePeriod period(LocalDate effectiveDate, String dailyRate) {
        return new InventoryPolicy.RatePeriod(effectiveDate, new BigDecimal(dailyRate));
    }
}
