package com.jtxw.familyagent.infrastructure.llm;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 09:55:44
 * @Description: 供应商无关的大模型聊天请求，封装提示词、模型参数和可选图片输入
 *
 * @param scene 调用场景标识，仅用于调用方追踪，不由 provider adapter 解释
 * @param promptVersion 提示词版本标识，允许为空
 * @param systemPrompt 系统提示词，允许为空
 * @param userPrompt 用户提示词，不允许为空
 * @param model 模型名称，不允许为空
 * @param temperature 生成温度，允许为空，为空时由 provider adapter 使用默认值
 * @param maxTokens 最大输出 token 数，允许为空
 * @param images 图片输入列表，允许为空，构造后统一为不可变空列表或不可变副本
 */
public record LlmRequest(
        String scene,
        String promptVersion,
        String systemPrompt,
        String userPrompt,
        String model,
        Double temperature,
        Integer maxTokens,
        List<LlmImageInput> images
) {
    /**
     * 将允许为空的图片列表转换为不可变列表。
     */
    public LlmRequest {
        images = images == null ? List.of() : List.copyOf(images);
    }
}
