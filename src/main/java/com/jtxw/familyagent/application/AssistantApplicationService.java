package com.jtxw.familyagent.application;

import com.jtxw.familyagent.infrastructure.config.AssistantChatProperties;
import com.jtxw.familyagent.infrastructure.llm.LlmClient;
import com.jtxw.familyagent.infrastructure.llm.LlmException;
import com.jtxw.familyagent.infrastructure.llm.LlmRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 17:52:58
 * @Description: 网页聊天应用服务，组织问题和对话上下文并协调完整或流式模型回复
 */
@Service
public class AssistantApplicationService {
    /** 网页聊天的模型调用场景标识，固定非空。 */
    private static final String CHAT_SCENE = "assistant_chat";

    /** 聊天模型的职责说明，包含家庭数据访问范围，固定非空。 */
    private static final String SYSTEM_PROMPT = """
            你是家庭复购应用的对话助手，用简明中文回答。
            可以解释复购、商品规格和单位价格等通用知识。
            涉及用户家庭的库存、历史价格和订单时，只依据用户在对话中提供的信息。
            用户需要这些数据时，引导用户打开相应的功能页。不要虚构数据或声称已经执行操作。
            """;

    /** 网页聊天专用模型客户端，非空。 */
    private final LlmClient assistantLlmClient;

    /** 网页聊天的模型名称和连接配置，非空。 */
    private final AssistantChatProperties properties;

    /**
     * 注入网页聊天专用客户端和配置。
     *
     * @param assistantLlmClient 网页聊天专用模型客户端
     * @param properties 网页聊天配置
     */
    public AssistantApplicationService(@Qualifier("assistantLlmClient") LlmClient assistantLlmClient,
                                       AssistantChatProperties properties) {
        this.assistantLlmClient = assistantLlmClient;
        this.properties = properties;
    }

    /**
     * 判断部署者是否已完成聊天模型配置。
     *
     * @return 配置可用时为 true，不调用外部服务
     */
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /**
     * 将当前消息和已完成的历史问答发送给配置的模型服务。
     *
     * @param message 当前用户输入，非空
     * @param history 客户端附带的近期问答，可为空列表
     * @return 模型回复正文
     * @throws LlmException 模型调用失败或响应无有效正文时抛出
     */
    public String chat(String message, List<LlmRequest.ChatTurn> history) {
        return assistantLlmClient.chat(chatRequest(message, history)).content();
    }

    /**
     * 将聊天消息发送给模型，并在文本到达时交付给输入适配器。
     *
     * @param message 当前问题，非空
     * @param history 最近问答，可为空
     * @param onDelta 接收回复片段的回调，非空
     * @throws LlmException 模型调用失败或流式回复不完整时抛出
     */
    public void streamChat(String message, List<LlmRequest.ChatTurn> history, Consumer<String> onDelta) {
        assistantLlmClient.streamChat(chatRequest(message, history), onDelta);
    }

    /**
     * 组装两个聊天入口共用的模型请求，保留原始对话正文。
     *
     * @param message 当前问题
     * @param history 历史问答
     * @return 带有助手系统提示词的模型请求
     */
    private LlmRequest chatRequest(String message, List<LlmRequest.ChatTurn> history) {
        return new LlmRequest(CHAT_SCENE, null, SYSTEM_PROMPT, message,
                properties.model(), null, null, List.of(), history);
    }
}
