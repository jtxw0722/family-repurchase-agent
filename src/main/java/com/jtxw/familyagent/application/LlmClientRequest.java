package com.jtxw.familyagent.application;

/**
 * @Author: jtxw
 * @Date: 2026/06/10 16:35:24
 * @Description: LLM 原始调用请求，承载 HTTP 调用所需配置和已序列化请求体。
 *
 * @param baseUrl               LLM 服务 baseUrl，不包含具体 endpoint path
 * @param apiKey                LLM 服务 API Key，仅用于 Authorization 请求头
 * @param requestTimeoutSeconds HTTP connect/read 超时时间，单位秒
 * @param requestBody           已序列化好的 JSON 请求体，不包含业务上下文字段拆分结构
 */
public record LlmClientRequest(
        String baseUrl,
        String apiKey,
        int requestTimeoutSeconds,
        String requestBody
) {
}
