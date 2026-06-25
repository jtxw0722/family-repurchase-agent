package com.jtxw.familyagent.infrastructure.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.infrastructure.llm.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 11:15:28
 * @Description: 订单截图大模型场景适配器，负责图片边界校验、提示词装配及模型文本到 OCR 结果的转换
 */
@Component
public class LlmOrderImageModelClient implements OrderImageModelClient {
    /**
     * 通用 LLM 请求场景标识。
     */
    private static final String SCENE = "parse_order_image";
    /**
     * 订单截图 OCR 提示词版本。
     */
    private static final String PROMPT_VERSION = "model-ocr-v1";
    /**
     * 视觉 OCR 提示词资源路径。
     */
    private static final String PROMPT_RESOURCE = "prompts/parse-order-image/model-ocr-v1.md";
    /**
     * 用户消息中的固定任务说明。
     */
    private static final String USER_INSTRUCTION =
            "请提取图片中的所有可见文字，尽量保持阅读顺序和换行。只返回 JSON："
                    + "{\"rawText\":\"...\",\"warnings\":[]}";

    /**
     * 订单截图模型配置，保留现有外部配置契约。
     */
    private final ParseOrderImageModelProperties properties;
    /**
     * 订单场景使用的通用 LLM 客户端。
     */
    private final LlmClient llmClient;
    /**
     * 模型 content 业务 JSON 解析器。
     */
    private final ObjectMapper objectMapper;
    /**
     * 订单截图隐私脱敏器，负责在返回 OCR 结果前隐藏收货信息和编号。
     */
    private final OrderImagePrivacySanitizer privacySanitizer;

    /**
     * 创建订单截图大模型场景适配器，使用指定配置、通用 LLM 客户端和 JSON 解析器。
     *
     * @param properties   订单截图模型配置
     * @param llmClient    具名的订单截图通用 LLM 客户端
     * @param objectMapper 模型 content 业务 JSON 解析器
     */
    public LlmOrderImageModelClient(
            ParseOrderImageModelProperties properties,
            @Qualifier("orderImageLlmClient") LlmClient llmClient,
            ObjectMapper objectMapper,
            OrderImagePrivacySanitizer privacySanitizer) {
        this.properties = properties;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.privacySanitizer = privacySanitizer;
    }

    /**
     * 读取图片并通过通用 LLM 客户端提取订单截图原始文字。
     *
     * @param imagePath 已完成安全校验且真实存在的本地图片路径
     * @return 模型 content 中解析出的 OCR 原始文本和警告
     */
    @Override
    public OcrResult recognize(Path imagePath) {
        return recognize(OrderImageInput.path(imagePath));
    }

    /**
     * 通过通用 LLM 客户端提取订单截图原始文字，支持本地路径图片和 Base64 内存图片。
     *
     * @param imageInput 订单截图统一输入对象，不允许为空
     * @return 模型 content 中解析出的 OCR 原始文本和警告
     */
    @Override
    public OcrResult recognize(OrderImageInput imageInput) {
        validateConfiguration();
        LlmImageInput image = buildImageInput(imageInput);
        LlmRequest request = new LlmRequest(
                SCENE,
                PROMPT_VERSION,
                loadPrompt(),
                USER_INSTRUCTION,
                properties.getModelName().trim(),
                0D,
                null,
                List.of(image)
        );
        try {
            LlmResponse response = llmClient.chat(request);
            if (response == null || response.content() == null || response.content().isBlank()) {
                throw new OrderImageModelException("视觉模型响应 content 为空");
            }
            return parseModelContent(response.content());
        } catch (OrderImageModelException exception) {
            throw exception;
        } catch (LlmException exception) {
            throw new OrderImageModelException("视觉模型请求失败：" + exception.getMessage());
        }
    }

    /**
     * 根据统一图片输入对象构造通用 LLM 图片输入，禁止把 Base64 内容写入请求文本。
     *
     * @param imageInput 订单截图统一输入对象
     * @return 通用 LLM 图片输入
     */
    private LlmImageInput buildImageInput(OrderImageInput imageInput) {
        if (imageInput == null) {
            throw new OrderImageModelException("订单截图输入不能为空");
        }
        if (OrderImageInputType.BASE64.equals(imageInput.sourceType())) {
            byte[] imageBytes = imageInput.content();
            validateImageSize(imageBytes.length);
            return new LlmImageInput(imageInput.displayName(), imageInput.mimeType(), imageBytes);
        }
        byte[] imageBytes = readImage(imageInput.path());
        return new LlmImageInput(imageInput.displayName(), mimeType(imageInput.path()), imageBytes);
    }

    /**
     * 保持原有 provider 和连接配置校验语义。
     */
    private void validateConfiguration() {
        if (!ParseOrderImageModelProperties.DEFAULT_PROVIDER.equalsIgnoreCase(trim(properties.getProvider()))) {
            throw new OrderImageModelException("不支持的订单截图视觉模型 provider");
        }
        requireText(properties.getBaseUrl(), "视觉模型 base-url 未配置");
        requireText(properties.getApiKey(), "视觉模型 api-key 未配置");
        requireText(properties.getModelName(), "视觉模型 model-name 未配置");
    }

    /**
     * 在读取图片前校验大小，避免向通用 LLM 层传递超过场景限制的内容。
     *
     * @param imagePath 已校验的图片路径
     * @return 图片二进制内容
     */
    private byte[] readImage(Path imagePath) {
        try {
            long imageSize = Files.size(imagePath);
            validateImageSize(imageSize);
            return Files.readAllBytes(imagePath);
        } catch (OrderImageModelException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new OrderImageModelException("无法读取订单截图文件");
        }
    }

    /**
     * 校验图片大小不超过视觉模型配置限制。
     *
     * @param imageSize 图片字节数
     */
    private void validateImageSize(long imageSize) {
        long maxImageBytes = properties.getMaxImageBytes() > 0
                ? properties.getMaxImageBytes() : ParseOrderImageModelProperties.DEFAULT_MAX_IMAGE_BYTES;
        if (imageSize > maxImageBytes) {
            throw new OrderImageModelException(
                    "订单截图大小超过视觉模型限制：" + imageSize + " > " + maxImageBytes + " 字节");
        }
    }

    /**
     * 解析模型 content 中的 OCR JSON，允许 JSON 位于 Markdown code block 中。
     *
     * @param content 模型首个消息文本
     * @return OCR 原始文本和字符串警告
     */
    private OcrResult parseModelContent(String content) {
        try {
            JsonNode result = objectMapper.readTree(extractJson(content));
            JsonNode rawTextNode = result.path("rawText");
            if (!rawTextNode.isTextual() || rawTextNode.asText().isBlank()) {
                throw new OrderImageModelException("视觉模型返回空文本");
            }
            List<String> warnings = new ArrayList<>();
            JsonNode warningNodes = result.path("warnings");
            if (warningNodes.isArray()) {
                warningNodes.forEach(warning -> {
                    if (warning.isTextual() && !warning.asText().isBlank()) {
                        warnings.add(warning.asText());
                    }
                });
            }
            return new OcrResult(privacySanitizer.sanitizeRawText(rawTextNode.asText()).trim(),
                    null, List.copyOf(warnings));
        } catch (OrderImageModelException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new OrderImageModelException("视觉模型 content 不是有效 JSON");
        }
    }

    /**
     * 从纯 JSON 或 Markdown code block 中截取最外层 JSON 对象。
     *
     * @param content 模型消息文本
     * @return JSON 对象文本
     */
    private String extractJson(String content) {
        String normalizedContent = content.trim();
        int firstBraceIndex = normalizedContent.indexOf('{');
        int lastBraceIndex = normalizedContent.lastIndexOf('}');
        if (firstBraceIndex < 0 || lastBraceIndex < firstBraceIndex) {
            throw new OrderImageModelException("视觉模型 content 不是有效 JSON");
        }
        return normalizedContent.substring(firstBraceIndex, lastBraceIndex + 1);
    }

    /**
     * 从 classpath 资源加载订单截图 OCR 系统提示词，加载失败时抛出安全异常。
     *
     * @return UTF-8 编码的订单截图 OCR 系统提示词
     */
    private String loadPrompt() {
        try {
            return new ClassPathResource(PROMPT_RESOURCE).getContentAsString(StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            throw new OrderImageModelException("无法读取订单截图视觉模型提示词");
        }
    }

    /**
     * 根据场景已允许的图片后缀确定 MIME 类型。
     *
     * @param imagePath 图片路径
     * @return image/png、image/jpeg 或 image/webp
     */
    private String mimeType(Path imagePath) {
        String fileName = imagePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (fileName.endsWith(".webp")) {
            return "image/webp";
        }
        throw new OrderImageModelException("视觉模型不支持该图片格式");
    }

    /**
     * 校验配置值非空且非空白，不满足时抛出包含安全提示的模型异常。
     *
     * @param value   待校验的配置值
     * @param message 不满足时的错误信息
     */
    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new OrderImageModelException(message);
        }
    }

    /**
     * 安全 trim，null 统一返回空字符串。
     *
     * @param value 待处理文本，允许为空
     * @return trim 后的文本，null 时返回空字符串
     */
    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
