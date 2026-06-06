package com.jtxw.familyagent.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 20:30:00
 * @Description: LLM 建议 targetUnit 规格值清洗和安全判断测试。
 */
class SuggestedTargetUnitCanonicalizerTest {
    /**
     * 被测 targetUnit 归并器。
     */
    private final SuggestedTargetUnitCanonicalizer canonicalizer = new SuggestedTargetUnitCanonicalizer();

    @Test
    void shouldStripNumericSpecFromTargetUnit() {
        assertThat(canonicalizer.canonicalize("猫主食罐", "240g").targetUnit()).isEqualTo("g");
        assertThat(canonicalizer.canonicalize("猫主食罐", "840g").targetUnit()).isEqualTo("g");
        assertThat(canonicalizer.canonicalize("猫主食罐", "80g*4").targetUnit()).isEqualTo("g");
        assertThat(canonicalizer.canonicalize("猫主食罐", "40g×9").targetUnit()).isEqualTo("g");
        assertThat(canonicalizer.canonicalize("猫主食罐", "100g/包").targetUnit()).isEqualTo("g");
        assertThat(canonicalizer.canonicalize("精华液", "500ml").targetUnit()).isEqualTo("ml");
        assertThat(canonicalizer.canonicalize("美瞳", "10片").targetUnit()).isEqualTo("片");
    }

    @Test
    void shouldKeepCatMainCanGramSafeAfterSpecStripping() {
        SuggestedTargetUnitCanonicalizer.TargetUnitSafetyResult result =
                canonicalizer.canonicalize("猫主食罐", "240g");

        assertThat(result.targetUnit()).isEqualTo("g");
        assertThat(result.batchApprovalSafe()).isTrue();
    }

    @Test
    void shouldKeepCatMainCanPackageUnitsUnsafeAfterSpecStripping() {
        SuggestedTargetUnitCanonicalizer.TargetUnitSafetyResult result =
                canonicalizer.canonicalize("猫主食罐", "12罐");

        assertThat(result.targetUnit()).isEqualTo("罐");
        assertThat(result.batchApprovalSafe()).isFalse();
    }
}
