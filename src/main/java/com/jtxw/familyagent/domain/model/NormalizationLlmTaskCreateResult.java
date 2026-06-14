package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 归一化 LLM 异步任务创建结果，供规则维护建议任务返回通用任务入口
 *
 * @param taskId   新建任务 ID，来自 normalization_llm_tasks.id，不允许为空
 * @param taskType 任务类型，当前取值为 rule_suggestion
 * @param status   初始任务状态，通常为 pending
 * @param message  创建结果提示文案
 */
public record NormalizationLlmTaskCreateResult(Long taskId,
                                               String taskType,
                                               String status,
                                               String message) {
}
