package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 16:50:03
 * @Description: 归一化名称库查询结果，聚合规则基础信息、正负关键词和动态历史样本数量
 */
public record NormalizationLibraryItem(
        String ruleCode,
        String normalizedName,
        String category,
        String standardUnit,
        String unitFamily,
        List<String> keywords,
        List<String> excludeKeywords,
        int sampleCount,
        int priority,
        boolean enabled,
        String source
) {
}
