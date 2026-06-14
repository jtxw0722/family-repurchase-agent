package com.jtxw.familyagent.domain.model;

import java.math.BigDecimal;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 06:31:45
 * @Description: 规则回填购买记录快照，描述回填前后用于价格统计的归一化字段
 *
 * @param normalizedName    归一化商品名称，不允许为空
 * @param quantity          标准数量，单位由 unit 表示；无法可靠解析时允许为空
 * @param unit              标准单位，允许为空
 * @param unitPrice         标准单价，单位为元/标准单位；金额或数量异常时允许为空
 * @param decision          统计决策，通常为 include 或 exclude，不允许为空
 * @param normalizationRule 命中的归一化规则编码，允许为空
 */
public record NormalizationApplyRuleRecordSnapshot(String normalizedName,
                                                   BigDecimal quantity,
                                                   String unit,
                                                   BigDecimal unitPrice,
                                                   String decision,
                                                   String normalizationRule) {
}

