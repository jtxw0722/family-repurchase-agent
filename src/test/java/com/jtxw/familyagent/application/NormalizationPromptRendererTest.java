package com.jtxw.familyagent.application;

import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/10
 * @Description: NormalizationPromptRenderer prompt 资源加载和模板渲染测试。
 */
class NormalizationPromptRendererTest {
    /**
     * 默认 prompt 资源路径。
     */
    private static final String DEFAULT_PROMPT_RESOURCE_PATH = "prompts/normalization/v1";

    /**
     * 创建使用指定资源路径的 renderer 实例。
     *
     * @param promptResourcePath classpath 中的 prompt 资源目录
     * @return renderer 实例
     */
    private static NormalizationPromptRenderer renderer(String promptResourcePath) {
        NormalizationProperties properties = new NormalizationProperties();
        properties.getLlm().setPromptResourcePath(promptResourcePath);
        return new NormalizationPromptRenderer(properties);
    }

    /**
     * 创建使用默认资源路径的 renderer 实例。
     *
     * @return renderer 实例
     */
    private static NormalizationPromptRenderer renderer() {
        return renderer(DEFAULT_PROMPT_RESOURCE_PATH);
    }

    @Test
    void shouldLoadSystemPromptFromConfiguredResourcePath() {
        NormalizationPromptRenderer promptRenderer = renderer();

        String systemPrompt = promptRenderer.getSystemPrompt();

        assertThat(systemPrompt).isNotBlank();
        assertThat(systemPrompt).contains("你是商品归一化分类器");
        assertThat(systemPrompt).contains("只输出 JSON Array");
        assertThat(systemPrompt).contains("NORMALIZE");
        assertThat(systemPrompt).contains("EXCLUDE");
        assertThat(systemPrompt).contains("REVIEW");
    }

    @Test
    void shouldLoadUserTemplateFromConfiguredResourcePath() {
        NormalizationPromptRenderer promptRenderer = renderer();

        String userPrompt = promptRenderer.renderUserPrompt("test-input");

        assertThat(userPrompt).isNotBlank();
        assertThat(userPrompt).startsWith("逐条输出 compact JSON Array：");
        assertThat(userPrompt).contains("test-input");
        assertThat(userPrompt).doesNotContain("{{inputJson}}");
    }

    @Test
    void shouldReplaceInputJsonPlaceholder() {
        NormalizationPromptRenderer promptRenderer = renderer();
        String inputJson = "{\"context\":{},\"items\":[]}";

        String userPrompt = promptRenderer.renderUserPrompt(inputJson);

        assertThat(userPrompt).contains(inputJson);
        assertThat(userPrompt).doesNotContain("{{inputJson}}");
    }

    @Test
    void shouldReplacePlaceholderWithEmptyString() {
        NormalizationPromptRenderer promptRenderer = renderer();

        String userPrompt = promptRenderer.renderUserPrompt("");

        assertThat(userPrompt.trim()).isEqualTo("逐条输出 compact JSON Array：");
    }

    @Test
    void shouldReplacePlaceholderWithSpecialCharacters() {
        NormalizationPromptRenderer promptRenderer = renderer();
        String inputJson = "{\"name\":\"猫条三文鱼口味\",\"sku\":\"15g*4\"}";

        String userPrompt = promptRenderer.renderUserPrompt(inputJson);

        assertThat(userPrompt).contains("猫条三文鱼口味");
        assertThat(userPrompt).contains("15g*4");
    }

    @Test
    void shouldCacheSystemPromptAcrossCalls() {
        NormalizationPromptRenderer promptRenderer = renderer();

        String first = promptRenderer.getSystemPrompt();
        String second = promptRenderer.getSystemPrompt();

        assertThat(first).isSameAs(second);
    }

    @Test
    void shouldCacheUserTemplateAcrossCalls() {
        NormalizationPromptRenderer promptRenderer = renderer();

        String first = promptRenderer.renderUserPrompt("a");
        String second = promptRenderer.renderUserPrompt("b");

        assertThat(first).contains("a");
        assertThat(second).contains("b");
    }

    @Test
    void shouldThrowWhenResourcePathDoesNotExist() {
        NormalizationPromptRenderer promptRenderer = renderer("prompts/nonexistent/v999");

        assertThatThrownBy(promptRenderer::getSystemPrompt)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prompt 资源文件不存在")
                .hasMessageContaining("prompts/nonexistent/v999");
    }

    @Test
    void shouldThrowWhenResourcePathIsEmpty() {
        NormalizationPromptRenderer promptRenderer = renderer("");

        assertThatThrownBy(promptRenderer::getSystemPrompt)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldLoadFromCustomResourcePath() {
        NormalizationPromptRenderer promptRenderer = renderer("prompts/normalization/v1");

        String systemPrompt = promptRenderer.getSystemPrompt();
        String userPrompt = promptRenderer.renderUserPrompt("{\"test\":true}");

        assertThat(systemPrompt).isNotBlank();
        assertThat(userPrompt).contains("{\"test\":true}");
    }
}
