package com.jtxw.familyagent.infrastructure.ocr;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/18 18:12:00
 * @Description: OCR 基础设施识别结果，封装原始文本、识别置信度和底层警告
 *
 * @param rawText OCR 识别的完整原始文本，允许为空字符串
 * @param confidence OCR 客户端置信度，取值范围为 0 到 1，允许为空
 * @param warnings OCR 客户端产生的识别警告，允许为空列表但不应为 null
 */
public record OcrResult(
        String rawText,
        Double confidence,
        List<String> warnings
) {
}
