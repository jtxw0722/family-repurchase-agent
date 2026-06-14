package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 23:10:00
 * @Description: 归一化规则库统一写操作响应结果，保证不同 action 返回同一结构
 *
 * @param action         本次操作动作，来自统一入口 action
 * @param success        操作是否成功，正常返回时固定为 true
 * @param message        面向调用方的中文结果提示
 * @param ruleCode       规则业务编码，对应 normalization_rules.rule_code
 * @param normalizedName 归一化商品名称，关键词操作中允许为空
 * @param affectedRows   受影响记录数量，幂等成功且未写库时为 0
 * @param warnings       非阻断告警列表，允许为空列表
 */
public record NormalizationLibraryOperationResult(
        String action,
        boolean success,
        String message,
        String ruleCode,
        String normalizedName,
        int affectedRows,
        List<String> warnings
) {
    /**
     * 创建统一成功响应。
     *
     * @param action         本次操作动作
     * @param message        中文提示
     * @param ruleCode       规则业务编码
     * @param normalizedName 归一化商品名称
     * @param affectedRows   受影响记录数量
     * @param warnings       非阻断告警列表
     */
    public NormalizationLibraryOperationResult {
        warnings = warnings == null ? List.of() : warnings.stream().toList();
    }

    /**
     * 构造无告警的成功响应。
     *
     * @param action         本次操作动作
     * @param message        中文提示
     * @param ruleCode       规则业务编码
     * @param normalizedName 归一化商品名称
     * @param affectedRows   受影响记录数量
     * @return 统一操作成功响应
     */
    public static NormalizationLibraryOperationResult success(String action,
                                                              String message,
                                                              String ruleCode,
                                                              String normalizedName,
                                                              int affectedRows) {
        return new NormalizationLibraryOperationResult(action, true, message, ruleCode,
                normalizedName, affectedRows, List.of());
    }
}
