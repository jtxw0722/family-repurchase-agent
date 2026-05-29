package com.jtxw.familyagent.mcp;

/**
 * @Author: jtxw
 * @Date: 2026/05/28/00:18
 * @Description: MCP 工具执行异常，用于转换为 isError=true 的工具结果
 */
public class ToolExecutionException extends RuntimeException {
    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
