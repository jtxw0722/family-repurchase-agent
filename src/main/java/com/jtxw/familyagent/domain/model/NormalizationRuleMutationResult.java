package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 21:48:04
 * @Description: 归一化规则库维护结果，承载规则和关键词写操作后的动作类型与提示信息
 *
 * @param ruleCode       规则业务编码，对应 normalization_rules.rule_code
 * @param normalizedName 归一化商品名称，关键词操作或禁用操作中允许为空
 * @param keyword        关键词文本，规则基础信息操作中允许为空
 * @param matchType      关键词类型，仅关键词操作中返回 include 或 exclude
 * @param action         本次维护动作编码
 * @param message        面向调用方的中文结果提示
 */
public record NormalizationRuleMutationResult(
        String ruleCode,
        String normalizedName,
        String keyword,
        String matchType,
        String action,
        String message
) {
    /**
     * 构造规则级维护结果。
     *
     * @param ruleCode       规则业务编码
     * @param normalizedName 归一化商品名称
     * @param action         动作编码
     * @param message        中文提示
     * @return 规则级维护结果
     */
    public static NormalizationRuleMutationResult rule(String ruleCode,
                                                       String normalizedName,
                                                       String action,
                                                       String message) {
        return new NormalizationRuleMutationResult(ruleCode, normalizedName, null, null, action, message);
    }

    /**
     * 构造关键词级维护结果。
     *
     * @param ruleCode  规则业务编码
     * @param keyword   关键词文本
     * @param matchType 关键词类型
     * @param action    动作编码
     * @param message   中文提示
     * @return 关键词级维护结果
     */
    public static NormalizationRuleMutationResult keyword(String ruleCode,
                                                          String keyword,
                                                          String matchType,
                                                          String action,
                                                          String message) {
        return new NormalizationRuleMutationResult(ruleCode, null, keyword, matchType, action, message);
    }
}
