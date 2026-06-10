package com.jtxw.familyagent.application;

/**
 * @Author: jtxw
 * @Date: 2026/06/10 17:11:02
 * @Description: LLM 原始 HTTP 调用异常，保留非 2xx 响应的状态码、响应类型和响应体信息。
 */
public class LlmClientException extends RuntimeException {
    /**
     * HTTP 响应状态码。
     */
    private final int httpStatus;
    /**
     * HTTP 响应 Content-Type，允许为空字符串。
     */
    private final String contentType;
    /**
     * 原始响应体 UTF-8 字节数。
     */
    private final int responseBytes;
    /**
     * 原始响应文本，已按 UTF-8 解码。
     */
    private final String responseBody;

    /**
     * 构造 LLM HTTP 调用异常。
     *
     * @param httpStatus   HTTP 响应状态码
     * @param contentType  HTTP 响应 Content-Type
     * @param responseBytes 原始响应体字节数
     * @param responseBody 原始响应文本
     * @param message      已脱敏的异常信息
     */
    public LlmClientException(int httpStatus,
                              String contentType,
                              int responseBytes,
                              String responseBody,
                              String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.contentType = contentType;
        this.responseBytes = responseBytes;
        this.responseBody = responseBody;
    }

    /**
     * 返回 HTTP 响应状态码。
     *
     * @return HTTP 响应状态码
     */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 返回 HTTP 响应 Content-Type。
     *
     * @return HTTP 响应 Content-Type，允许为空字符串
     */
    public String contentType() {
        return contentType;
    }

    /**
     * 返回原始响应体字节数。
     *
     * @return 原始响应体 UTF-8 字节数
     */
    public int responseBytes() {
        return responseBytes;
    }

    /**
     * 返回原始响应文本。
     *
     * @return 原始响应文本，已按 UTF-8 解码
     */
    public String responseBody() {
        return responseBody;
    }
}
