package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 06:31:45
 * @Description: 单条历史购买记录规则回填结果，描述匹配状态、回填前后字段和风险提示
 *
 * @param recordId    购买记录 ID，不允许为空
 * @param productName 原始商品名称，不允许为空
 * @param sku         商品 SKU，允许为空
 * @param status      单条处理状态，取值为 applicable、updated、review_required 或 skipped
 * @param before      回填前快照，不允许为空
 * @param after       回填后预期快照；跳过时允许为空
 * @param warnings    单条处理警告，允许为空列表
 */
public record NormalizationApplyRuleToRecordsItem(long recordId,
                                                  String productName,
                                                  String sku,
                                                  String status,
                                                  NormalizationApplyRuleRecordSnapshot before,
                                                  NormalizationApplyRuleRecordSnapshot after,
                                                  List<String> warnings) {
    public NormalizationApplyRuleToRecordsItem {
        warnings = warnings == null ? List.of() : warnings.stream().toList();
    }
}

