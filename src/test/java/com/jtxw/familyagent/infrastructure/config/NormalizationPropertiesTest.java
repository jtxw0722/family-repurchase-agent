package com.jtxw.familyagent.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 11:10:14
 * @Description: 商品归一化配置默认值测试。
 */
class NormalizationPropertiesTest {
    @Test
    void shouldUseDefaultLlmBaseUrlAndKeepDisabled() {
        NormalizationProperties properties = new NormalizationProperties();

        assertThat(properties.getLlm().getBaseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(properties.getLlm().getProvider()).isEqualTo("openai-compatible");
        assertThat(properties.getLlm().isEnabled()).isFalse();
        assertThat(properties.getLlm().isDebugLogEnabled()).isFalse();
        assertThat(properties.getLlm().getDebugLogDir()).isEqualTo("logs/llm-debug");
        assertThat(properties.immediateFallbackReview()).isTrue();
    }
}
