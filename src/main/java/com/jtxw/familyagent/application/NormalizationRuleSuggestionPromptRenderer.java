package com.jtxw.familyagent.application;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 规则维护建议 prompt 加载器，负责读取版本化 system 和 user prompt 并渲染 LLM 输入 JSON
 */
@Component
public class NormalizationRuleSuggestionPromptRenderer {
    /**
     * 规则维护建议 prompt 基础路径，按版本隔离。
     */
    private static final String PROMPT_RESOURCE_PATH = "prompts/normalization-rule-suggestion/v1";
    /**
     * system prompt 资源文件名。
     */
    private static final String SYSTEM_PROMPT_FILE = "system.md";
    /**
     * user prompt 资源文件名。
     */
    private static final String USER_PROMPT_FILE = "user.md";
    /**
     * user prompt 中待替换的输入 JSON 占位符。
     */
    private static final String INPUT_JSON_PLACEHOLDER = "{{inputJson}}";
    /**
     * 已缓存的 system prompt 内容，避免重复读取 classpath。
     */
    private volatile String cachedSystemPrompt;
    /**
     * 已缓存的 user prompt 模板，避免重复读取 classpath。
     */
    private volatile String cachedUserTemplate;

    /**
     * 获取 system prompt 内容。
     *
     * @return system prompt 文本
     */
    public String getSystemPrompt() {
        if (cachedSystemPrompt == null) {
            synchronized (this) {
                if (cachedSystemPrompt == null) {
                    cachedSystemPrompt = loadResource(PROMPT_RESOURCE_PATH + "/" + SYSTEM_PROMPT_FILE);
                }
            }
        }
        return cachedSystemPrompt;
    }

    /**
     * 渲染 user prompt。
     *
     * @param inputJson 已序列化的 LLM 输入 JSON
     * @return 渲染后的 user prompt 文本
     */
    public String renderUserPrompt(String inputJson) {
        if (cachedUserTemplate == null) {
            synchronized (this) {
                if (cachedUserTemplate == null) {
                    cachedUserTemplate = loadResource(PROMPT_RESOURCE_PATH + "/" + USER_PROMPT_FILE);
                }
            }
        }
        if (!cachedUserTemplate.contains(INPUT_JSON_PLACEHOLDER)) {
            throw new IllegalStateException("规则维护建议 user prompt 缺少占位符：" + INPUT_JSON_PLACEHOLDER);
        }
        return cachedUserTemplate.replace(INPUT_JSON_PLACEHOLDER, inputJson);
    }

    private String loadResource(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("prompt 资源文件不存在：" + resourcePath);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 prompt 资源文件失败：" + resourcePath, e);
        }
    }
}
