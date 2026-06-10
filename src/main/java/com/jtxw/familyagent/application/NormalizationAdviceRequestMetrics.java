package com.jtxw.familyagent.application;

/**
 * @Author: jtxw
 * @Date: 2026/06/10 11:34:21
 * @Description: 商品归一化建议请求体积指标，记录 prompt 字符数、请求字节数和请求体。
 *
 * @param promptChars 系统 prompt 与用户 prompt 的字符数总和
 * @param requestBytes HTTP 请求体 UTF-8 字节数
 * @param requestBody  实际请求体 JSON，用于 debug dump，允许为空
 */
public record NormalizationAdviceRequestMetrics(
        int promptChars,
        int requestBytes,
        String requestBody
) {
}
