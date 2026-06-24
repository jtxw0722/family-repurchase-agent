package com.jtxw.familyagent.infrastructure.ocr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.infrastructure.llm.LlmClient;
import com.jtxw.familyagent.infrastructure.llm.LlmDebugLogger;
import com.jtxw.familyagent.infrastructure.llm.OpenAiCompatibleLlmClient;
import com.jtxw.familyagent.infrastructure.llm.OpenAiCompatibleLlmClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 10:52:14
 * @Description: 订单截图 LLM 配置桥接器，将现有场景配置转换为通用 OpenAI-compatible 客户端连接设置
 */
@Configuration
public class OrderImageLlmConfiguration {
    /**
     * 创建订单截图场景专用的通用 LLM 客户端，保持外部配置 key 不变。
     *
     * @param properties 现有订单截图模型配置
     * @param objectMapper JSON 请求构造和响应解析器
     * @return 订单截图场景使用的 OpenAI-compatible 客户端
     */
    @Bean("orderImageLlmClient")
    public LlmClient orderImageLlmClient(ParseOrderImageModelProperties properties,
                                         ObjectMapper objectMapper,
                                         LlmDebugLogger debugLogger) {
        OpenAiCompatibleLlmClientSettings settings = new OpenAiCompatibleLlmClientSettings(
                properties.getBaseUrl(), properties.getApiKey(), properties.getTimeoutSeconds());
        return new OpenAiCompatibleLlmClient(settings, objectMapper, debugLogger);
    }
}
