package com.jtxw.familyagent.application.command;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 21:48:04
 * @Description: 禁用归一化规则关键词命令，通过 enabled=0 实现软删除语义
 *
 * @param ruleCode  规则业务编码，来自路径参数
 * @param keyword   关键词文本，不能为空
 * @param matchType 关键词类型，仅允许 include 或 exclude
 */
public record DisableNormalizationRuleKeywordCommand(
        String ruleCode,
        String keyword,
        String matchType
) {
}
