package com.jtxw.familyagent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorRequest;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 15:30:53
 * @Description: LLM 商品归一化输出解析器，负责 JSON 解析、DTO 转换、index 对齐和基础结构降级。
 */
@Service
public class LlmNormalizationOutputParser {
    /**
     * JSON 解析组件，用于读取模型输出。
     */
    private final ObjectMapper objectMapper;
    /**
     * 单条 DTO 校验器，负责字段合法性和结果级降级。
     */
    private final LlmNormalizationItemValidator itemValidator;

    /**
     * 构造 LLM 输出解析器。
     *
     * @param objectMapper  JSON 解析组件
     * @param itemValidator 单条 DTO 校验器
     */
    public LlmNormalizationOutputParser(ObjectMapper objectMapper, LlmNormalizationItemValidator itemValidator) {
        this.objectMapper = objectMapper;
        this.itemValidator = itemValidator;
    }

    /**
     * 解析 LLM 返回的批量 JSON Array。
     *
     * @param content  LLM message.content 中的 JSON Array 文本
     * @param requests 当前批次输入商品列表
     * @return 与请求顺序一致的建议结果列表
     * @throws JsonProcessingException JSON 语法非法时抛出，由 Advisor 统一降级为批次级 failed fallback
     */
    public List<NormalizationAdvisorResult> parseBatchContent(String content,
                                                              List<NormalizationAdvisorRequest> requests)
            throws JsonProcessingException {
        return parseBatchContent(content, requests, UnaryOperator.identity());
    }

    /**
     * 解析 LLM 返回的批量 JSON Array，并在基础字段校验后执行业务纠偏回调。
     *
     * @param content   LLM message.content 中的 JSON Array 文本
     * @param requests  当前批次输入商品列表
     * @param corrector 业务纠偏回调，由 Advisor 持有分类纠偏规则
     * @return 与请求顺序一致的建议结果列表
     * @throws JsonProcessingException JSON 语法非法时抛出，由 Advisor 统一降级为批次级 failed fallback
     */
    List<NormalizationAdvisorResult> parseBatchContent(String content,
                                                       List<NormalizationAdvisorRequest> requests,
                                                       UnaryOperator<NormalizationAdvisorResult> corrector)
            throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripJsonFence(content));
        JsonNode arrayNode = resultArray(root);
        if (!arrayNode.isArray()) {
            throw new IllegalStateException("LLM 输出不是 JSON Array");
        }
        if (containsCompactIndex(arrayNode) || looksLikeCompactSchema(arrayNode)) {
            return parseIndexedResults(arrayNode, requests, corrector);
        }
        return parseSequentialResults(arrayNode, requests, corrector);
    }

    /**
     * 将模型输出 JSON 文本解析为 DTO 列表。
     *
     * @param content LLM message.content 中的 JSON Array 文本
     * @return DTO 列表，非 object 条目会保留为空 DTO 占位
     * @throws JsonProcessingException JSON 语法非法时抛出
     */
    public List<LlmNormalizationItem> parseItems(String content) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripJsonFence(content));
        JsonNode arrayNode = resultArray(root);
        if (!arrayNode.isArray()) {
            throw new IllegalStateException("LLM 输出不是 JSON Array");
        }
        List<LlmNormalizationItem> items = new ArrayList<>();
        for (JsonNode itemNode : arrayNode) {
            items.add(toItem(itemNode));
        }
        return items;
    }

    /**
     * 按 compact schema 解析批量结果。
     *
     * <p>compact schema 使用 1-based index 对齐请求列表，允许模型乱序返回。缺失、重复、越界或非法 index
     * 不扩大为批次失败，而是让对应请求位置生成 failed 结果；非 object 条目按当前位置生成单条 failed 结果。</p>
     *
     * @param arrayNode compact schema 输出数组
     * @param requests  当前批次请求列表
     * @param corrector Advisor 提供的业务纠偏回调
     * @return 与请求顺序一致的建议结果列表
     */
    private List<NormalizationAdvisorResult> parseIndexedResults(JsonNode arrayNode,
                                                                 List<NormalizationAdvisorRequest> requests,
                                                                 UnaryOperator<NormalizationAdvisorResult> corrector) {
        List<NormalizationAdvisorResult> results = emptyResults(requests.size());
        Set<Integer> usedIndexes = new HashSet<>();
        int itemOrdinal = 0;
        for (JsonNode itemNode : arrayNode) {
            if (!itemNode.isObject()) {
                if (itemOrdinal < requests.size() && !usedIndexes.contains(itemOrdinal)) {
                    usedIndexes.add(itemOrdinal);
                    results.set(itemOrdinal, itemValidator.failedResult(requests.get(itemOrdinal),
                            "LLM 返回条目不是 JSON object"));
                }
                itemOrdinal++;
                continue;
            }
            LlmNormalizationItem item = toItem(itemNode);
            int requestIndex = requestIndex(item.index());
            if (requestIndex < 0 || requestIndex >= requests.size() || usedIndexes.contains(requestIndex)) {
                itemOrdinal++;
                continue;
            }
            // compact schema 必须由 index 回填原始商品，避免模型顺序漂移时把 A 商品建议写到 B 商品。
            usedIndexes.add(requestIndex);
            NormalizationAdvisorRequest request = requests.get(requestIndex);
            results.set(requestIndex, itemValidator.toAdvisorResult(item, request, corrector));
            itemOrdinal++;
        }
        for (int index = 0; index < requests.size(); index++) {
            if (results.get(index) == null) {
                results.set(index, itemValidator.failedResult(requests.get(index), "LLM 未返回该商品的有效 index 结果"));
            }
        }
        return results;
    }

    /**
     * 按 legacy schema 解析批量结果。
     *
     * <p>legacy schema 不依赖 index，按数组顺序回填请求列表。输出数量不足或某个条目不是 JSON object 时，
     * 只为对应请求生成单条 failed 结果，不影响同批次其他条目。</p>
     *
     * @param arrayNode legacy schema 输出数组
     * @param requests  当前批次请求列表
     * @param corrector Advisor 提供的业务纠偏回调
     * @return 与请求顺序一致的建议结果列表
     */
    private List<NormalizationAdvisorResult> parseSequentialResults(JsonNode arrayNode,
                                                                    List<NormalizationAdvisorRequest> requests,
                                                                    UnaryOperator<NormalizationAdvisorResult> corrector) {
        List<NormalizationAdvisorResult> results = new ArrayList<>();
        for (int index = 0; index < requests.size(); index++) {
            NormalizationAdvisorRequest request = requests.get(index);
            if (index >= arrayNode.size()) {
                results.add(itemValidator.failedResult(request, "LLM 输出数量少于请求数量"));
                continue;
            }
            JsonNode itemNode = arrayNode.get(index);
            if (!itemNode.isObject()) {
                results.add(itemValidator.failedResult(request, "LLM 返回条目不是 JSON object"));
                continue;
            }
            results.add(itemValidator.toAdvisorResult(toItem(itemNode), request, corrector));
        }
        return results;
    }

    /**
     * 创建固定长度的结果占位列表。
     *
     * @param size 当前批次请求数量
     * @return 长度为 size、初始值为 null 的结果列表，用于 compact schema 按 index 回填
     */
    private List<NormalizationAdvisorResult> emptyResults(int size) {
        List<NormalizationAdvisorResult> results = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            results.add(null);
        }
        return results;
    }

    /**
     * 判断输出数组是否包含 compact schema 的 index 字段。
     *
     * @param arrayNode 模型输出数组
     * @return 任意 object 条目包含 index 字段时返回 true，表示应走 compact schema 解析路径
     */
    private boolean containsCompactIndex(JsonNode arrayNode) {
        for (JsonNode itemNode : arrayNode) {
            if (itemNode.isObject() && !itemNode.path("index").isMissingNode()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断输出数组是否呈现 compact schema 字段形态。
     *
     * @param arrayNode 模型输出数组
     * @return 任意 object 条目包含 normalizedName、reasonCode 或 shortReason 时返回 true
     */
    private boolean looksLikeCompactSchema(JsonNode arrayNode) {
        for (JsonNode itemNode : arrayNode) {
            if (itemNode.isObject() && (!itemNode.path("normalizedName").isMissingNode()
                    || !itemNode.path("reasonCode").isMissingNode()
                    || !itemNode.path("shortReason").isMissingNode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 compact schema 的 1-based index 转换为请求列表的 0-based 下标。
     *
     * @param index 模型返回的 1-based index
     * @return 0-based 请求下标；null 视为非法并返回 -1
     */
    private int requestIndex(Integer index) {
        return index == null ? -1 : index - 1;
    }

    /**
     * 获取实际承载建议结果的 JSON Array。
     *
     * <p>兼容直接返回 JSON Array，也兼容使用 results、items、suggestions 包装数组的代理服务响应。</p>
     *
     * @param root 模型输出根节点
     * @return 结果数组节点；没有匹配 wrapper 时返回 root，由调用方判断是否为数组
     */
    private JsonNode resultArray(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        if (root.path("results").isArray()) {
            return root.path("results");
        }
        if (root.path("items").isArray()) {
            return root.path("items");
        }
        if (root.path("suggestions").isArray()) {
            return root.path("suggestions");
        }
        return root;
    }

    /**
     * 将单条 JSON 节点转换为 LLM 输出 DTO。
     *
     * <p>非 object 条目返回空 DTO 占位；字段读取采用严格类型策略，类型不匹配时返回 null 或空集合，
     * 后续由 validator 负责字段兜底和结果级降级。</p>
     *
     * @param itemNode 模型输出数组中的单个条目
     * @return LlmNormalizationItem DTO
     */
    private LlmNormalizationItem toItem(JsonNode itemNode) {
        if (!itemNode.isObject()) {
            return new LlmNormalizationItem(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, List.of());
        }
        return new LlmNormalizationItem(
                integer(itemNode, "index"),
                nullableText(itemNode, "rawProductName"),
                nullableText(itemNode, "sku"),
                nullableText(itemNode, "action"),
                nullableText(itemNode, "productType"),
                nullableText(itemNode, "normalizedName"),
                nullableText(itemNode, "suggestedNormalizedName"),
                nullableText(itemNode, "rejectedNormalizedName"),
                nullableText(itemNode, "targetUnit"),
                nullableText(itemNode, "unitFamily"),
                decimal(itemNode, "confidence"),
                bool(itemNode, "reviewRequired"),
                nullableText(itemNode, "reasonCode"),
                nullableText(itemNode, "shortReason"),
                nullableText(itemNode, "reason"),
                textArray(itemNode, "evidence")
        );
    }

    /**
     * 严格读取 JSON 整数字段。
     *
     * <p>仅接受 JSON 整数，不接受小数隐式转换；类型不匹配时返回 null，由 index 校验或 validator 兜底。</p>
     *
     * @param root      当前 JSON object
     * @param fieldName 字段名
     * @return 整数字段值；缺失、非整数或超出 int 范围时返回 null
     */
    private Integer integer(JsonNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        return node.isIntegralNumber() && node.canConvertToInt() ? node.asInt() : null;
    }

    /**
     * 严格读取 JSON 数字字段。
     *
     * @param root      当前 JSON object
     * @param fieldName 字段名
     * @return 数字字段值；缺失或类型不匹配时返回 null，由 validator 执行置信度兜底
     */
    private Double decimal(JsonNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        return node.isNumber() ? node.asDouble() : null;
    }

    /**
     * 严格读取 JSON 布尔字段。
     *
     * @param root      当前 JSON object
     * @param fieldName 字段名
     * @return 布尔字段值；缺失或类型不匹配时返回 null，由 validator 决定默认复核策略
     */
    private Boolean bool(JsonNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        return node.isBoolean() ? node.asBoolean() : null;
    }

    /**
     * 严格读取 JSON 文本字段。
     *
     * @param root      当前 JSON object
     * @param fieldName 字段名
     * @return 去除首尾空白后的文本；缺失或类型不匹配时返回 null
     */
    private String nullableText(JsonNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        return node.isTextual() ? node.asText().trim() : null;
    }

    /**
     * 严格读取 JSON 文本数组字段。
     *
     * @param root      当前 JSON object
     * @param fieldName 字段名
     * @return 非空文本元素列表；字段缺失、非数组或元素非文本时忽略并返回空集合或有效文本子集
     */
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

    /**
     * 移除模型输出外层的 Markdown JSON 代码块标记。
     *
     * @param content LLM message.content 原始文本
     * @return 去除代码块围栏后的 JSON 文本；未使用代码块时返回 trim 后内容
     */
    private String stripJsonFence(String content) {
        String trimmedContent = content.trim();
        if (!trimmedContent.startsWith("```")) {
            return trimmedContent;
        }
        String withoutOpeningFence = trimmedContent.replaceFirst("^```[a-zA-Z]*\\s*", "");
        return withoutOpeningFence.replaceFirst("\\s*```$", "").trim();
    }
}
