package com.jtxw.familyagent.infrastructure.ocr;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * @Author: jtxw
 * @Date: 2026/06/18 20:06:00
 * @Description: 默认关闭的 OCR 客户端，阻止未配置时将本地订单图片发送到任何外部服务
 */
@Component
@ConditionalOnProperty(
        name = "family-agent.parse-order-image.local-ocr.enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class DisabledOcrClient implements OcrClient {
    /**
     * 拒绝执行 OCR，确保第一阶段不会误用外部识别服务。
     *
     * @param imagePath 已完成安全校验的本地图片路径
     * @return 不返回结果
     * @throws IllegalStateException 始终抛出，提示配置本地 OCR 后再调用
     */
    @Override
    public OcrResult recognize(Path imagePath) {
        throw new IllegalStateException("OCR 未启用，请配置本地 OCR 或视觉模型后再使用 parse_order_image。");
    }
}
