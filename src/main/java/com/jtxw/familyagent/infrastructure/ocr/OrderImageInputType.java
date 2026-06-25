package com.jtxw.familyagent.infrastructure.ocr;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 07:14:32
 * @Description: 订单截图输入来源类型，区分服务器本地路径和接口内存图片，供识别链路选择安全处理策略
 */
public enum OrderImageInputType {
    /**
     * 接口传入的 Base64 图片，识别链路应优先使用内存字节，只有本地 OCR 兜底时才允许写临时文件。
     */
    BASE64,
    /**
     * 服务器本地图片路径，识别链路沿用既有路径安全校验后的本地文件处理方式。
     */
    PATH
}
