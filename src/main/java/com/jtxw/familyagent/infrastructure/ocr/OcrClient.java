package com.jtxw.familyagent.infrastructure.ocr;

import java.nio.file.Path;

/**
 * @Author: jtxw
 * @Date: 2026/06/18 18:45:00
 * @Description: OCR 基础设施抽象，负责读取本地图片并返回文字识别结果
 */
public interface OcrClient {
    /**
     * 识别指定本地图片中的文字。
     *
     * @param imagePath 已完成安全校验且真实存在的本地图片路径
     * @return OCR 原始文本、置信度和识别警告
     * @throws IllegalStateException OCR 未启用或识别服务不可用时抛出
     */
    OcrResult recognize(Path imagePath);
}
