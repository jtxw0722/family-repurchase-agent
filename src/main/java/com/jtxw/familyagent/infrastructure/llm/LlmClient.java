package com.jtxw.familyagent.infrastructure.llm;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 10:08:19
 * @Description: 通用大模型客户端抽象，负责发送结构化聊天请求并返回供应商无关的文本响应
 */
public interface LlmClient {
    /**
     * 发送包含文本和可选图片的聊天请求，不处理具体业务语义。
     *
     * @param request 供应商无关的聊天请求
     * @return 模型文本响应、实际模型、耗时和可选 token 用量
     * @throws LlmException 供应商调用失败或响应结构无效时抛出
     */
    LlmResponse chat(LlmRequest request);
}
