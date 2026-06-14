package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: LLM 输出的归一化规则维护建议，描述新增规则或补充关键词的结构化操作
 *
 * @param operation      建议操作，当前只支持 create_rule 和 add_keyword
 * @param ruleCode       规则业务编码，create_rule 和 add_keyword 均需要
 * @param normalizedName 归一化商品名称，create_rule 需要
 * @param category       商品品类，create_rule 可选
 * @param standardUnit   标准统计单位，create_rule 需要
 * @param unitFamily     单位族，create_rule 需要
 * @param priority       规则优先级，create_rule 可选，默认 100
 * @param keywords       正向 include 关键词列表，create_rule 至少需要一个安全关键词
 * @param excludeKeywords 负向 exclude 关键词列表，create_rule 可为空
 * @param keyword        单个关键词，add_keyword 需要
 * @param matchType      关键词类型，add_keyword 只允许 include 或 exclude
 * @param confidence     LLM 置信度，create_rule 最低 0.8，add_keyword 最低 0.75
 * @param reason         建议原因，允许为空
 * @param evidence       证据商品标题列表，仅用于任务结果回显
 * @param applied        是否已成功写入规则库，apply=false 时固定为 false
 * @param skipped        是否被跳过，校验失败或写入失败时为 true
 * @param warnings       当前建议的警告列表，允许为空列表
 */
public record NormalizationRuleSuggestion(String operation,
                                          String ruleCode,
                                          String normalizedName,
                                          String category,
                                          String standardUnit,
                                          String unitFamily,
                                          Integer priority,
                                          List<String> keywords,
                                          List<String> excludeKeywords,
                                          String keyword,
                                          String matchType,
                                          Double confidence,
                                          String reason,
                                          List<String> evidence,
                                          boolean applied,
                                          boolean skipped,
                                          List<String> warnings) {

    /**
     * 返回带执行状态的建议副本。
     *
     * @param applied  是否已应用
     * @param skipped  是否已跳过
     * @param warnings 建议级警告
     * @return 新的建议对象，保留原始 LLM 字段并更新执行状态
     */
    public NormalizationRuleSuggestion withExecution(boolean applied, boolean skipped, List<String> warnings) {
        return new NormalizationRuleSuggestion(operation, ruleCode, normalizedName, category, standardUnit, unitFamily,
                priority, keywords, excludeKeywords, keyword, matchType, confidence, reason, evidence,
                applied, skipped, warnings == null ? List.of() : warnings);
    }
}
