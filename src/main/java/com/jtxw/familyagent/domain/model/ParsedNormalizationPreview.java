package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 18:18:00
 * @Description: OCR 候选样本的归一化预览结果，用于说明候选样本后续可能命中的归一化规则
 *
 * @param matched        是否命中归一化规则
 * @param ruleCode       命中的规则编码，未命中时为空
 * @param normalizedName 归一化名称，未命中时为空
 * @param targetUnit     规则目标统计单位，未命中时为空
 * @param matchedKeyword 命中的关键词，无法识别时为空
 * @param matchedText    由商品名、SKU 和店铺名构造的匹配文本，不包含完整 OCR 原文
 * @param sampleCount    当前归一化名称下的历史样本数量，无法低成本获取时为空
 * @param warning        未命中或无法判断时的提示，正常命中时为空
 */
public record ParsedNormalizationPreview(
        boolean matched,
        String ruleCode,
        String normalizedName,
        String targetUnit,
        String matchedKeyword,
        String matchedText,
        Integer sampleCount,
        String warning
) {
}
