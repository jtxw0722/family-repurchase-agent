package com.jtxw.familyagent.infrastructure.ocr;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 11:38:51
 * @Description: 订单截图识别编排服务，负责按配置执行视觉模型优先、本地 OCR 兜底并合并安全警告
 */
@Service
public class OrderImageRecognitionService {
    /**
     * 安全错误信息最大字符数，避免把底层异常中的大段响应带入接口。
     */
    private static final int MAX_SAFE_MESSAGE_LENGTH = 300;

    /**
     * 现有本地 OCR 客户端，可能是已启用实现或禁用实现。
     */
    private final OcrClient localOcrClient;
    /**
     * 订单截图视觉模型客户端。
     */
    private final OrderImageModelClient modelClient;
    /**
     * 视觉模型开关和兜底配置。
     */
    private final ParseOrderImageModelProperties modelProperties;

    /**
     * 创建订单截图识别编排服务，按配置决定是否优先调用视觉模型并在失败时回退本地 OCR。
     *
     * @param localOcrClient  现有本地 OCR 客户端
     * @param modelClient     视觉模型客户端
     * @param modelProperties 视觉模型开关和兜底配置
     */
    public OrderImageRecognitionService(OcrClient localOcrClient,
                                        OrderImageModelClient modelClient,
                                        ParseOrderImageModelProperties modelProperties) {
        this.localOcrClient = localOcrClient;
        this.modelClient = modelClient;
        this.modelProperties = modelProperties;
    }

    /**
     * 按“模型优先、本地 OCR 兜底”策略提取图片原始文字。
     *
     * @param imagePath 已完成路径安全校验的图片真实路径
     * @return OCR 原始文本及模型或本地 OCR 警告
     * @throws IllegalStateException 模型失败且禁用兜底，或本地 OCR 不可用时抛出
     */
    public OcrResult recognize(Path imagePath) {
        if (!modelProperties.isEnabled()) {
            return localOcrClient.recognize(imagePath);
        }
        try {
            OcrResult modelResult = modelClient.recognize(imagePath);
            if (modelResult == null || modelResult.rawText() == null || modelResult.rawText().isBlank()) {
                throw new OrderImageModelException("视觉模型返回空文本");
            }
            return modelResult;
        } catch (RuntimeException exception) {
            String safeMessage = safeMessage(exception);
            if (!modelProperties.isFallbackToLocalOcr()) {
                throw new OrderImageModelException(
                        "视觉模型识别失败，且未启用本地 OCR 兜底：" + safeMessage);
            }
            OcrResult fallbackResult = localOcrClient.recognize(imagePath);
            List<String> warnings = new ArrayList<>();
            if (fallbackResult != null && fallbackResult.warnings() != null) {
                warnings.addAll(fallbackResult.warnings());
            }
            warnings.add("视觉模型识别失败，已回退本地 OCR：" + safeMessage);
            String rawText = fallbackResult == null ? "" : fallbackResult.rawText();
            Double confidence = fallbackResult == null ? null : fallbackResult.confidence();
            return new OcrResult(rawText, confidence, List.copyOf(warnings));
        }
    }

    /**
     * 创建仅调用现有 OCR 的识别服务，供保持历史构造器兼容和单元测试使用。
     *
     * @param localOcrClient 现有本地 OCR 客户端
     * @return 关闭视觉模型的识别编排服务
     */
    public static OrderImageRecognitionService localOnly(OcrClient localOcrClient) {
        ParseOrderImageModelProperties properties = new ParseOrderImageModelProperties();
        properties.setEnabled(false);
        return new OrderImageRecognitionService(localOcrClient,
                imagePath -> {
                    throw new OrderImageModelException("视觉模型未启用");
                }, properties);
    }

    /**
     * 从模型异常生成可返回的短错误信息，并移除已配置 API Key 和图片 data URL。
     *
     * @param exception 视觉模型调用异常
     * @return 不超过固定长度且不含已知敏感配置的错误信息
     */
    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        String apiKey = modelProperties.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            message = message.replace(apiKey, "[REDACTED]");
        }
        String normalizedMessage = message.replaceAll("data:image/[^\\s]+", "[REDACTED_IMAGE]")
                .replaceAll("\\s+", " ").trim();
        return normalizedMessage.substring(0, Math.min(normalizedMessage.length(), MAX_SAFE_MESSAGE_LENGTH));
    }
}
