package com.jtxw.familyagent.application.command;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 15:58:00
 * @Description: 商品归一化异步分析任务创建命令，用于承载 analyze-normalization 用例的输入参数。
 *
 * @param batchId         导入批次 ID，对应 raw_import_batches.id
 * @param limit           最大分析候选数，小于等于 0 时由仓储落库为默认值 100
 * @param forceReanalyze  是否强制重新分析已存在建议的商品
 * @param includeKeywords 包含关键词列表，命中商品名或 SKU 任一字段才进入候选；为空时不过滤
 * @param excludeKeywords 排除关键词列表，命中商品名或 SKU 任一字段时排除；为空时不过滤
 * @param onlyFailed      是否只重试已有 failed suggestion 对应的候选商品
 */
public record AnalyzeNormalizationCommand(
        long batchId,
        int limit,
        boolean forceReanalyze,
        List<String> includeKeywords,
        List<String> excludeKeywords,
        boolean onlyFailed
) {
    /**
     * 创建商品归一化分析命令。
     *
     * <p>构造时对 includeKeywords 和 excludeKeywords 做 null 兜底，
     * 确保后台线程使用时不会出现空指针异常。</p>
     *
     * @param batchId         导入批次 ID
     * @param limit           最大分析候选数
     * @param forceReanalyze  是否强制重新分析
     * @param includeKeywords 包含关键词列表，允许为 null
     * @param excludeKeywords 排除关键词列表，允许为 null
     * @param onlyFailed      是否只重试 failed suggestion
     */
    public AnalyzeNormalizationCommand {
        includeKeywords = includeKeywords == null ? List.of() : includeKeywords.stream().toList();
        excludeKeywords = excludeKeywords == null ? List.of() : excludeKeywords.stream().toList();
    }
}
