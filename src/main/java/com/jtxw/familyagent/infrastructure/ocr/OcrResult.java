package com.jtxw.familyagent.infrastructure.ocr;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/11/10 12:30:39
 * @Description: OCR 基础设施识别结果，封装原始文本、识别置信度和底层警告
 *
 * @param rawText OCR 识别的完整原始文本，允许为空字符串
 * @param confidence OCR 客户端置信度，取值范围为 0 到 1，允许为空
 * @param warnings OCR 客户端产生的识别警告，允许为空列表但不应为 null
 * @param recognitionSource 实际成功的识别来源，local 表示本地模型、llm 表示外部模型；历史调用未提供时为空
 */
public record OcrResult(
        String rawText,
        Double confidence,
        List<String> warnings,
        String recognitionSource
) {
    /** 实际由本地 OCR 成功识别的来源标识，不允许为空。 */
    public static final String SOURCE_LOCAL = "local";
    /** 实际由外部 LLM 成功识别的来源标识，不允许为空。 */
    public static final String SOURCE_LLM = "llm";

    /**
     * 兼容客户端尚未标记来源的识别结果，实际来源由识别编排服务统一补充。
     *
     * @param rawText 完整原文，允许为空文本
     * @param confidence 识别置信度，取值为 0 到 1，允许为空
     * @param warnings 识别警告，允许为空列表
     */
    public OcrResult(String rawText, Double confidence, List<String> warnings) {
        this(rawText, confidence, warnings, null);
    }

    /**
     * 标记实际成功执行的识别来源，不依赖警告文本推断，也不修改原识别结果。
     *
     * @param source 来源标识，由编排服务传入 local 或 llm
     * @return 保留原文、置信度和警告并带有指定来源的新结果
     */
    public OcrResult withRecognitionSource(String source) {
        return new OcrResult(rawText, confidence, warnings, source);
    }
}
