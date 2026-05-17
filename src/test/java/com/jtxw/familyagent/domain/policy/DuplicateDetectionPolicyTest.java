package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.PurchaseRecord;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/05/17/09:49
 * @Description: 重复订单检测规则的单元测试。
 */
class DuplicateDetectionPolicyTest {
    @Test
    void shouldDetectDuplicateInCurrentBatch() {
        DuplicateDetectionPolicy policy = new DuplicateDetectionPolicy();
        Set<String> fingerprints = new HashSet<>();
        PurchaseRecord record = record(12D, 89D);

        assertThat(policy.isDuplicate(record, fingerprints, false)).isFalse();
        assertThat(policy.isDuplicate(record, fingerprints, false)).isTrue();
    }

    @Test
    void shouldNormalizeEquivalentNumberValuesInFingerprint() {
        DuplicateDetectionPolicy policy = new DuplicateDetectionPolicy();

        assertThat(policy.fingerprint(record(12D, 89D)))
                .isEqualTo(policy.fingerprint(record(12.0D, 89.00D)));
    }

    @Test
    void shouldRespectHistoricalDuplicateFlag() {
        DuplicateDetectionPolicy policy = new DuplicateDetectionPolicy();
        Set<String> fingerprints = new HashSet<>();

        assertThat(policy.isDuplicate(record(12D, 89D), fingerprints, true)).isTrue();
    }

    private PurchaseRecord record(Double quantity, Double totalAmount) {
        return new PurchaseRecord(
                null, 1L, "2026-05-01", "taobao", "JTXW", "混合猫砂 12kg", "猫砂",
                "12kg", "宠物用品", "猫砂", quantity, "kg", totalAmount, 7.42D,
                "CNY", "include", false, "unique", "examples/sample_orders.csv", "2026-05-13 01:24:00"
        );
    }
}
