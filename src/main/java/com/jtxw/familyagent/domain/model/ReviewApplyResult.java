package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/05/17/10:24
 * @Description: 复核应用结果对象，返回复核项、订单记录和最终统计决策。
 */
public record ReviewApplyResult(
        Long reviewId,
        Long recordId,
        String action,
        String decision,
        String status,
        String message
) {}
