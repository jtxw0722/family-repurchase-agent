package com.jtxw.familyagent.application.command;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 规则维护建议任务创建命令，承载候选筛选条件、候选上限和是否应用建议的控制参数
 *
 * @param batchId         可选导入批次 ID，按批次筛选候选购买记录
 * @param owner           可选订单归属人，按家庭成员筛选候选购买记录
 * @param fullScan        是否显式允许全量扫描，未传 batchId 和 owner 时必须为 true
 * @param candidateMode   候选模式，支持 legacy_fallback 和 all
 * @param limit           最大候选数量，默认 100，上限 500
 * @param apply           是否将通过校验的建议写入规则库
 * @param includeKeywords 可选包含关键词，命中商品名或 SKU 任一字段才进入候选
 * @param excludeKeywords 可选排除关键词，命中商品名或 SKU 任一字段时排除
 */
public record NormalizationRuleSuggestionCommand(Long batchId,
                                                 String owner,
                                                 boolean fullScan,
                                                 String candidateMode,
                                                 int limit,
                                                 boolean apply,
                                                 List<String> includeKeywords,
                                                 List<String> excludeKeywords) {
}
