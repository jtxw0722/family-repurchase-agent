package com.jtxw.familyagent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 14:17:01
 * @Description: 网页聊天基础设施配置，保存独立的模型服务连接参数
 * @param baseUrl 模型服务基础地址，不含聊天接口路径，默认空
 * @param apiKey 模型鉴权密钥，仅后端请求使用，默认空
 * @param model 模型名称，默认空
 * @param timeoutSeconds 连接及读取超时时间，单位秒，默认三十秒
 */
@ConfigurationProperties(prefix = "family-agent.assistant")
public record AssistantChatProperties(String baseUrl, String apiKey, String model, int timeoutSeconds) {
    /**
     * 检查聊天专用连接是否具备调用所需信息。
     *
     * @return 基础地址、密钥和模型名称均有值时为 true
     */
    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey) && StringUtils.hasText(model);
    }

    /**
     * 返回安全的配置状态摘要，避免密钥进入日志。
     *
     * @return 仅包含配置状态和超时时间的摘要
     */
    @Override
    public String toString() {
        return "AssistantChatProperties[configured=" + isConfigured() + ", timeoutSeconds=" + timeoutSeconds + "]";
    }
}
