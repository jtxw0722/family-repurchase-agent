package com.jtxw.familyagent.interfaces.rest.request;

import com.jtxw.familyagent.application.command.NormalizationLibraryOperationCommand;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 23:10:00
 * @Description: 归一化规则库统一写操作 REST 请求参数，负责承载 /normalization-library 的 JSON 入参
 */
@Schema(description = "归一化规则库统一写操作请求")
public class NormalizationLibraryOperationRequest {
    /**
     * 操作动作，不能为空，支持 create_rule、update_rule、disable_rule、add_keyword、disable_keyword。
     */
    @Schema(description = "操作动作", example = "create_rule")
    private String action;
    /**
     * 规则业务编码，涉及规则维护时不能为空，建议只包含小写字母、数字和下划线。
     */
    @Schema(description = "规则业务编码", example = "body_wash")
    private String ruleCode;
    /**
     * 归一化商品名称，新增和更新规则时不能为空。
     */
    @Schema(description = "归一化商品名称", example = "沐浴露")
    private String normalizedName;
    /**
     * 商品品类，允许为空。
     */
    @Schema(description = "商品品类", example = "个护清洁")
    private String category;
    /**
     * 标准统计单位，新增和更新规则时不能为空，必须与 unitFamily 兼容。
     */
    @Schema(description = "标准统计单位", example = "L")
    private String standardUnit;
    /**
     * 单位族，新增和更新规则时不能为空，兼容小写入参。
     */
    @Schema(description = "单位族", example = "volume")
    private String unitFamily;
    /**
     * 规则或关键词优先级，允许为空；为空时应用层按 100 处理。
     */
    @Schema(description = "规则或关键词优先级", example = "80")
    private Integer priority;
    /**
     * 是否启用规则，仅 update_rule 使用，允许为空。
     */
    @Schema(description = "是否启用规则", example = "true")
    private Boolean enabled;
    /**
     * 单个关键词文本，add_keyword 和 disable_keyword 时不能为空。
     */
    @Schema(description = "单个关键词文本", example = "沐浴露")
    private String keyword;
    /**
     * 关键词类型，仅允许 include 或 exclude。
     */
    @Schema(description = "关键词类型", example = "include", allowableValues = {"include", "exclude"})
    private String matchType;
    /**
     * include 关键词列表，create_rule 时允许为空列表；update_rule 时 null 表示不修改，空数组表示清空。
     */
    @Schema(description = "include 关键词列表；update_rule 时 null 表示不修改，空数组表示清空")
    private List<String> keywords;
    /**
     * exclude 关键词列表，create_rule 时允许为空列表；update_rule 时 null 表示不修改，空数组表示清空。
     */
    @Schema(description = "exclude 关键词列表；update_rule 时 null 表示不修改，空数组表示清空")
    private List<String> excludeKeywords;
    /**
     * 历史记录回填批次筛选，apply_rule_to_records 时可选；为空时不按批次筛选。
     * batchId 和 owner 都为空时，按全家庭历史样本扫描。
     */
    @Schema(description = "历史记录回填批次筛选；为空时不按批次筛选，batchId 和 owner 都为空时扫描全家庭历史样本", example = "1")
    private Long batchId;
    /**
     * 历史记录回填归属人筛选，apply_rule_to_records 时可选；为空时不按归属人筛选。
     * batchId 和 owner 都为空时，按全家庭历史样本扫描。
     */
    @Schema(description = "历史记录回填归属人筛选；为空时不按归属人筛选，batchId 和 owner 都为空时扫描全家庭历史样本", example = "lj")
    private String owner;
    /**
     * 是否只处理 normalization_rule 为空或 legacy_fallback 的记录，apply_rule_to_records 默认 true。
     */
    @Schema(description = "是否只处理未命中明确规则的记录", example = "true")
    private Boolean onlyLegacyFallback;
    /**
     * 是否只处理当前 decision=exclude 的记录，apply_rule_to_records 默认 true。
     */
    @Schema(description = "是否只处理当前已排除记录", example = "true")
    private Boolean onlyExcluded;
    /**
     * 是否只预览不写库，apply_rule_to_records 默认 true；只有显式 false 才回填。
     */
    @Schema(description = "是否只预览不写库", example = "true")
    private Boolean dryRun;
    /**
     * 历史记录回填候选数量上限，默认 100，最大 500。
     */
    @Schema(description = "历史记录回填候选数量上限", example = "100")
    private Integer limit;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public void setNormalizedName(String normalizedName) {
        this.normalizedName = normalizedName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getStandardUnit() {
        return standardUnit;
    }

    public void setStandardUnit(String standardUnit) {
        this.standardUnit = standardUnit;
    }

    public String getUnitFamily() {
        return unitFamily;
    }

    public void setUnitFamily(String unitFamily) {
        this.unitFamily = unitFamily;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getMatchType() {
        return matchType;
    }

    public void setMatchType(String matchType) {
        this.matchType = matchType;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }

    public List<String> getExcludeKeywords() {
        return excludeKeywords;
    }

    public void setExcludeKeywords(List<String> excludeKeywords) {
        this.excludeKeywords = excludeKeywords;
    }

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

    public Boolean getOnlyLegacyFallback() {
        return onlyLegacyFallback;
    }

    public void setOnlyLegacyFallback(Boolean onlyLegacyFallback) {
        this.onlyLegacyFallback = onlyLegacyFallback;
    }

    public Boolean getOnlyExcluded() {
        return onlyExcluded;
    }

    public void setOnlyExcluded(Boolean onlyExcluded) {
        this.onlyExcluded = onlyExcluded;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(Boolean dryRun) {
        this.dryRun = dryRun;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    /**
     * 转换为应用层统一写操作命令。
     *
     * @return 归一化规则库统一写操作命令
     */
    public NormalizationLibraryOperationCommand toCommand() {
        return new NormalizationLibraryOperationCommand(action, ruleCode, normalizedName, category, standardUnit,
                unitFamily, priority, enabled, keyword, matchType, keywords, excludeKeywords,
                batchId, owner, onlyLegacyFallback, onlyExcluded, dryRun, limit);
    }
}
