package com.jtxw.familyagent.domain.policy;

/**
 * @Author: jtxw
 * @Date: 2026/05/31/11:45
 * @Description: 商品名称归一化结果，携带规格解析所需的单位族和标准单位。
 */
public record ProductNormalizationResult(
        String normalizedName,
        UnitFamily preferredUnitFamily,
        String standardUnit,
        boolean reviewRequired
) {
}
