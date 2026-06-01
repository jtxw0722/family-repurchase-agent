package com.jtxw.familyagent.domain.model;


import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/31/20:11
 * @Description: 价格基准结果
 */

public record PriceBaselineResult(
        String productName,
        String normalizedName,
        PriceDecisionResult.Baseline baseline,
        PriceDecisionResult.Evidence evidence,
        List<String> warnings
) {
}