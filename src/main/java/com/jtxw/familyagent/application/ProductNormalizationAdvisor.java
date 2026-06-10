package com.jtxw.familyagent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorRequest;
import com.jtxw.familyagent.domain.model.NormalizationAdvisorResult;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/10 11:34:21
 * @Description: 商品归一化建议能力抽象，供应用编排层批量获取商品归一化建议和观测指标
 */
public interface ProductNormalizationAdvisor {

    /**
     * 批量分析候选商品并返回商品归一化建议。
     *
     * @param requests 待分析商品列表，只包含允许传给建议能力的隐私安全字段
     * @return 与输入顺序一致的结构化归一化建议列表；批次失败时返回 failed=true 的兜底建议
     */
    List<NormalizationAdvisorResult> analyzeBatch(List<NormalizationAdvisorRequest> requests);

    /**
     * 批量分析候选商品并返回建议结果与本次调用观测指标。
     *
     * @param requests 待分析商品列表，只包含允许传给建议能力的隐私安全字段
     * @return 商品归一化建议结果和观测指标，不暴露具体 LLM 实现类型
     */
    NormalizationAdviceBatchAnalysis analyzeBatchWithObservation(List<NormalizationAdvisorRequest> requests);

    /**
     * 构建商品归一化建议请求体并计算 prompt / request 体积指标。
     *
     * @param requests 待分析商品列表
     * @return 请求体字符串和体积指标，用于调用前日志与失败 debug dump
     * @throws JsonProcessingException 请求体序列化或扩展请求体配置解析失败时抛出
     */
    NormalizationAdviceRequestMetrics requestMetrics(List<NormalizationAdvisorRequest> requests)
            throws JsonProcessingException;
}
