package com.jtxw.familyagent.application;

/**
 * @Author: jtxw
 * @Date: 2026/06/10 11:34:21
 * @Description: 商品归一化建议调用观测指标，记录请求体积、耗时、HTTP 响应和解析状态。
 */
public record NormalizationAdviceObservation(
        /**
         * 本次系统 prompt 与用户 prompt 的字符数总和。
         */
        int promptChars,
        /**
         * HTTP 请求体 UTF-8 字节数。
         */
        int requestBytes,
        /**
         * 实际请求体 JSON，默认仅用于 debug dump，允许为空。
         */
        String requestBody,
        /**
         * 请求体构建耗时，单位毫秒。
         */
        long requestBuildElapsedMs,
        /**
         * LLM HTTP 调用耗时，单位毫秒。
         */
        long llmHttpElapsedMs,
        /**
         * 模型输出抽取耗时，单位毫秒。
         */
        long extractElapsedMs,
        /**
         * 结构化结果解析耗时，单位毫秒。
         */
        long parseElapsedMs,
        /**
         * 本批次总耗时，单位毫秒。
         */
        long totalElapsedMs,
        /**
         * LLM HTTP 响应状态码；未完成 HTTP 调用时为 0。
         */
        int httpStatus,
        /**
         * LLM HTTP 响应 Content-Type，允许为空字符串。
         */
        String contentType,
        /**
         * LLM 原始响应体 UTF-8 字节数。
         */
        int responseBytes,
        /**
         * 从响应中抽取出的模型正文字符数。
         */
        int extractedContentChars,
        /**
         * 成功解析出的建议条数。
         */
        int parsedItems,
        /**
         * 失败类型，成功时为空。
         */
        String errorType,
        /**
         * 已脱敏的失败信息，成功时为空。
         */
        String errorMessage,
        /**
         * LLM 原始响应体，仅用于 debug dump，允许为空。
         */
        String responseBody,
        /**
         * 从响应中抽取出的模型正文，仅用于 debug dump，允许为空。
         */
        String extractedContent
) {
    /**
     * 创建空观测指标，用于空请求列表场景。
     *
     * @return 所有计数和耗时为 0、正文为空的观测指标
     */
    public static NormalizationAdviceObservation empty() {
        return new NormalizationAdviceObservation(0, 0, null, 0L, 0L, 0L, 0L, 0L,
                0, "", 0, 0, 0, null, null, null, null);
    }
}
