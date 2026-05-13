package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/05/13/16:08
 * @Description: 价格决策规则的单元测试。
 */
class PriceDecisionPolicyTest {
    @Test
    void shouldReturnNormalPrice() {
        PriceDecisionPolicy policy = new PriceDecisionPolicy();
        PriceDecisionResult result = policy.decide("猫砂", "猫砂", 89, 12, "kg", List.of(6.8, 8.15));
        assertThat(result.decision()).isEqualTo("normal_price");
        assertThat(result.decisionText()).isEqualTo("正常价格");
    }

    @Test
    void shouldReturnInsufficientData() {
        PriceDecisionPolicy policy = new PriceDecisionPolicy();
        PriceDecisionResult result = policy.decide("猫砂", "猫砂", 89, 12, "kg", List.of());
        assertThat(result.decision()).isEqualTo("insufficient_data");
    }
}
