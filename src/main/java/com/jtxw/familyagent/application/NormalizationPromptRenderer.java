package com.jtxw.familyagent.application;

import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 商品归一化 prompt 加载器，从 classpath 读取版本化 prompt 资源文件。
 *
 * <p>prompt 资源目录由 {@code family-agent.normalization.llm.prompt-resource-path} 配置项指定，
 * 目录下包含 system prompt、user prompt 模板和 output schema 文档。</p>
 *
 * @Author: jtxw
 * @Date: 2026/06/10
 * @Description: 商品归一化 prompt 资源加载与模板渲染。
 */
@Component
public class NormalizationPromptRenderer {
    /**
     * system prompt 资源文件名。
     */
    private static final String SYSTEM_PROMPT_FILE = "system.md";
    /**
     * user prompt 模板资源文件名。
     */
    private static final String USER_TEMPLATE_FILE = "user-template.md";
    /**
     * user prompt 模板中输入 JSON 的占位符。
     */
    private static final String INPUT_JSON_PLACEHOLDER = "{{inputJson}}";

    /**
     * prompt 资源文件在 classpath 中的基础路径。
     */
    private final String promptResourcePath;
    /**
     * 已缓存的 system prompt 内容，避免每次调用重复读取 classpath。
     */
    private volatile String cachedSystemPrompt;
    /**
     * 已缓存的 user prompt 模板内容，避免每次调用重复读取 classpath。
     */
    private volatile String cachedUserTemplate;

    /**
     * 构造 prompt 加载器，从归一化配置中获取资源路径。
     *
     * @param normalizationProperties 归一化配置，提供 prompt-resource-path
     */
    public NormalizationPromptRenderer(NormalizationProperties normalizationProperties) {
        this.promptResourcePath = normalizationProperties.getLlm().getPromptResourcePath();
    }

    /**
     * 获取 system prompt 内容。
     *
     * @return system prompt 文本
     * @throws IllegalStateException 资源文件读取失败时抛出
     */
    public String getSystemPrompt() {
        if (cachedSystemPrompt == null) {
            synchronized (this) {
                if (cachedSystemPrompt == null) {
                    cachedSystemPrompt = loadResource(promptResourcePath + "/" + SYSTEM_PROMPT_FILE);
                }
            }
        }
        return cachedSystemPrompt;
    }

    /**
     * 渲染 user prompt，将模板中的 {@code {{inputJson}}} 占位符替换为实际输入 JSON。
     *
     * @param inputJson 序列化后的输入 JSON 字符串
     * @return 渲染后的 user prompt 文本
     * @throws IllegalStateException 资源文件读取失败或模板缺少占位符时抛出
     */
    public String renderUserPrompt(String inputJson) {
        if (cachedUserTemplate == null) {
            synchronized (this) {
                if (cachedUserTemplate == null) {
                    cachedUserTemplate = loadResource(promptResourcePath + "/" + USER_TEMPLATE_FILE);
                }
            }
        }
        String template = cachedUserTemplate;
        if (!template.contains(INPUT_JSON_PLACEHOLDER)) {
            throw new IllegalStateException("user prompt 模板缺少占位符：" + INPUT_JSON_PLACEHOLDER);
        }
        return template.replace(INPUT_JSON_PLACEHOLDER, inputJson);
    }

    /**
     * 从 classpath 加载资源文件内容，使用 UTF-8 编码。
     *
     * @param resourcePath classpath 资源路径
     * @return 文件内容
     * @throws IllegalStateException 资源不存在或读取失败时抛出
     */
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
