package com.jtxw.familyagent.domain.policy;

import org.springframework.stereotype.Component;

/**
 * @Author: jtxw
 * @Date: 2026/06/05
 * @Description: 商品名称归一化包装组件，委托规则归一化器并对低置信结果补充复核兜底。
 */
@Component
public class LearningProductNameNormalizer {
    private static final double MIN_TRUSTED_CONFIDENCE = 0.8D;

    private final ProductNameNormalizer delegate;

    public LearningProductNameNormalizer(ProductNameNormalizer delegate) {
        this.delegate = delegate;
    }

    /**
     * 执行商品名称归一化并补充低置信复核兜底。
     *
     * <p>当前主链路依赖归一化规则；当结果为 legacy_fallback 或置信度低于阈值时，
     * 标记为需要人工复核，避免低置信样本直接进入价格基准。</p>
     *
     * @param productName 原始商品标题
     * @param sku         商品 SKU
     * @return 商品归一化结果
     */
    public ProductNameNormalizationResult normalize(String productName, String sku) {
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
}
