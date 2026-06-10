package com.jtxw.familyagent.application;

/**
 * @Author: jtxw
 * @Date: 2026/06/10 16:37:08
 * @Description: LLM 原始 HTTP 响应，承载状态码、响应类型、响应体积和响应文本。
 *
 * @param httpStatus    HTTP 响应状态码
 * @param contentType   HTTP 响应 Content-Type，允许为空字符串
 * @param responseBytes 原始响应体 UTF-8 字节数
 * @param body          原始响应文本，已按 UTF-8 解码
 */
public record LlmClientResponse(
        int httpStatus,
        String contentType,
        int responseBytes,
        String body
) {
}
