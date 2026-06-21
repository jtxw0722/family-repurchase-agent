package com.jtxw.familyagent.infrastructure.llm;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 09:34:52
 * @Description: OpenAI-compatible 客户端连接设置，封装基础地址、鉴权密钥和超时时间
 *
 * @param baseUrl 服务基础地址，不包含具体 endpoint
 * @param apiKey 服务鉴权密钥，仅用于 Authorization 请求头
 * @param timeoutSeconds 连接和读取超时时间，单位秒
 */
public record OpenAiCompatibleLlmClientSettings(String baseUrl, String apiKey, int timeoutSeconds) {
    /**
     * 返回不包含 API Key 的安全配置摘要，用于日志和调试输出。
     *
     * @return 不包含 API Key 的安全配置摘要
     */
    @Override
    public String toString() {
        return "OpenAiCompatibleLlmClientSettings[baseUrl=" + baseUrl
                + ", apiKey=[REDACTED], timeoutSeconds=" + timeoutSeconds + "]";
    }
}
