package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.ParsedNormalizationPreview;
import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.ProductRuleMatchResult;
import com.jtxw.familyagent.domain.policy.ProductRuleMatcher;
import com.jtxw.familyagent.domain.policy.ProductRuleProvider;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 20:05:00
 * @Description: OCR 候选归一化预览应用服务，只读复用现有商品规则匹配能力并返回轻量级命中摘要
 */
@Service
public class NormalizationPreviewService {
    /**
     * 未命中归一化规则时的人工确认提示。
     */
    private static final String UNMATCHED_WARNING =
            "未命中归一化规则，请确认是否需要新增 normalization_library 规则或关键词";
    /**
     * 归一化预览异常时的提示前缀。
     */
    private static final String PREVIEW_FAILURE_WARNING_PREFIX = "归一化预览失败：";

    /**
     * 现有商品规则匹配器，负责保持规则优先级和排除词语义一致。
     */
    private final ProductRuleMatcher productRuleMatcher;
    /**
     * 商品规则提供接口，仅用于读取已命中规则的关键词摘要。
     */
    private final ProductRuleProvider productRuleProvider;

    /**
     * 创建 OCR 候选归一化预览服务。
     *
     * @param productRuleMatcher  现有商品规则匹配器
     * @param productRuleProvider 当前启用规则提供接口
     */
    public NormalizationPreviewService(ProductRuleMatcher productRuleMatcher,
                                       ProductRuleProvider productRuleProvider) {
        this.productRuleMatcher = productRuleMatcher;
        this.productRuleProvider = productRuleProvider;
    }

    /**
     * 为候选样本列表逐项补充归一化预览，单项失败不会中断其他候选或 OCR 主流程。
     *
     * @param candidates OCR 解析得到的候选样本列表
     * @return 保持原顺序并附带归一化预览的不可变候选列表
     */
    public List<ParsedPurchaseCandidate> enrich(List<ParsedPurchaseCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream().map(this::enrich).toList();
    }

    /**
     * 为单个候选样本补充归一化预览，不使用 sourceText 或完整 OCR rawText。
     *
     * @param candidate OCR 解析候选样本
     * @return 附带归一化预览的新候选样本
     */
    public ParsedPurchaseCandidate enrich(ParsedPurchaseCandidate candidate) {
        String matchedText = buildMatchedText(candidate);
        try {
            ProductRuleMatchResult matchResult = productRuleMatcher.match(matchedText);
            if (!matchResult.matched()) {
                return candidate.withNormalization(unmatchedPreview(UNMATCHED_WARNING));
            }
            String matchedKeyword = findMatchedKeyword(matchResult.ruleId(), matchedText);
            ParsedNormalizationPreview preview = new ParsedNormalizationPreview(
                    true,
                    matchResult.ruleId(),
                    matchResult.normalizedName(),
                    matchResult.standardUnit(),
                    matchedKeyword,
                    matchedText,
                    null,
                    null
            );
            return candidate.withNormalization(preview);
        } catch (RuntimeException exception) {
            String reason = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            return candidate.withNormalization(unmatchedPreview(PREVIEW_FAILURE_WARNING_PREFIX + reason));
        }
    }

    /**
     * 创建不包含任何规则的预览服务，供兼容构造器和隔离测试使用。
     *
     * @return 所有候选均返回未命中预览的只读服务
     */
    public static NormalizationPreviewService withoutRules() {
        ProductRuleProvider emptyRuleProvider = List::of;
        return new NormalizationPreviewService(new ProductRuleMatcher(emptyRuleProvider), emptyRuleProvider);
    }

    /**
     * 仅使用候选样本的非敏感结构化字段构造规则匹配文本。
     *
     * @param candidate OCR 解析候选样本
     * @return 由商品名、SKU 和店铺名按顺序拼接的文本
     */
    private String buildMatchedText(ParsedPurchaseCandidate candidate) {
        return Arrays.stream(new String[]{candidate.productName(), candidate.sku(), candidate.shopName()})
                .filter(this::hasText)
                .map(String::trim)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    /**
     * 从匹配器已选中规则的正向关键词中提取实际命中的首个关键词。
     *
     * @param ruleCode    匹配器返回的规则编码
     * @param matchedText 参与规则匹配的非敏感候选文本
     * @return 实际命中的首个正向关键词；无法定位时返回 null
     */
    private String findMatchedKeyword(String ruleCode, String matchedText) {
        for (ProductRule rule : productRuleProvider.listEnabledRules()) {
            if (!rule.id().equals(ruleCode)) {
                continue;
            }
            return rule.includeKeywords().stream()
                    .filter(this::hasText)
                    .filter(matchedText::contains)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    /**
     * 创建未命中或无法判断的归一化预览。
     *
     * @param warning 未命中或预览失败提示
     * @return 不包含规则详情的未命中预览
     */
    private ParsedNormalizationPreview unmatchedPreview(String warning) {
        return new ParsedNormalizationPreview(false, null, null, null,
                null, null, null, warning);
    }

    /**
     * 判断文本是否包含非空白字符。
     *
     * @param text 待判断文本，允许为空
     * @return 文本非空且非空白时返回 true
     */
    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
