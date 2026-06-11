package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.NormalizationAdvisorRequest;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorResult;
import com.jtxw.familyagent.infrastructure.config.NormalizationProperties;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.UnaryOperator;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 15:41:34
 * @Description: LLM 商品归一化输出校验器，负责字段合法性校验、展示原因归一化和结果级降级。
 */
@Service
public class LlmNormalizationItemValidator {
    /**
     * LLM 允许返回的动作集合，其他值会被兜底为 REVIEW。
     */
    private static final Set<String> ALLOWED_ACTIONS = Set.of("NORMALIZE", "EXCLUDE", "NEW_CATEGORY", "REVIEW");
    /**
     * LLM 允许返回的商品类型集合，其他值会被兜底为 UNKNOWN。
     */
    private static final Set<String> ALLOWED_PRODUCT_TYPES = Set.of(
            "REPURCHASE_CONSUMABLE", "NON_REPURCHASE", "DURABLE", "COUPON_OR_DEPOSIT", "UNKNOWN");
    /**
     * LLM 允许返回的单位族集合，其他值会被兜底为 UNKNOWN。
     */
    private static final Set<String> ALLOWED_UNIT_FAMILIES = Set.of("WEIGHT", "VOLUME", "COUNT", "PIECE", "UNKNOWN");
    /**
     * compact schema 中 shortReason 的最大保存长度，避免长推理文本进入建议表。
     */
    private static final int SHORT_REASON_MAX_LENGTH = 24;
    /**
     * 兼容 legacy schema 时 reason 的最大保存长度，避免旧模型输出长段解释。
     */
    private static final int LEGACY_REASON_MAX_LENGTH = 80;
    /**
     * LLM reasonCode 展示文案映射；只用于压缩后的原因入库，不参与业务分类决策。
     */
    private static final Map<String, String> REASON_CODE_MESSAGES = reasonCodeMessages();

    /**
     * 归一化配置，提供低置信排除降级阈值。
     */
    private final NormalizationProperties normalizationProperties;

    /**
     * 构造 LLM 输出校验器。
     *
     * @param normalizationProperties 归一化配置，包含 reviewConfidenceThreshold
     */
    public LlmNormalizationItemValidator(NormalizationProperties normalizationProperties) {
        this.normalizationProperties = normalizationProperties;
    }

    /**
     * 返回 prompt 可展示的 reasonCode 集合，确保 prompt contract 与运行时映射一致。
     *
     * @return 支持的 reasonCode 集合
     */
    public static Set<String> reasonCodes() {
        return REASON_CODE_MESSAGES.keySet();
    }

    /**
     * 将 DTO 转换为最终建议结果，并在业务纠偏后执行结果级安全降级。
     *
     * @param item      LLM 单条输出 DTO
     * @param request   当前请求，用于 compact schema 回填商品名和 SKU
     * @param corrector 业务纠偏回调，由 Advisor 保留分类纠偏职责
     * @return 单条商品归一化建议结果
     */
    public NormalizationAdvisorResult toAdvisorResult(LlmNormalizationItem item,
                                                      NormalizationAdvisorRequest request,
                                                      UnaryOperator<NormalizationAdvisorResult> corrector) {
        NormalizationAdvisorResult baseResult = new NormalizationAdvisorResult(
                text(item.rawProductName(), request.productName()),
                text(item.sku(), request.sku()),
                allowed(item.action(), ALLOWED_ACTIONS, "REVIEW"),
                firstText(item.normalizedName(), item.suggestedNormalizedName()),
                nullableText(item.rejectedNormalizedName()),
                allowed(item.productType(), ALLOWED_PRODUCT_TYPES, "UNKNOWN"),
                nullableText(item.targetUnit()),
                allowed(item.unitFamily(), ALLOWED_UNIT_FAMILIES, "UNKNOWN"),
                confidence(item.confidence()),
                item.reviewRequired() == null || item.reviewRequired(),
                displayReason(item),
                List.of(),
                nullableText(item.reasonCode()),
                nullableText(item.shortReason()),
                false
        );
        NormalizationAdvisorResult correctedResult = corrector.apply(baseResult);
        return applyResultLevelFallback(correctedResult);
    }

    /**
     * 创建单条失败兜底结果，避免结构问题扩大为批次级失败。
     *
     * @param request 当前请求
     * @param reason  失败原因
     * @return failed=true 的 REVIEW 建议
     */
    public NormalizationAdvisorResult failedResult(NormalizationAdvisorRequest request, String reason) {
        return new NormalizationAdvisorResult(request.productName(), request.sku(), "REVIEW", null, null, "UNKNOWN",
                null, "UNKNOWN", 0.5D, true, reason, List.of(reason), true);
    }

    /**
     * 对单条建议结果执行结果级兜底。
     *
     * <p>该方法不处理 JSON 语法失败，JSON 语法失败由批次入口统一降级。这里仅处理字段已经解析后的业务安全边界：
     * NORMALIZE 缺少标准名称时降级为 REVIEW；低置信度的 EXCLUDE + UNKNOWN 也降级为 REVIEW，避免误排除。</p>
     *
     * @param result 已完成基础字段校验和业务纠偏的建议结果
     * @return 应用结果级兜底后的建议结果
     */
    private NormalizationAdvisorResult applyResultLevelFallback(NormalizationAdvisorResult result) {
        String action = result.action();
        boolean reviewRequired = result.reviewRequired();
        String reason = result.reason();
        if ("NORMALIZE".equals(action) && isBlank(result.suggestedNormalizedName())) {
            action = "REVIEW";
            reviewRequired = true;
            reason = reason + "；NORMALIZE 缺少 suggestedNormalizedName，已降级 REVIEW";
        }
        if ("EXCLUDE".equals(action) && "UNKNOWN".equals(result.productType())
                && result.confidence() < normalizationProperties.getLlm().getReviewConfidenceThreshold()) {
            action = "REVIEW";
            reviewRequired = true;
            reason = reason + "；EXCLUDE 商品类型未知且置信度不足，已降级 REVIEW";
        }
        return new NormalizationAdvisorResult(result.rawProductName(), result.sku(), action,
                result.suggestedNormalizedName(), result.rejectedNormalizedName(), result.productType(),
                result.targetUnit(), result.unitFamily(), result.confidence(), reviewRequired, reason,
                result.evidence(), result.reasonCode(), result.shortReason(), result.failed());
    }

    /**
     * 生成最终展示和入库使用的原因文本。
     *
     * <p>原因选择优先级为：reasonCode 映射文案优先，其次 shortReason，再其次 legacy reason，
     * 最后兜底为“需要人工复核”。shortReason 和 legacy reason 会按各自长度上限截断。</p>
     *
     * @param item LLM 单条输出 DTO
     * @return 归一化后的原因文本
     */
    private String displayReason(LlmNormalizationItem item) {
        String reasonCode = nullableText(item.reasonCode());
        String shortReason = nullableText(item.shortReason());
        if (!isBlank(reasonCode)) {
            String message = REASON_CODE_MESSAGES.get(reasonCode.trim().toUpperCase(Locale.ROOT));
            if (message != null) {
                return message;
            }
            return isBlank(shortReason) ? "需要人工复核" : truncate(shortReason, SHORT_REASON_MAX_LENGTH);
        }
        if (!isBlank(shortReason)) {
            return truncate(shortReason, SHORT_REASON_MAX_LENGTH);
        }
        String legacyReason = nullableText(item.reason());
        if (!isBlank(legacyReason)) {
            return truncate(legacyReason, LEGACY_REASON_MAX_LENGTH);
        }
        return "需要人工复核";
    }

    /**
     * 校验枚举字符串是否在白名单中。
     *
     * @param value         模型返回的枚举字符串
     * @param allowedValues 允许的枚举值集合
     * @param fallback      空值或非法值时使用的兜底值
     * @return 大写归一化后的合法枚举值，或 fallback
     */
    private String allowed(String value, Set<String> allowedValues, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return allowedValues.contains(normalized) ? normalized : fallback;
    }

    /**
     * 校验模型返回的置信度。
     *
     * @param value 模型返回的置信度
     * @return 0 到 1 之间的置信度；空值、负数或大于 1 时兜底为 0.5D
     */
    private double confidence(Double value) {
        if (value == null) {
            return 0.5D;
        }
        return value < 0D || value > 1D ? 0.5D : value;
    }

    /**
     * 读取文本字段并提供请求侧兜底。
     *
     * @param value    DTO 中的文本字段
     * @param fallback 字段为空白时使用的兜底文本
     * @return 去除首尾空白后的文本；字段为空白时返回 fallback
     */
    private String text(String value, String fallback) {
        String normalizedValue = nullableText(value);
        return isBlank(normalizedValue) ? fallback : normalizedValue;
    }

    /**
     * 归一化可空文本字段。
     *
     * @param value DTO 中的文本字段
     * @return 去除首尾空白后的文本；原始值为 null 时返回 null
     */
    private String nullableText(String value) {
        return value == null ? null : value.trim();
    }

    /**
     * 在 compact schema 和 legacy schema 的标准名称字段之间选择第一个有效值。
     *
     * @param firstValue  compact schema 的 normalizedName
     * @param secondValue legacy schema 的 suggestedNormalizedName
     * @return 第一个非空白文本；都为空时返回 null 或空白归一化结果
     */
    private String firstText(String firstValue, String secondValue) {
        String normalizedFirstValue = nullableText(firstValue);
        return isBlank(normalizedFirstValue) ? nullableText(secondValue) : normalizedFirstValue;
    }

    /**
     * 截断原因文本，避免长推理内容进入建议结果。
     *
     * @param text      原始原因文本
     * @param maxLength 最大保留字符数
     * @return 去除首尾空白并按长度上限截断后的文本；原始值为 null 时返回空字符串
     */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String trimmedText = text.trim();
        return trimmedText.length() <= maxLength ? trimmedText : trimmedText.substring(0, maxLength);
    }

    /**
     * 判断文本是否为空白。
     *
     * @param text 待判断文本
     * @return null 或空白字符串时返回 true
     */
    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * 集中维护运行时 reasonCode 到展示文案的映射。
     *
     * <p>该映射用于 displayReason，也通过 reasonCodes() 暴露给 prompt contract，
     * 确保模型可选 reasonCode 与运行时识别能力保持一致。</p>
     *
     * @return 不可变的 reasonCode 展示文案映射
     */
    private static Map<String, String> reasonCodeMessages() {
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("CAT_MAIN_FOOD", "猫主食罐消耗品");
        messages.put("CAT_SNACK", "猫零食消耗品");
        messages.put("CAT_SOUP_AMBIGUOUS", "猫汤包归类需复核");
        messages.put("PERSONAL_CARE", "个人护理消耗品");
        messages.put("COLOR_COSMETIC_REVIEW", "色号彩妆需复核");
        messages.put("FOOD_REVIEW", "食品是否纳入需复核");
        messages.put("DURABLE_CLOTHING", "服饰耐用品排除");
        messages.put("DURABLE_ACCESSORY", "饰品耐用品排除");
        messages.put("DURABLE_GOODS", "耐用品排除");
        messages.put("COUPON_OR_DEPOSIT", "支付权益类排除");
        messages.put("REAL_PRODUCT_WITH_DEPOSIT", "真实商品含预售付定需复核");
        messages.put("UNIT_UNSAFE", "单位不安全需复核");
        messages.put("UNKNOWN_REVIEW", "无法判断需复核");
        return Map.copyOf(messages);
    }
}
