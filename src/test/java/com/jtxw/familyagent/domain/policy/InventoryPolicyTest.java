package com.jtxw.familyagent.domain.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 17:00:04
 * @Description: 库存领域策略测试，验证单位精度、保守包装换算、周期中位数和未知库存边界
 */
class InventoryPolicyTest {
    /** 固定购买起始日，不依赖系统时间。 */
    private static final LocalDate START = LocalDate.of(2026, 10, 1);

    /** 验证物理单位与明确包装规格，已归一化数量不再重复乘规格。 */
    @ParameterizedTest
    @CsvSource({"4.5,kg,g,,4500", "8,kg,g,,8000", "4500,g,g,,4500", "2,袋,g,4.5kg,9000", "2,袋,g,8kg/袋,16000", "2,L,ml,,2000", "10,kg,g,4.5kg,10000", "2,袋,袋,,2"})
    void convertsExactQuantity(String quantity, String unit, String target, String sku, String expected) {
        assertThat(InventoryPolicy.convert(new BigDecimal(quantity), unit, target, sku)).isEqualByComparingTo(expected);
    }

    /** 未知和组合包装、维度冲突均拒绝，不猜测净含量。 */
    @ParameterizedTest
    @CsvSource({"袋,g,", "kg,ml,", "袋,g,4.5kg+赠品", "袋,g,4.5kgx2袋", "袋,g,4.5kg/瓶"})
    void rejectsUnsafeConversion(String unit, String target, String sku) {
        assertThatThrownBy(() -> InventoryPolicy.convert(BigDecimal.ONE, unit, target, sku)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 4.5kg/14天与8kg/25天的两个周期取中位数，而非平均间隔。 */
    @Test void quantityWeightedMedian() {
        var result = InventoryPolicy.estimate(List.of(purchase(0,"4500"), purchase(14,"8000"), purchase(39,"4500")), 3);
        assertThat(result.rate()).isEqualByComparingTo("320.714285714286");
        assertThat(result.sampleCount()).isEqualTo(2);
        assertThat(result.purchaseCount()).isEqualTo(3);
        assertThat(result.label()).isEqualTo("少样本");
        var odd = InventoryPolicy.estimate(List.of(purchase(0,"4500"), purchase(14,"8000"), purchase(39,"100"), purchase(40,"1")),3);
        assertThat(odd.rate()).isEqualByComparingTo("320");
        assertThat(odd.label()).isEqualTo("历史估算");
    }

    /** 单次购买不预测，两次购买一个周期；同日明细合并但原始条数保留。 */
    @Test void sampleCountsAndSameDayMerge() {
        assertThat(InventoryPolicy.estimate(List.of(purchase(0,"4500")),3).rate()).isNull();
        var two = InventoryPolicy.estimate(List.of(purchase(0,"4500"),purchase(14,"8000")),3);
        assertThat(two.sampleCount()).isEqualTo(1);
        assertThat(two.label()).isEqualTo("少样本");
        assertThat(two.rate()).isEqualByComparingTo("321.428571428571");
        var sameDay = InventoryPolicy.estimate(List.of(purchase(0,"2000"),purchase(0,"2500"),purchase(14,"8000")),3);
        assertThat(sameDay.rate()).isEqualByComparingTo(two.rate());
        assertThat(sameDay.purchaseCount()).isEqualTo(3);
        assertThat(sameDay.sampleCount()).isEqualTo(1);
    }

    /** 人工参数每个输入变化均重新计算，单位等价保持一致，非法输入被拒绝。 */
    @Test void manualInputsAndStockBoundary() {
        assertThat(rate("4.5","kg","14")).isEqualByComparingTo("321.428571428571");
        assertThat(rate("4500","g","14")).isEqualByComparingTo(rate("4.5","kg","14"));
        assertThat(rate("9","kg","14")).isEqualByComparingTo("642.857142857143");
        assertThat(rate("4.5","kg","7")).isEqualByComparingTo("642.857142857143");
        assertThatThrownBy(()->rate("0","kg","14")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->rate("1","kg","0")).isInstanceOf(IllegalArgumentException.class);
        assertThat(InventoryPolicy.remaining(null,START,START.plusDays(50),BigDecimal.ONE)).isNull();
        assertThat(InventoryPolicy.remaining(BigDecimal.ZERO,START,START.plusDays(50),BigDecimal.ONE)).isZero();
        assertThat(InventoryPolicy.remaining(BigDecimal.TEN,START,START.plusDays(50),null)).isEqualByComparingTo("10");
        assertThat(InventoryPolicy.remaining(BigDecimal.TEN,START,START.plusDays(50),BigDecimal.ONE)).isZero();
    }

    /** 速率舍入不能让恰好三天的数量变成两天，非整数仍向下取整。 */
    @Test void completeDaysAvoidsRateRoundingBoundary() {
        assertThat(InventoryPolicy.completeDays(new BigDecimal("2000"), rate("2","kg","3"))).isEqualByComparingTo("3");
        assertThat(InventoryPolicy.completeDays(new BigDecimal("10000"), rate("4.5","kg","14"))).isEqualByComparingTo("31");
    }

    /** 同一单位族可向规则单位反向换算，自然计数支持规则定义的单位而不依赖白名单。 */
    @ParameterizedTest
    @CsvSource({"4500,g,kg,,4.5", "2500,ml,L,,2.5", "2.5,L,ml,,2500", "2,袋,kg,4.5kg/袋,9",
            "6,粒,粒,,6", "11,条,条,,11", "960,cm*撕,cm*撕,,960", "1800,抽,抽,,1800"})
    void convertsToProductStandardUnit(String quantity, String source, String target, String specification, String expected) {
        assertThat(InventoryPolicy.convert(new BigDecimal(quantity), source, target, specification)).isEqualByComparingTo(expected);
    }

    /** 同一重量/体积单位族可分别选择kg/g、L/ml，计数和抽数遵循各商品自己的规则。 */
    @ParameterizedTest
    @CsvSource({"kg,weight,kg", "g,weight,g", "L,volume,L", "ml,volume,ml", "抽,draw_count,抽",
            "粒,count,粒", "条,count,条", "cm*撕,count,cm*撕"})
    void respectsConfiguredStandardUnit(String unit, String family, String expected) {
        assertThat(InventoryPolicy.standardUnit(unit, family)).isEqualTo(expected);
    }

    /** 规则单位不兼容或不同计数单位不能擅自换算；数量和速度同倍率换算保持使用天数不变。 */
    @Test void rejectsWrongFamilyAndPreservesDuration() {
        assertThatThrownBy(() -> InventoryPolicy.standardUnit("瓶", "weight")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InventoryPolicy.standardUnit("kg", "count")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InventoryPolicy.standardUnit("条", "draw_count")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InventoryPolicy.convert(BigDecimal.ONE, "粒", "条", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InventoryPolicy.convert(BigDecimal.ONE, "cm*撕", "件", null)).isInstanceOf(IllegalArgumentException.class);
        BigDecimal grams = new BigDecimal("10000");
        BigDecimal gramRate = rate("4.5", "kg", "14");
        BigDecimal kilograms = InventoryPolicy.convert(grams, "g", "kg", null);
        BigDecimal kilogramRate = InventoryPolicy.convert(gramRate, "g", "kg", null);
        assertThat(InventoryPolicy.completeDays(kilograms, kilogramRate)).isEqualByComparingTo(InventoryPolicy.completeDays(grams, gramRate));
        assertThat(InventoryPolicy.convert(kilograms, "kg", "g", null)).isEqualByComparingTo(grams);
    }

    /** @return 基础重量日均消耗 */
    private BigDecimal rate(String quantity, String unit, String days) { return InventoryPolicy.manualRate(new BigDecimal(quantity),unit,new BigDecimal(days),"g"); }
    /** @return 固定起点偏移日的购买样本 */
    private InventoryPolicy.Purchase purchase(int offset, String quantity) { return new InventoryPolicy.Purchase(START.plusDays(offset),new BigDecimal(quantity)); }
}
