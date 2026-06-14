package com.jtxw.familyagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 01:02:00
 * @Description: 规则维护建议 LLM Advisor 测试，覆盖 LLM 配置校验和调用前置边界
 */
class NormalizationRuleSuggestionLlmAdvisorTest {

    @Test
    void adviseShouldRejectBlankBaseUrl() {
        NormalizationProperties normalizationProperties = new NormalizationProperties();
        normalizationProperties.getLlm().setEnabled(true);
        normalizationProperties.getLlm().setApiKey("test-key");
        normalizationProperties.getLlm().setBaseUrl(" ");
        NormalizationRuleSuggestionLlmAdvisor advisor = new NormalizationRuleSuggestionLlmAdvisor(
                normalizationProperties,
                new ObjectMapper(),
                new NormalizationRuleSuggestionPromptRenderer(),
                mock(LlmClient.class),
                new NormalizationRuleSuggestionOutputParser(new ObjectMapper())
        );

        assertThatThrownBy(() -> advisor.advise(List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少 baseUrl");
    }
}
