package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/25 19:15:07
 * @Description: 归一化规则历史样本重算结果，汇总解绑、重新归一化、待复核数量和逐条预览明细
 *
 * @param action         操作动作，固定为 recheck_rule_records
 * @param success        是否完成本次重算扫描
 * @param ruleCode       本次重算的规则编码，不允许为空
 * @param normalizedName 规则对应的归一化商品名称，不允许为空
 * @param dryRun         是否只预览不写库，默认 true
 * @param candidateCount 两个阶段 SQL 范围筛选后的 include 候选记录总数，单位为条
 * @param matchedCount   实际需要处理的记录数量，包含解绑、重新归一化和待复核记录，单位为条
 * @param excludedCount  兼容字段，当前语义等同 resetCount，不表示 decision=exclude
 * @param resetCount     从当前规则解绑的记录数量，单位为条
 * @param normalizedCount 重新归一化到当前规则的记录数量，单位为条
 * @param reviewRequiredCount 命中当前规则但因单位不一致等原因需要人工复核的记录数量，单位为条
 * @param updatedCount   实际更新 purchase_records 的记录数量，dryRun=true 时为 0
 * @param skippedCount   因重复、非 unique、并发更新失败等安全边界跳过的记录数量，单位为条
 * @param warnings       任务级警告，允许为空列表
 * @param items          逐条处理或跳过结果，不包含未产生处理结果的 unchanged 记录
 */
public record NormalizationRecheckRuleRecordsResult(String action,
                                                    boolean success,
                                                    String ruleCode,
                                                    String normalizedName,
                                                    boolean dryRun,
                                                    int candidateCount,
                                                    int matchedCount,
                                                    int excludedCount,
                                                    int resetCount,
                                                    int normalizedCount,
                                                    int reviewRequiredCount,
                                                    int updatedCount,
                                                    int skippedCount,
                                                    List<String> warnings,
                                                    List<NormalizationRecheckRuleRecordsItem> items) {
    public NormalizationRecheckRuleRecordsResult {
        warnings = warnings == null ? List.of() : warnings.stream().toList();
        items = items == null ? List.of() : items.stream().toList();
    }
}
