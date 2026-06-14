package com.jtxw.familyagent.application.command;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 21:48:04
 * @Description: 新增归一化规则关键词命令，用于给已有规则添加 include 或 exclude 关键词
 *
 * @param ruleCode  规则业务编码，来自路径参数
 * @param keyword   关键词文本，不能为空
 * @param matchType 关键词类型，仅允许 include 或 exclude
 * @param priority  关键词优先级，数值越大排序越靠前
 */
public record AddNormalizationRuleKeywordCommand(
        String ruleCode,
        String keyword,
        String matchType,
        int priority
) {
}
