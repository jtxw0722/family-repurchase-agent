package com.jtxw.familyagent.infrastructure.llm;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 14:17:01
 * @Description: 供应商无关的大模型聊天请求，封装提示词、模型参数、历史对话和可选图片
 *
 * @param scene 调用场景标识，仅用于调用方追踪，不由 provider adapter 解释
 * @param promptVersion 提示词版本标识，允许为空
 * @param systemPrompt 系统提示词，允许为空
 * @param userPrompt 用户提示词，不允许为空
 * @param model 模型名称，不允许为空
 * @param temperature 生成温度，允许为空，为空时由 provider adapter 使用默认值
 * @param maxTokens 最大输出 token 数，允许为空
 * @param images 图片输入列表，允许为空，构造后统一为不可变空列表或不可变副本
 * @param history 当前问题之前的完整问答轮次，按时间升序，允许为空
 */
public record LlmRequest(
        String scene,
        String promptVersion,
        String systemPrompt,
        String userPrompt,
        String model,
        Double temperature,
        Integer maxTokens,
        List<LlmImageInput> images,
        List<ChatTurn> history
) {
    /**
     * 将可选图片和对话历史统一转换为不可变列表。
     */
    public LlmRequest {
        images = images == null ? List.of() : List.copyOf(images);
        history = history == null ? List.of() : List.copyOf(history);
    }

    /**
     * 创建不带历史对话的单次模型请求。
     *
     * @param scene 调用场景标识
     * @param promptVersion 提示词版本，允许为空
     * @param systemPrompt 系统提示词，允许为空
     * @param userPrompt 当前用户输入，不允许为空
     * @param model 模型名称，不允许为空
     * @param temperature 生成温度，允许为空
     * @param maxTokens 最大输出 token 数，允许为空
     * @param images 可选图片列表，允许为空
     */
    public LlmRequest(String scene, String promptVersion, String systemPrompt, String userPrompt,
                      String model, Double temperature, Integer maxTokens, List<LlmImageInput> images) {
        this(scene, promptVersion, systemPrompt, userPrompt, model, temperature, maxTokens, images, List.of());
    }

    /**
     * @Author: jtxw
     * @Date: 2026/11/19 14:17:01
     * @Description: 大模型调用的上下文数据，保留一轮已经完成的问答及其消息归属
     * @param question 历史用户消息，非空
     * @param answer 对应的历史模型回复，非空
     */
    public record ChatTurn(String question, String answer) {
    }
}
