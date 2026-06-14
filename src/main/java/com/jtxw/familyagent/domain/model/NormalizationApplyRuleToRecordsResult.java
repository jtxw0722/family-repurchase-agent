package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 06:31:45
 * @Description: 归一化规则应用到历史购买记录的结果，汇总预览、实际更新数量和逐条处理明细
 *
 * @param action              操作动作，固定为 apply_rule_to_records
 * @param success             是否完成本次扫描处理
 * @param ruleCode            本次应用的规则编码，不允许为空
 * @param normalizedName      规则对应的归一化商品名称，不允许为空
 * @param dryRun              是否只预览不写库
 * @param candidateCount      SQL 范围筛选后的候选记录数量，单位为条
 * @param matchedCount        命中目标规则并进入处理明细的记录数量，单位为条
 * @param applicableCount     可自动回填记录数量，单位为条
 * @param reviewRequiredCount 需要人工复核记录数量，单位为条
 * @param updatedCount        实际更新记录数量，单位为条
 * @param skippedCount        跳过记录数量，单位为条
 * @param warnings            任务级警告，允许为空列表
 * @param items               逐条处理结果，最多为请求 limit 限制的候选数量
 */
public record NormalizationApplyRuleToRecordsResult(String action,
                                                    boolean success,
                                                    String ruleCode,
                                                    String normalizedName,
                                                    boolean dryRun,
                                                    int candidateCount,
                                                    int matchedCount,
                                                    int applicableCount,
                                                    int reviewRequiredCount,
                                                    int updatedCount,
                                                    int skippedCount,
                                                    List<String> warnings,
                                                    List<NormalizationApplyRuleToRecordsItem> items) {
    public NormalizationApplyRuleToRecordsResult {
        warnings = warnings == null ? List.of() : warnings.stream().toList();
        items = items == null ? List.of() : items.stream().toList();
    }
}
