package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/11/10 12:30:39
 * @Description: 订单截图解析结果，返回 OCR 原文和待确认候选样本，不包含任何入库结果
 *
 * @param success 是否完成 OCR 和规则解析
 * @param imagePath 调用方提交的本地图片路径
 * @param parseMode 实际采用的解析模式，默认 order_screenshot
 * @param candidateCount 返回的候选样本数量
 * @param candidates 待用户确认的结构化购买候选样本
 * @param warnings 本次解析的全局警告，包含不写库边界说明
 * @param rawText OCR 客户端返回的完整原始文本
 * @param recognitionSource 实际成功的识别来源，local 为本地模型、llm 为外部模型；历史调用未提供时为空
 */
public record ParseOrderImageResult(
        boolean success,
        String imagePath,
        String parseMode,
        int candidateCount,
        List<ParsedPurchaseCandidate> candidates,
        List<String> warnings,
        String rawText,
        String recognitionSource
) {
    /**
     * 兼容未提供识别来源的既有调用方，未提供来源时不猜测使用了哪一种模型。
     *
     * @param success 是否完成识别及解析
     * @param imagePath 调用方图片路径或文件名
     * @param parseMode 解析模式
     * @param candidateCount 候选样本数量，非负整数
     * @param candidates 待确认候选列表
     * @param warnings 全局警告，允许为空列表
     * @param rawText 识别原文，允许为空文本
     */
    public ParseOrderImageResult(boolean success, String imagePath, String parseMode, int candidateCount,
                                 List<ParsedPurchaseCandidate> candidates, List<String> warnings, String rawText) {
        this(success, imagePath, parseMode, candidateCount, candidates, warnings, rawText, null);
    }
}
