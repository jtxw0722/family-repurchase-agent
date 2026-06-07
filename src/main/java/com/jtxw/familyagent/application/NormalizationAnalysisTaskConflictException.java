package com.jtxw.familyagent.application;

/**
 * @Author: jtxw
 * @Date: 2026/06/07 15:10:28
 * @Description: 商品归一化分析任务并发冲突异常，用于阻止多个 pending/running 任务同时存在。
 */
public class NormalizationAnalysisTaskConflictException extends RuntimeException {
    /**
     * 创建归一化分析任务冲突异常。
     *
     * @param message 面向接口调用方的错误提示，不应包含敏感信息
     */
    public NormalizationAnalysisTaskConflictException(String message) {
        super(message);
    }
}
