package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 规则维护建议候选商品样本，只承载允许发送给 LLM 的脱敏商品字段
 *
 * @param productName       商品原始名称，来自 purchase_records.product_name，不包含价格和订单信息
 * @param sku               商品规格或 SKU，允许为空
 * @param category          电商一级分类，允许为空
 * @param subCategory       电商二级分类，允许为空
 * @param normalizationRule 当前导入时命中的归一化规则，允许为空
 */
public record NormalizationRuleSuggestionCandidate(String productName,
                                                   String sku,
                                                   String category,
                                                   String subCategory,
                                                   String normalizationRule) {
}
