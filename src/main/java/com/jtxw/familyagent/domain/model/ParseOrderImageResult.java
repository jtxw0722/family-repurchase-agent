package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 19:08:00
 * @Description: 订单截图解析结果，返回 OCR 原文和待确认候选样本，不包含任何入库结果
 *
 * @param success 是否完成 OCR 和规则解析
 * @param imagePath 调用方提交的本地图片路径
 * @param parseMode 实际采用的解析模式，默认 order_screenshot
 * @param candidateCount 返回的候选样本数量
 * @param candidates 待用户确认的结构化购买候选样本
 * @param warnings 本次解析的全局警告，包含不写库边界说明
 * @param rawText OCR 客户端返回的完整原始文本
 */
public record ParseOrderImageResult(
        boolean success,
        String imagePath,
        String parseMode,
        int candidateCount,
        List<ParsedPurchaseCandidate> candidates,
        List<String> warnings,
        String rawText
) {
}
