package com.jtxw.familyagent.interfaces.rest.request;


import com.jtxw.familyagent.application.command.AnalyzeNormalizationCommand;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/08/14:41
 * @Description: 批次商品归一化分析请求参数，属于 interfaces.rest.request 层，对应 /import-batches/{batchId}/analyze-normalization 接口。
 */

@Schema(description = "批次商品归一化分析请求")
public class AnalyzeNormalizationRequest {
    /**
     * 最大分析候选数，默认 100。
     */
    @Schema(description = "最大分析候选数", example = "100")
    private int limit = 100;
    /**
     * 是否忽略同批次已有建议后重新分析。
     */
    @Schema(description = "是否强制重新分析", example = "false")
    private boolean forceReanalyze = false;
    /**
     * 包含关键词，命中商品名或 SKU 任一字段才进入候选；为空时不过滤。
     */
    @Schema(description = "包含关键词，命中商品名或 SKU 任一字段才进入候选")
    private List<String> includeKeywords = List.of();
    /**
     * 排除关键词，命中商品名或 SKU 任一字段时排除；为空时不过滤。
     */
    @Schema(description = "排除关键词，命中商品名或 SKU 任一字段时排除")
    private List<String> excludeKeywords = List.of();
    /**
     * 是否只重试已有 failed suggestion 对应的候选。
     */
    @Schema(description = "是否只重试已有 failed suggestion 对应的候选", example = "false")
    private boolean onlyFailed = false;

    public AnalyzeNormalizationRequest() {
    }

    public int limit() {
        return limit;
    }

    public boolean forceReanalyze() {
        return forceReanalyze;
    }

    public List<String> includeKeywords() {
        return includeKeywords;
    }

    public List<String> excludeKeywords() {
        return excludeKeywords;
    }

    public boolean onlyFailed() {
        return onlyFailed;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public boolean isForceReanalyze() {
        return forceReanalyze;
    }

    public void setForceReanalyze(boolean forceReanalyze) {
        this.forceReanalyze = forceReanalyze;
    }

    public List<String> getIncludeKeywords() {
        return includeKeywords;
    }

    public void setIncludeKeywords(List<String> includeKeywords) {
        this.includeKeywords = includeKeywords == null ? List.of() : includeKeywords;
    }

    public List<String> getExcludeKeywords() {
        return excludeKeywords;
    }

    public void setExcludeKeywords(List<String> excludeKeywords) {
        this.excludeKeywords = excludeKeywords == null ? List.of() : excludeKeywords;
    }

    public boolean isOnlyFailed() {
        return onlyFailed;
    }

    public void setOnlyFailed(boolean onlyFailed) {
        this.onlyFailed = onlyFailed;
    }

    /**
     * 将 REST 请求参数转换为应用层分析命令。
     *
     * <p>保留原有默认值：limit=100、forceReanalyze=false、
     * includeKeywords=List.of()、excludeKeywords=List.of()、onlyFailed=false。
     * setter 中对 null list 转 List.of() 的处理逻辑已通过构造器兜底。</p>
     *
     * @param batchId 导入批次 ID，由路径变量传入
     * @return 商品归一化分析命令
     */
    public AnalyzeNormalizationCommand toCommand(long batchId) {
        return new AnalyzeNormalizationCommand(batchId, limit, forceReanalyze,
                includeKeywords, excludeKeywords, onlyFailed);
    }
}
