package com.jtxw.familyagent.infrastructure.ocr;

import java.nio.file.Path;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 10:18:35
 * @Description: 订单截图视觉模型客户端抽象，仅负责从已校验图片提取 OCR 原始文本，不解析候选样本或写库
 */
public interface OrderImageModelClient {
    /**
     * 调用视觉模型识别订单截图中的可见文字。
     *
     * @param imagePath 已完成安全校验且真实存在的本地图片路径
     * @return 视觉模型提取的 OCR 原始文本和识别警告
     * @throws OrderImageModelException 配置无效、图片不合规、请求失败或响应不可解析时抛出
     */
    OcrResult recognize(Path imagePath);
}
