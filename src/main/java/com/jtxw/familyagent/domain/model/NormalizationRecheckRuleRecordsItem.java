package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/25 19:14:32
 * @Description: 单条历史样本重算结果，描述规则解绑、重新归一化、待复核状态和字段快照
 *
 * @param recordId              购买记录 ID，不允许为空
 * @param productName           原始商品名称，不允许为空
 * @param sku                   商品 SKU，允许为空
 * @param status                单条处理状态，取值为 would_reset、reset、would_normalize、normalized、review_required 或 skipped
 * @param matchedExcludeKeyword 命中的当前规则排除关键词，允许为空
 * @param before                重算前归一化统计字段快照，不允许为空
 * @param after                 重算后归一化统计字段快照；待复核或跳过时允许为空
 * @param warnings              单条处理警告，允许为空列表
 */
public record NormalizationRecheckRuleRecordsItem(Long recordId,
                                                  String productName,
                                                  String sku,
                                                  String status,
                                                  String matchedExcludeKeyword,
                                                  NormalizationApplyRuleRecordSnapshot before,
                                                  NormalizationApplyRuleRecordSnapshot after,
                                                  List<String> warnings) {
    public NormalizationRecheckRuleRecordsItem {
        warnings = warnings == null ? List.of() : warnings.stream().toList();
    }
}
