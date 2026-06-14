package com.jtxw.familyagent.application.command;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 21:48:04
 * @Description: 新增归一化规则命令，承载规则基础信息和初始 include / exclude 关键词
 *
 * @param ruleCode        规则业务编码，要求唯一且只能包含小写字母、数字和下划线
 * @param normalizedName  归一化商品名称，要求唯一
 * @param category        商品品类，允许为空
 * @param standardUnit    标准统计单位
 * @param unitFamily      单位族文本，兼容小写入参
 * @param priority        规则优先级，数值越大优先级越高
 * @param keywords        初始 include 关键词列表，允许为空
 * @param excludeKeywords 初始 exclude 关键词列表，允许为空
 */
public record CreateNormalizationRuleCommand(
        String ruleCode,
        String normalizedName,
        String category,
        String standardUnit,
        String unitFamily,
        int priority,
        List<String> keywords,
        List<String> excludeKeywords
) {
    /**
     * 创建新增规则命令。
     *
     * <p>关键词列表在构造阶段做 null 兜底，Service 层继续负责 trim、去重和语义冲突校验。</p>
     *
     * @param ruleCode        规则业务编码
     * @param normalizedName  归一化商品名称
     * @param category        商品品类
     * @param standardUnit    标准统计单位
     * @param unitFamily      单位族文本
     * @param priority        规则优先级
     * @param keywords        include 关键词列表
     * @param excludeKeywords exclude 关键词列表
     */
    public CreateNormalizationRuleCommand {
        keywords = keywords == null ? List.of() : keywords.stream().toList();
        excludeKeywords = excludeKeywords == null ? List.of() : excludeKeywords.stream().toList();
    }
}
