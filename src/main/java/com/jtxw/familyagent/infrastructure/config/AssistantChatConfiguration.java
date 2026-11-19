package com.jtxw.familyagent.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.infrastructure.llm.LlmClient;
import com.jtxw.familyagent.infrastructure.llm.OpenAiCompatibleLlmClient;
import com.jtxw.familyagent.infrastructure.llm.OpenAiCompatibleLlmClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 14:17:01
 * @Description: 聊天模型基础设施配置，为网页助手提供独立的模型连接
 */
@Configuration(proxyBeanMethods = false)
public class AssistantChatConfiguration {
    /**
     * 创建网页聊天专用模型客户端，避免聊天内容写入现有模型调试文件。
     *
     * @param properties 网页聊天模型连接配置
     * @param objectMapper 请求和响应的 JSON 转换器
     * @return 网页聊天专用客户端，创建时不发起网络请求
     */
    @Bean("assistantLlmClient")
    public LlmClient assistantLlmClient(AssistantChatProperties properties, ObjectMapper objectMapper) {
        OpenAiCompatibleLlmClientSettings settings = new OpenAiCompatibleLlmClientSettings(
                properties.baseUrl(), properties.apiKey(), properties.timeoutSeconds());
        return new OpenAiCompatibleLlmClient(settings, objectMapper);
    }
}
