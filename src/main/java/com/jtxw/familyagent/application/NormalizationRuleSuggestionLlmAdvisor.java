package com.jtxw.familyagent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestionCandidate;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestionResult;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 规则维护建议 LLM Advisor，负责构建规则缺口分析 prompt、调用 OpenAI-compatible LLM 并解析结构化建议
 */
@Service
public class NormalizationRuleSuggestionLlmAdvisor implements NormalizationRuleSuggestionAdvisor {
    /**
     * LLM request body 扩展字段禁止覆盖的核心字段。
     */
    private static final List<String> FORBIDDEN_EXTRA_KEYS = List.of("model", "messages", "stream");
    /**
     * 商品归一化配置，复用现有 LLM provider、模型、API Key 和超时配置。
     */
    private final NormalizationProperties normalizationProperties;
    /**
     * JSON 序列化组件，用于构建请求和解析响应。
     */
    private final ObjectMapper objectMapper;
    /**
     * prompt 渲染器，负责读取规则维护建议专用 prompt。
     */
    private final NormalizationRuleSuggestionPromptRenderer promptRenderer;
    /**
     * LLM 原始调用客户端，负责发送 OpenAI-compatible HTTP 请求。
     */
    private final LlmClient llmClient;
    /**
     * LLM 输出解析器，负责将 JSON 输出转换为规则维护建议。
     */
    private final NormalizationRuleSuggestionOutputParser outputParser;

    /**
     * 创建规则维护建议 LLM Advisor。
     *
     * @param normalizationProperties 归一化配置，不能为空
     * @param objectMapper            JSON 序列化组件，不能为空
     * @param promptRenderer          prompt 渲染器，不能为空
     * @param llmClient               LLM 客户端，不能为空
     * @param outputParser            输出解析器，不能为空
     */
    public NormalizationRuleSuggestionLlmAdvisor(NormalizationProperties normalizationProperties,
                                                 ObjectMapper objectMapper,
                                                 NormalizationRuleSuggestionPromptRenderer promptRenderer,
                                                 LlmClient llmClient,
                                                 NormalizationRuleSuggestionOutputParser outputParser) {
        this.normalizationProperties = normalizationProperties;
        this.objectMapper = objectMapper;
        this.promptRenderer = promptRenderer;
        this.llmClient = llmClient;
        this.outputParser = outputParser;
    }

    @Override
    public NormalizationRuleSuggestionResult advise(List<NormalizationRuleSuggestionCandidate> candidates,
                                                    List<NormalizationLibraryItem> libraryItems) {
        NormalizationProperties.Llm llm = normalizationProperties.getLlm();
        if (!llm.isEnabled()) {
            throw new IllegalStateException("LLM normalization advisor 未启用");
        }
        if (llm.getApiKey() == null || llm.getApiKey().isBlank()) {
            throw new IllegalStateException("LLM normalization advisor 缺少 API Key");
        }
        if (llm.getBaseUrl() == null || llm.getBaseUrl().isBlank()) {
            throw new IllegalStateException("LLM normalization advisor 缺少 baseUrl");
        }
        if (!isOpenAiCompatibleProvider(llm.getProvider())) {
            throw new IllegalStateException("暂不支持的 LLM provider：" + llm.getProvider());
        }
        try {
            String inputJson = objectMapper.writeValueAsString(Map.of(
                    "candidates", candidates,
                    "normalizationLibrary", libraryItems
            ));
            String requestBody = requestBody(llm, inputJson);
            LlmClientResponse response = llmClient.chatCompletion(new LlmClientRequest(
                    llm.getBaseUrl(), llm.getApiKey(), llm.getRequestTimeoutSeconds(), requestBody));
            return outputParser.parse(extractModelOutput(response.body()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规则维护建议 LLM 输出解析失败", e);
        }
    }

    private String requestBody(NormalizationProperties.Llm llm, String inputJson) throws JsonProcessingException {
        String systemPrompt = promptRenderer.getSystemPrompt();
        String userPrompt = promptRenderer.renderUserPrompt(inputJson);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", llm.getModel());
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        if (llm.getMaxTokens() > 0) {
            request.put("max_tokens", llm.getMaxTokens());
        }
        mergeExtraBodyJson(request, llm.getExtraBodyJson());
        return objectMapper.writeValueAsString(request);
    }

    private String extractModelOutput(String responseText) throws JsonProcessingException {
        if (responseText == null || responseText.isBlank()) {
            throw new IllegalStateException("LLM 返回空响应");
        }
        String trimmedResponse = stripJsonFence(responseText.trim());
        JsonNode root = objectMapper.readTree(trimmedResponse);
        if (root.path("suggestions").isArray()) {
            return trimmedResponse;
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            String content = choices.get(0).path("message").path("content").asText("");
            if (!content.isBlank()) {
                return stripJsonFence(content);
            }
        }
        JsonNode output = root.path("output");
        if (output.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode outputItem : output) {
                JsonNode contentItems = outputItem.path("content");
                if (!contentItems.isArray()) {
                    continue;
                }
                for (JsonNode contentItem : contentItems) {
                    String text = contentItem.path("text").asText("");
                    if (!text.isBlank()) {
                        builder.append(text);
                    }
                }
            }
            if (!builder.isEmpty()) {
                return stripJsonFence(builder.toString());
            }
        }
        throw new IllegalStateException("LLM 响应缺少规则维护建议内容");
    }

    private void mergeExtraBodyJson(Map<String, Object> request, String extraBodyJson) throws JsonProcessingException {
        if (extraBodyJson == null || extraBodyJson.isBlank()) {
            return;
        }
        JsonNode extraRoot = objectMapper.readTree(extraBodyJson);
        if (!extraRoot.isObject()) {
            throw new IllegalArgumentException("NORMALIZATION_LLM_EXTRA_BODY_JSON 必须是 JSON object");
        }
        for (Map.Entry<String, JsonNode> field : extraRoot.properties()) {
            if (FORBIDDEN_EXTRA_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("NORMALIZATION_LLM_EXTRA_BODY_JSON 不能覆盖核心字段：" + field.getKey());
            }
            String serializedValue = objectMapper.writeValueAsString(field.getValue());
            if (serializedValue.toLowerCase(Locale.ROOT).contains("bearer ")
                    || serializedValue.toLowerCase(Locale.ROOT).contains("sk-")) {
                throw new IllegalArgumentException("NORMALIZATION_LLM_EXTRA_BODY_JSON 不能包含密钥或鉴权值");
            }
            request.put(field.getKey(), field.getValue());
        }
    }

    private boolean isOpenAiCompatibleProvider(String provider) {
        String value = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        return value.equals("openai") || value.equals("openai-compatible");
    }

    private String stripJsonFence(String content) {
        String trimmedContent = content.trim();
        if (!trimmedContent.startsWith("```")) {
            return trimmedContent;
        }
        String withoutOpeningFence = trimmedContent.replaceFirst("^```[a-zA-Z]*\\s*", "");
        return withoutOpeningFence.replaceFirst("\\s*```$", "").trim();
    }
}
