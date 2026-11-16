package com.jtxw.familyagent.domain.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/11/11 13:20:37
 * @Description: 归一化名称库查询结果，聚合规则信息、关键词、有效样本数量和标准单位下的历史单价中位数
 * @param ruleCode 规则业务编码，不允许为空
 * @param normalizedName 归一化商品名称，不允许为空
 * @param category 商品品类，允许为空
 * @param standardUnit 标准计量单位，用于解释单价中位数，允许为空
 * @param unitFamily 单位族数据库编码，允许为空
 * @param keywords 启用的正向关键词，无匹配词时为空列表
 * @param excludeKeywords 启用的排除关键词，无排除词时为空列表
 * @param sampleCount 有效历史样本数量，最小为 0，沿用现有全家庭统计口径
 * @param priority 规则优先级，值越大越优先
 * @param enabled 是否启用规则
 * @param source 规则来源，例如 system 或 manual，允许为空
 * @param medianUnitPrice 当前标准单位下的有效历史单价中位数，单位为元/standardUnit；无样本时为 null
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
        String source,
        BigDecimal medianUnitPrice
) {
    /**
     * 兼容不提供单价中位数的既有调用方，未计算时使用 null，不能当作零元展示。
     *
     * @param ruleCode 规则编码
     * @param normalizedName 归一化名称
     * @param category 商品品类
     * @param standardUnit 标准单位
     * @param unitFamily 单位族
     * @param keywords 正向关键词
     * @param excludeKeywords 排除关键词
     * @param sampleCount 有效样本数量
     * @param priority 规则优先级
     * @param enabled 是否启用
     * @param source 规则来源
     */
    public NormalizationLibraryItem(String ruleCode, String normalizedName, String category,
                                    String standardUnit, String unitFamily, List<String> keywords,
                                    List<String> excludeKeywords, int sampleCount, int priority,
                                    boolean enabled, String source) {
        this(ruleCode, normalizedName, category, standardUnit, unitFamily, keywords, excludeKeywords,
                sampleCount, priority, enabled, source, null);
    }
}
