package com.jtxw.familyagent.infrastructure.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 11:38:51
 * @Description: 订单截图识别编排服务，负责按配置执行视觉模型优先、本地 OCR 兜底并合并安全警告
 */
@Service
public class OrderImageRecognitionService {
    /**
     * 安全日志记录器，禁止输出图片内容和 Base64。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderImageRecognitionService.class);
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
        return recognize(OrderImageInput.path(imagePath));
    }

    /**
     * 按“模型优先、本地 OCR 兜底”策略提取图片原始文字，支持本地路径和 Base64 内存图片。
     *
     * @param imageInput 订单截图统一输入对象，不允许为空
     * @return OCR 原始文本及模型或本地 OCR 警告
     * @throws IllegalStateException 模型失败且禁用兜底，或本地 OCR 不可用时抛出
     */
    public OcrResult recognize(OrderImageInput imageInput) {
        if (imageInput == null) {
            throw new IllegalArgumentException("订单截图输入不能为空");
        }
        if (!modelProperties.isEnabled()) {
            return recognizeByLocalOcr(imageInput);
        }
        try {
            OcrResult modelResult = modelClient.recognize(imageInput);
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
            OcrResult fallbackResult = recognizeByLocalOcr(imageInput);
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
     * 调用本地 OCR；当输入来自 Base64 时，仅创建临时文件并在识别结束后删除。
     *
     * @param imageInput 订单截图统一输入对象
     * @return 本地 OCR 识别结果
     */
    private OcrResult recognizeByLocalOcr(OrderImageInput imageInput) {
        if (OrderImageInputType.PATH.equals(imageInput.sourceType())) {
            return localOcrClient.recognize(imageInput.path());
        }
        Path tempPath = null;
        try {
            tempPath = createTemporaryImageFile(imageInput);
            return localOcrClient.recognize(tempPath);
        } finally {
            deleteTemporaryImageFile(tempPath);
        }
    }

    /**
     * 为本地 OCR 创建临时图片文件，文件名使用 UUID 且不使用用户原始文件名。
     *
     * @param imageInput Base64 图片输入对象
     * @return 已写入图片字节的临时文件路径
     */
    private Path createTemporaryImageFile(OrderImageInput imageInput) {
        try {
            String suffix = temporaryFileSuffix(imageInput.mimeType());
            Path tempPath = Files.createTempFile("family-order-image-" + UUID.randomUUID() + "-", suffix);
            Files.write(tempPath, imageInput.content());
            return tempPath;
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建订单截图临时文件");
        }
    }

    /**
     * 删除本地 OCR 使用的临时图片文件；删除失败只记录安全 warning，不影响主流程结果。
     *
     * @param tempPath 临时图片文件路径，允许为空
     */
    private void deleteTemporaryImageFile(Path tempPath) {
        if (tempPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempPath);
        } catch (IOException exception) {
            LOGGER.warn("订单截图临时文件删除失败，fileName={}，exceptionType={}",
                    tempPath.getFileName(), exception.getClass().getSimpleName());
        }
    }

    /**
     * 根据图片 MIME 类型生成本地 OCR 临时文件后缀。
     *
     * @param mimeType 图片 MIME 类型
     * @return 本地临时文件后缀
     */
    private String temporaryFileSuffix(String mimeType) {
        if ("image/png".equalsIgnoreCase(mimeType)) {
            return ".png";
        }
        if ("image/webp".equalsIgnoreCase(mimeType)) {
            return ".webp";
        }
        return ".jpg";
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
