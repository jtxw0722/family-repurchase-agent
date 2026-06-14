package com.jtxw.familyagent.interfaces.rest.advice;

import com.jtxw.familyagent.application.NormalizationLlmTaskConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 16:50:00
 * @Description: REST Tool API 统一异常处理器，将业务异常转换为标准 HTTP 错误响应，避免工具调用方收到不明确的 500 错误。
 */
@RestControllerAdvice(basePackages = "com.jtxw.familyagent.interfaces.rest")
public class RestExceptionHandler {

    /**
     * 将业务参数错误或状态异常转换为 400 响应。
     *
     * <p>捕获 IllegalArgumentException 和 IllegalStateException，返回包含 error 字段的 JSON 对象。</p>
     *
     * @param exception 参数或状态异常
     * @return 包含 error 字段的错误信息
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public Map<String, String> handleBadRequest(RuntimeException exception) {
        return Map.of("error", exception.getMessage());
    }

    /**
     * 将归一化 LLM 通用任务并发冲突转换为 409 响应，明确提示调用方稍后重试。
     *
     * @param exception 归一化 LLM 通用任务冲突异常
     * @return 包含 error 字段的错误信息
     */
    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(NormalizationLlmTaskConflictException.class)
    public Map<String, String> handleConflict(RuntimeException exception) {
        return Map.of("error", exception.getMessage());
    }
}
