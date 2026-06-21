package com.jtxw.familyagent.infrastructure.llm;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 09:22:31
 * @Description: 通用大模型供应商调用异常，只允许携带不含密钥、请求体、图片和完整模型内容的安全信息
 */
public class LlmException extends IllegalStateException {
    /**
     * 构造不包含密钥、请求体、图片或完整模型内容的安全异常。
     *
     * @param message 已确认不包含密钥、请求体、图片或完整模型内容的安全错误信息
     */
    public LlmException(String message) {
        super(message);
    }
}
