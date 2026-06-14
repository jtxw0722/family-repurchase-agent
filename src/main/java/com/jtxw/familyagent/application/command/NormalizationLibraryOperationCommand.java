package com.jtxw.familyagent.application.command;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 23:10:00
 * @Description: 归一化规则库统一写操作命令，承载 REST 统一入口传入的 action 和规则维护字段
 *
 * @param action          操作动作，不能为空，支持 create_rule / update_rule / disable_rule / add_keyword / disable_keyword
 * @param ruleCode        规则业务编码，涉及规则维护时不能为空
 * @param normalizedName  归一化商品名称，新增和更新规则时不能为空
 * @param category        商品品类，允许为空
 * @param standardUnit    标准统计单位，新增和更新规则时不能为空
 * @param unitFamily      单位族文本，新增和更新规则时不能为空
 * @param priority        规则或关键词优先级，允许为空，写操作默认按 100 处理
 * @param enabled         是否启用规则，仅 update_rule 使用，允许为空
 * @param keyword         单个关键词文本，add_keyword 和 disable_keyword 时不能为空
 * @param matchType       关键词类型，仅允许 include 或 exclude
 * @param keywords        初始 include 关键词列表，create_rule 时允许为空
 * @param excludeKeywords 初始 exclude 关键词列表，create_rule 时允许为空
 */
public record NormalizationLibraryOperationCommand(
        String action,
        String ruleCode,
        String normalizedName,
        String category,
        String standardUnit,
        String unitFamily,
        Integer priority,
        Boolean enabled,
        String keyword,
        String matchType,
        List<String> keywords,
        List<String> excludeKeywords
) {
    /**
     * 创建统一操作命令。
     *
     * <p>列表字段在构造阶段做 null 兜底，Service 层继续负责 trim、去重和业务语义校验。</p>
     *
     * @param action          操作动作
     * @param ruleCode        规则业务编码
     * @param normalizedName  归一化商品名称
     * @param category        商品品类
     * @param standardUnit    标准统计单位
     * @param unitFamily      单位族文本
     * @param priority        优先级
     * @param enabled         是否启用规则
     * @param keyword         单个关键词
     * @param matchType       关键词类型
     * @param keywords        include 关键词列表
     * @param excludeKeywords exclude 关键词列表
     */
    public NormalizationLibraryOperationCommand {
        keywords = keywords == null ? List.of() : keywords.stream().toList();
        excludeKeywords = excludeKeywords == null ? List.of() : excludeKeywords.stream().toList();
    }
}
