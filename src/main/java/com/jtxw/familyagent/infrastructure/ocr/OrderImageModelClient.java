package com.jtxw.familyagent.infrastructure.ocr;

import java.nio.file.Path;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 09:21:54
 * @Description: 订单截图视觉模型客户端抽象，仅负责从已校验本地路径或内存图片提取 OCR 原始文本，不解析候选样本或写库
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

    /**
     * 调用视觉模型识别订单截图中的可见文字，支持已校验路径图片或接口 Base64 内存图片。
     *
     * @param imageInput 订单截图统一输入对象，不允许为空
     * @return 视觉模型提取的 OCR 原始文本和识别警告
     * @throws OrderImageModelException 配置无效、图片不合规、请求失败或响应不可解析时抛出
     */
    default OcrResult recognize(OrderImageInput imageInput) {
        if (imageInput == null) {
            throw new OrderImageModelException("订单截图输入不能为空");
        }
        if (OrderImageInputType.PATH.equals(imageInput.sourceType())) {
            return recognize(imageInput.path());
        }
        throw new OrderImageModelException("当前视觉模型客户端不支持内存图片输入");
    }
}
