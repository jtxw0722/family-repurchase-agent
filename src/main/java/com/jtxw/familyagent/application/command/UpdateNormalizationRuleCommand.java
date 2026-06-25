package com.jtxw.familyagent.application.command;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/25 18:47:34
 * @Description: 更新归一化规则快照命令，支持更新规则主表字段并按需同步 include / exclude 关键词
 *
 * @param ruleCode       规则业务编码，来自路径参数，不允许修改
 * @param normalizedName 归一化商品名称，要求唯一
 * @param category       商品品类，允许为空
 * @param standardUnit   标准统计单位
 * @param unitFamily     单位族文本，兼容小写入参
 * @param priority       规则优先级，数值越大优先级越高
 * @param enabled        是否启用规则
 * @param keywords       include 关键词快照；null 表示不修改，空列表表示清空
 * @param excludeKeywords exclude 关键词快照；null 表示不修改，空列表表示清空
 */
public record UpdateNormalizationRuleCommand(
        String ruleCode,
        String normalizedName,
        String category,
        String standardUnit,
        String unitFamily,
        Integer priority,
        Boolean enabled,
        List<String> keywords,
        List<String> excludeKeywords
) {
    /**
     * 兼容只更新规则主表字段的调用场景。
     *
     * @param ruleCode       规则业务编码
     * @param normalizedName 归一化商品名称
     * @param category       商品品类
     * @param standardUnit   标准统计单位
     * @param unitFamily     单位族文本
     * @param priority       规则优先级
     * @param enabled        是否启用规则
     */
    public UpdateNormalizationRuleCommand(String ruleCode,
                                          String normalizedName,
                                          String category,
                                          String standardUnit,
                                          String unitFamily,
                                          Integer priority,
                                          Boolean enabled) {
        this(ruleCode, normalizedName, category, standardUnit, unitFamily, priority, enabled, null, null);
    }

    /**
     * 创建更新归一化规则快照命令。
     *
     * <p>列表字段只做不可变拷贝，保留 null 和空列表差异。</p>
     */
    public UpdateNormalizationRuleCommand {
        keywords = keywords == null ? null : keywords.stream().toList();
        excludeKeywords = excludeKeywords == null ? null : excludeKeywords.stream().toList();
    }
}
