package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.infrastructure.persistence.ProductAliasRepository;
import com.jtxw.familyagent.infrastructure.persistence.ProductNegativeAliasRepository;
import org.springframework.stereotype.Component;

/**
 * @Author: jtxw
 * @Date: 2026/06/05
 * @Description: 学习型商品名称归一化组件，基于人工复核沉淀的正向/负向别名增强现有规则。
 */
@Component
public class LearningProductNameNormalizer {
    private static final double MIN_TRUSTED_CONFIDENCE = 0.8D;

    private final ProductTitleCleaner productTitleCleaner;
    private final ProductAliasRepository productAliasRepository;
    private final ProductNegativeAliasRepository productNegativeAliasRepository;
    private final ProductNameNormalizer delegate;

    public LearningProductNameNormalizer(ProductTitleCleaner productTitleCleaner,
                                         ProductAliasRepository productAliasRepository,
                                         ProductNegativeAliasRepository productNegativeAliasRepository,
                                         ProductNameNormalizer delegate) {
        this.productTitleCleaner = productTitleCleaner;
        this.productAliasRepository = productAliasRepository;
        this.productNegativeAliasRepository = productNegativeAliasRepository;
        this.delegate = delegate;
    }

    /**
     * 执行学习增强后的商品归一化。
     *
     * <p>匹配顺序固定为负向别名、正向别名、旧规则。负向别名优先级最高，
     * 用于阻断“猫砂盆”这类曾被误判为“猫砂”的商品再次进入同一价格基准。</p>
     *
     * @param productName 原始商品标题
     * @param sku         商品 SKU
     * @return 商品归一化结果
     */
    public ProductNameNormalizationResult normalize(String productName, String sku) {
        String aliasKey = productTitleCleaner.aliasKey(productName, sku);
        var negativeAlias = productNegativeAliasRepository.findByAliasKey(aliasKey);
        if (negativeAlias.isPresent()) {
            return new ProductNameNormalizationResult(
                    safeNormalizedName(productName),
                    "",
                    0.4D,
                    "product_negative_alias",
                    false
            );
        }

        var positiveAlias = productAliasRepository.findByAliasKey(aliasKey);
        if (positiveAlias.isPresent()) {
            ProductAliasRepository.ProductAlias alias = positiveAlias.get();
            return new ProductNameNormalizationResult(
                    alias.normalizedName(),
                    alias.targetUnit(),
                    0.98D,
                    "product_alias",
                    false
            );
        }

        ProductNameNormalizationResult result = delegate.normalize(productName, sku);
        if ("legacy_fallback".equals(result.matchedRule()) || result.confidence() < MIN_TRUSTED_CONFIDENCE) {
            return new ProductNameNormalizationResult(
                    result.normalizedName(),
                    result.targetUnit(),
                    result.confidence(),
                    result.matchedRule(),
                    true
            );
        }
        return result;
    }

    private String safeNormalizedName(String productName) {
        return productName == null || productName.isBlank() ? "未归一化商品" : productName.trim();
    }
}
