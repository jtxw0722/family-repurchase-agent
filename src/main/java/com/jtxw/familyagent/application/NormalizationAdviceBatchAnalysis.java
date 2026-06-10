package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.NormalizationAdvisorResult;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/10 11:34:21
 * @Description: 商品归一化建议批次分析结果，承载建议列表和单次调用观测指标。
 *
 * @param results     与输入候选顺序一致的商品归一化建议列表，批次失败时包含 failed=true 的兜底结果
 * @param observation 本次商品归一化建议调用的观测指标，用于日志和 debug dump
 */
public record NormalizationAdviceBatchAnalysis(
        List<NormalizationAdvisorResult> results,
        NormalizationAdviceObservation observation
) {
}
