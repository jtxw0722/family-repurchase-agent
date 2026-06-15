package com.jtxw.familyagent.interfaces.rest.request;

import com.jtxw.familyagent.application.command.NormalizationRuleSuggestionCommand;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 规则维护建议请求 DTO，属于 REST 接口层，负责接收 LLM 规则建议任务的筛选条件和应用开关
 */
@Schema(description = "归一化规则维护建议任务创建请求")
public class NormalizationRuleSuggestionRequest {
    /**
     * 导入批次 ID，允许为空；为空时不按批次筛选候选样本。
     */
    @Schema(description = "导入批次 ID", example = "17")
    private Long batchId;
    /**
     * 订单归属人，允许为空；仅用于缩小候选来源，空值表示分析全家庭候选样本。
     */
    @Schema(description = "订单归属人过滤条件；为空时分析全家庭候选样本", example = "jtxw")
    private String owner;
    /**
     * 是否显式标记全家庭扫描，默认 false；未传 batchId 和 owner 时按全家庭候选样本分析。
     */
    @Schema(description = "是否显式标记全家庭扫描；默认不传 owner 时仍分析全家庭候选样本", example = "false")
    private boolean fullScan = false;
    /**
     * 候选模式，默认 legacy_fallback；all 只在用户显式传入时启用。
     */
    @Schema(description = "候选模式，支持 legacy_fallback / all", example = "legacy_fallback")
    private String candidateMode = "legacy_fallback";
    /**
     * 最大候选数量，默认 100；应用层会限制最大值 500。
     */
    @Schema(description = "最大候选数量", example = "100")
    private int limit = 100;
    /**
     * 是否将通过本地校验的 LLM 建议写入规则库，默认 false。
     */
    @Schema(description = "是否应用建议", example = "false")
    private boolean apply = false;
    /**
     * 包含关键词列表，商品名或 SKU 命中任一关键词才进入候选，允许为空列表。
     */
    @Schema(description = "包含关键词列表")
    private List<String> includeKeywords = List.of();
    /**
     * 排除关键词列表，商品名或 SKU 命中任一关键词时排除，允许为空列表。
     */
    @Schema(description = "排除关键词列表")
    private List<String> excludeKeywords = List.of();

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public boolean isFullScan() {
        return fullScan;
    }

    public void setFullScan(boolean fullScan) {
        this.fullScan = fullScan;
    }

    public String getCandidateMode() {
        return candidateMode;
    }

    public void setCandidateMode(String candidateMode) {
        this.candidateMode = candidateMode;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public boolean isApply() {
        return apply;
    }

    public void setApply(boolean apply) {
        this.apply = apply;
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

    /**
     * 将 REST 请求转换为应用层规则维护建议命令。
     *
     * @return 规则维护建议命令，保留接口默认值和可空筛选条件
     */
    public NormalizationRuleSuggestionCommand toCommand() {
        return new NormalizationRuleSuggestionCommand(batchId, owner, fullScan, candidateMode, limit, apply,
                includeKeywords, excludeKeywords);
    }
}
