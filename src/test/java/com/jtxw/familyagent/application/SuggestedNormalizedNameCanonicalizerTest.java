package com.jtxw.familyagent.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 13:13:18
 * @Description: LLM 建议标准品类归并器测试。
 */
class SuggestedNormalizedNameCanonicalizerTest {
    /**
     * 被测标准品类归并器。
     */
    private final SuggestedNormalizedNameCanonicalizer canonicalizer = new SuggestedNormalizedNameCanonicalizer();

    @Test
    void shouldCanonicalizeCatMainCanNames() {
        assertThat(canonicalizer.canonicalize("猫罐头主食罐鸡肉味", "85g*6", "主食罐"))
                .isEqualTo("猫主食罐");
        assertThat(canonicalizer.canonicalize("幼猫全价湿粮罐头", "默认", "猫主食罐头"))
                .isEqualTo("猫主食罐");
        assertThat(canonicalizer.canonicalize("大P便当肉蛋堡奶猫罐试吃猫罐头幼猫奶糕主食罐头猫咪零食奶昔", "默认", "猫罐头"))
                .isEqualTo("猫主食罐");
        assertThat(canonicalizer.canonicalize("诚实一口全价成猫幼猫用主食餐盒营养湿粮非零食40g*7/盒", "默认", "猫罐头"))
                .isEqualTo("猫主食罐");
        assertThat(canonicalizer.canonicalize("鲜朗猫罐头主食罐幼猫成猫湿粮猫咪零食无谷全价", "默认", "猫罐头"))
                .isEqualTo("猫主食罐");
        assertThat(canonicalizer.canonicalize("【双11预售立即付定】尾巴生活彩虹泥主食餐盒一餐一杯猫罐头", "默认", "猫罐头"))
                .isEqualTo("猫主食罐");
    }

    @Test
    void shouldCanonicalizeSnackCanWithoutMainContextToCatSnack() {
        assertThat(canonicalizer.canonicalize("鸡肉零食罐补水罐尝鲜罐猫咪零食", "默认", "猫罐头"))
                .isEqualTo("猫零食");
    }

    @Test
    void shouldCanonicalizeCatStickNames() {
        assertThat(canonicalizer.canonicalize("猫条三文鱼口味", "默认", "猫条"))
                .isEqualTo("猫条");
        assertThat(canonicalizer.canonicalize("猫咪咕噜酱补水零食", "默认", "咕噜酱"))
                .isEqualTo("猫条");
    }

    @Test
    void shouldCanonicalizeContactLensNames() {
        assertThat(canonicalizer.canonicalize("彩片隐形眼镜", "10片", "隐形眼镜"))
                .isEqualTo("美瞳");
        assertThat(canonicalizer.canonicalize("美瞳日抛", "10片", "日抛"))
                .isEqualTo("美瞳");
    }

    @Test
    void shouldCanonicalizeEssenceNames() {
        assertThat(canonicalizer.canonicalize("兰花油修护精华", "30ml", "兰花油"))
                .isEqualTo("精华液");
        assertThat(canonicalizer.canonicalize("保湿精华油", "30ml", "精华油"))
                .isEqualTo("精华液");
    }
}
