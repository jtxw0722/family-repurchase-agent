package com.jtxw.familyagent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestion;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestionResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 规则维护建议 LLM 输出解析器，负责将模型 JSON 输出转换为结构化建议并兼容代码块包裹
 */
@Service
public class NormalizationRuleSuggestionOutputParser {
    /**
     * JSON 解析组件，用于读取模型输出。
     */
    private final ObjectMapper objectMapper;

    /**
     * 创建规则维护建议输出解析器。
     *
     * @param objectMapper JSON 解析组件，不能为空
     */
    public NormalizationRuleSuggestionOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 LLM 返回的规则维护建议 JSON。
     *
     * @param content LLM message.content 中的 JSON 文本
     * @return 结构化规则维护建议结果
     * @throws JsonProcessingException JSON 语法非法时抛出
     */
    public NormalizationRuleSuggestionResult parse(String content) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripJsonFence(content));
        JsonNode suggestionsNode = root.path("suggestions");
        if (!suggestionsNode.isArray()) {
            throw new IllegalStateException("LLM 输出缺少 suggestions 数组");
        }
        List<NormalizationRuleSuggestion> suggestions = new ArrayList<>();
        for (JsonNode suggestionNode : suggestionsNode) {
            if (suggestionNode.isObject()) {
                suggestions.add(toSuggestion(suggestionNode));
            }
        }
        return new NormalizationRuleSuggestionResult(suggestions, textArray(root, "warnings"));
    }

    private NormalizationRuleSuggestion toSuggestion(JsonNode node) {
        return new NormalizationRuleSuggestion(
                text(node, "operation"),
                text(node, "ruleCode"),
                text(node, "normalizedName"),
                text(node, "category"),
                text(node, "standardUnit"),
                text(node, "unitFamily"),
                integer(node, "priority"),
                textArray(node, "keywords"),
                textArray(node, "excludeKeywords"),
                text(node, "keyword"),
                text(node, "matchType"),
                decimal(node, "confidence"),
                text(node, "reason"),
                textArray(node, "evidence"),
                false,
                false,
                List.of()
        );
    }

    private String text(JsonNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        return node.isTextual() ? node.asText().trim() : null;
    }

    private Integer integer(JsonNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        return node.isIntegralNumber() && node.canConvertToInt() ? node.asInt() : null;
    }

    private Double decimal(JsonNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        return node.isNumber() ? node.asDouble() : null;
    }

    private List<String> textArray(JsonNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode itemNode : node) {
            if (itemNode.isTextual() && !itemNode.asText().isBlank()) {
                values.add(itemNode.asText().trim());
            }
        }
        return values;
    }

    private String stripJsonFence(String content) {
        String trimmedContent = content == null ? "" : content.trim();
        if (!trimmedContent.startsWith("```")) {
            return trimmedContent;
        }
        String withoutOpeningFence = trimmedContent.replaceFirst("^```[a-zA-Z]*\\s*", "");
        return withoutOpeningFence.replaceFirst("\\s*```$", "").trim();
    }
}
