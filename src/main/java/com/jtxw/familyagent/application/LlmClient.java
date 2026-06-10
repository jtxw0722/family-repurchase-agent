package com.jtxw.familyagent.application;

/**
 * @Author: jtxw
 * @Date: 2026/06/10 16:34:34
 * @Description: LLM 原始调用客户端抽象，负责发送请求并返回未解析的模型服务响应。
 */
public interface LlmClient {

    /**
     * 发送 OpenAI-compatible Chat Completions 请求，并返回原始 HTTP 响应。
     *
     * @param request LLM 调用请求，包含 baseUrl、API Key、超时时间和请求体
     * @return LLM 原始响应，包含 HTTP 状态码、Content-Type、响应体字节数和响应文本
     */
    LlmClientResponse chatCompletion(LlmClientRequest request);
}
