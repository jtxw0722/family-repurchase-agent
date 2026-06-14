package com.jtxw.familyagent.domain.model;

import java.util.List;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 归一化 LLM 通用异步任务查询结果，承载规则维护建议任务的状态、统计与结果
 *
 * @param id                      任务 ID，来自 normalization_llm_tasks.id，不允许为空
 * @param taskType                任务类型，当前取值为 rule_suggestion
 * @param status                  任务状态，取值为 pending、running、completed、failed
 * @param batchId                 导入批次 ID，按批次筛选时不为空
 * @param owner                   订单归属人，按家庭成员筛选时不为空
 * @param fullScan                是否显式启用全量候选扫描，默认 false
 * @param apply                   是否将建议写入规则库，规则维护建议任务使用
 * @param candidateMode           候选筛选模式，取值为 legacy_fallback 或 all
 * @param limit                   最大候选数量，单位为条
 * @param candidateCount          候选商品数量，单位为条
 * @param analyzedCount           已分析商品数量，单位为条
 * @param suggestedOperationCount LLM 输出的建议操作数量，单位为条
 * @param appliedCount            已成功应用到规则库的建议数量，单位为条
 * @param skippedCount            被本地校验或写入异常跳过的建议数量，单位为条
 * @param warnings                任务级警告列表，允许为空列表
 * @param result                  任务结果 JSON 对象，未完成时允许为空
 * @param errorMessage            任务级失败错误信息，非 failed 状态允许为空
 * @param createdAt               任务创建时间，格式 yyyy-MM-dd HH:mm:ss
 * @param updatedAt               任务最近更新时间，格式 yyyy-MM-dd HH:mm:ss
 */
public record NormalizationLlmTask(Long id,
                                   String taskType,
                                   String status,
                                   Long batchId,
                                   String owner,
                                   boolean fullScan,
                                   boolean apply,
                                   String candidateMode,
                                   int limit,
                                   int candidateCount,
                                   int analyzedCount,
                                   int suggestedOperationCount,
                                   int appliedCount,
                                   int skippedCount,
                                   List<String> warnings,
                                   Map<String, Object> result,
                                   String errorMessage,
                                   String createdAt,
                                   String updatedAt) {
}
