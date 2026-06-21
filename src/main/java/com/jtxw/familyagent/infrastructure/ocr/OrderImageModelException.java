package com.jtxw.familyagent.infrastructure.ocr;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 09:41:08
 * @Description: 订单截图视觉模型识别异常，提供不包含 API Key、图片内容和请求体的安全错误信息
 */
public class OrderImageModelException extends IllegalStateException {
    /**
     * 构造不包含 API Key、图片内容和请求体的安全模型识别异常。
     *
     * @param message 可安全返回的模型识别错误信息
     */
    public OrderImageModelException(String message) {
        super(message);
    }

    /**
     * 构造保留原始异常链的安全模型识别异常，不主动拼接敏感配置到错误信息中。
     *
     * @param message 可安全返回的模型识别错误信息
     * @param cause 原始异常，仅用于保留异常链，不应包含主动拼接的敏感配置
     */
    public OrderImageModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
