package com.jtxw.familyagent.domain.policy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/10:38
 * @Description: 商品名称归一化规则，用于降低同类复购品名称差异。
 */
@Component
public class ProductNormalizer {
    private final ProductRuleMatcher productRuleMatcher;

    public ProductNormalizer() {
        this(new ProductRuleMatcher());
    }

    @Autowired
    public ProductNormalizer(ProductRuleMatcher productRuleMatcher) {
        this.productRuleMatcher = productRuleMatcher;
    }

    /**
     * 将原始商品名称归一化为稳定的统计名称。
     *
     * @param productName 原始商品名称
     * @return 归一化商品名称
     */
    public String normalize(String productName) {
        return normalizeProduct(productName).normalizedName();
    }

    /**
     * 返回结构化商品归一化结果，供规格解析分发和后续导入链路使用。
     *
     * @param productName 原始商品名称
     * @return 商品归一化结果
     */
    public ProductNormalizationResult normalizeProduct(String productName) {
        ProductRuleMatchResult matchResult = match(productName);
        return new ProductNormalizationResult(
                matchResult.normalizedName(),
                matchResult.unitFamily(),
                matchResult.standardUnit(),
                false
        );
    }

    /**
     * 返回商品规则匹配结果，供导入和规格解析链路复用。
     *
     * @param productName 原始商品名称
     * @return 商品规则匹配结果
     */
    public ProductRuleMatchResult match(String productName) {
        return productRuleMatcher.match(productName);
    }
}
