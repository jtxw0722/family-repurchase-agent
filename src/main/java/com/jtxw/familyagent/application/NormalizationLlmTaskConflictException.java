package com.jtxw.familyagent.application;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 05:37:36
 * @Description: 归一化 LLM 通用任务并发冲突异常，用于阻止多个后台规则维护任务同时运行
 */
public class NormalizationLlmTaskConflictException extends RuntimeException {
    /**
     * 创建归一化 LLM 通用任务并发冲突异常。
     *
     * @param message 面向调用方的冲突提示信息，不允许为空
     */
    public NormalizationLlmTaskConflictException(String message) {
        super(message);
    }
}
