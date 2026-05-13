package com.jtxw.familyagent.domain.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/05/13/15:12
 * @Description: 单位价格计算规则的单元测试。
 */
class UnitPriceCalculatorTest {
    @Test
    void shouldCalculateUnitPrice() {
        UnitPriceCalculator calculator = new UnitPriceCalculator();
        assertThat(calculator.calculate(89, 12)).isCloseTo(7.4167, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void shouldRejectZeroQuantity() {
        UnitPriceCalculator calculator = new UnitPriceCalculator();
        assertThatThrownBy(() -> calculator.calculate(89, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
