package com.jtxw.familyagent.infrastructure.llm;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 09:15:43
 * @Description: 供应商无关的大模型聊天响应，仅承载模型文本、调用元数据和可选 token 用量
 *
 * @param content 模型首个消息的文本内容
 * @param provider 模型供应商标识
 * @param model 实际使用的模型名称
 * @param durationMillis 请求耗时，单位毫秒
 * @param promptTokens 输入 token 数，供应商未返回时为空
 * @param completionTokens 输出 token 数，供应商未返回时为空
 */
public record LlmResponse(
        String content,
        String provider,
        String model,
        Long durationMillis,
        Integer promptTokens,
        Integer completionTokens
) {
}
