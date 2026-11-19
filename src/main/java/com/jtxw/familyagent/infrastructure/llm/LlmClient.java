package com.jtxw.familyagent.infrastructure.llm;

import java.util.function.Consumer;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 17:52:58
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

    /**
     * 发送聊天请求，并按返回顺序交付回复文本。
     *
     * @param request 供应商无关的聊天请求，非空
     * @param onDelta 文本片段接收器，非空；每次收到正文时同步调用
     * @throws LlmException 供应商调用失败或未返回有效正文时抛出
     * @implSpec 未实现流式协议的客户端在完整回复后只交付一次正文。
     */
    default void streamChat(LlmRequest request, Consumer<String> onDelta) {
        onDelta.accept(chat(request).content());
    }
}
