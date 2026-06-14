package com.jtxw.familyagent.application.command;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 21:48:04
 * @Description: 更新归一化规则基础信息命令，仅允许修改规则主表字段，不覆盖关键词
 *
 * @param ruleCode       规则业务编码，来自路径参数，不允许修改
 * @param normalizedName 归一化商品名称，要求唯一
 * @param category       商品品类，允许为空
 * @param standardUnit   标准统计单位
 * @param unitFamily     单位族文本，兼容小写入参
 * @param priority       规则优先级，数值越大优先级越高
 * @param enabled        是否启用规则
 */
public record UpdateNormalizationRuleCommand(
        String ruleCode,
        String normalizedName,
        String category,
        String standardUnit,
        String unitFamily,
        Integer priority,
        Boolean enabled
) {
}
