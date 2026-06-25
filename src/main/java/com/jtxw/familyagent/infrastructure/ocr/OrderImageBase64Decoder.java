package com.jtxw.familyagent.infrastructure.ocr;

import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 08:01:16
 * @Description: 订单截图 Base64 解析组件，负责解析 data URL 或纯 Base64 并完成 MIME 与大小安全校验
 */
@Component
public class OrderImageBase64Decoder {
    /**
     * 图片 data URL 结构匹配规则，只解析 MIME 类型和 Base64 载荷，不记录原始内容。
     */
    private static final Pattern DATA_URL_PATTERN =
            Pattern.compile("^data:([^;,]+);base64,(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /**
     * 接口允许的图片 MIME 类型集合。
     */
    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    /**
     * 未传安全文件名时使用的响应摘要。
     */
    private static final String DEFAULT_DISPLAY_NAME = "base64-image";

    /**
     * 订单截图模型配置，复用 max-image-bytes 作为接口图片大小限制。
     */
    private final ParseOrderImageModelProperties properties;

    /**
     * 创建订单截图 Base64 解析组件。
     *
     * @param properties 订单截图模型配置，用于读取最大图片字节数限制
     */
    public OrderImageBase64Decoder(ParseOrderImageModelProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析前端传入的 Base64 订单截图并返回统一图片输入对象。
     *
     * @param imageBase64 前端传入的 data URL 或纯 Base64，不允许为空
     * @param imageFileName 前端原始文件名，仅用于安全展示名和 MIME 后缀推断
     * @param imageMimeType 前端传入 MIME 类型，仅纯 Base64 且 data URL 不可用时使用
     * @return 可供识别链路使用的内存图片输入对象
     * @throws IllegalArgumentException Base64 为空、非法、MIME 不支持或图片超过大小限制时抛出
     */
    public OrderImageInput decode(String imageBase64, String imageFileName, String imageMimeType) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new IllegalArgumentException("imageBase64 不能为空");
        }
        String trimmedBase64 = imageBase64.trim();
        Matcher matcher = DATA_URL_PATTERN.matcher(trimmedBase64);
        String mimeType;
        String payload;
        if (matcher.matches()) {
            mimeType = normalizeMimeType(matcher.group(1));
            payload = matcher.group(2);
        } else {
            mimeType = resolvePlainBase64MimeType(imageMimeType, imageFileName);
            payload = trimmedBase64;
        }
        validateMimeType(mimeType);
        byte[] imageBytes = decodePayload(payload);
        validateImageSize(imageBytes.length);
        return OrderImageInput.base64(safeDisplayName(imageFileName), mimeType, imageBytes);
    }

    /**
     * 解析纯 Base64 图片的 MIME 类型，优先使用请求 MIME，其次使用文件名后缀。
     *
     * @param imageMimeType 前端传入 MIME 类型，允许为空
     * @param imageFileName 前端原始文件名，允许为空
     * @return 规范化后的 MIME 类型
     */
    private String resolvePlainBase64MimeType(String imageMimeType, String imageFileName) {
        if (hasText(imageMimeType)) {
            return normalizeMimeType(imageMimeType);
        }
        String displayName = safeDisplayName(imageFileName).toLowerCase(Locale.ROOT);
        if (displayName.endsWith(".png")) {
            return "image/png";
        }
        if (displayName.endsWith(".jpg") || displayName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (displayName.endsWith(".webp")) {
            return "image/webp";
        }
        throw new IllegalArgumentException("无法判断图片 MIME 类型，请提供 imageMimeType 或 imageFileName");
    }

    /**
     * 使用 MIME Base64 解码器解析图片载荷，允许前端或网络层插入换行。
     *
     * @param payload Base64 载荷，不允许为空
     * @return 解码后的图片字节
     */
    private byte[] decodePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("imageBase64 不能为空");
        }
        try {
            String normalizedPayload = payload.replaceAll("\\s+", "");
            return Base64.getDecoder().decode(normalizedPayload);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("imageBase64 不是合法的 Base64 图片内容");
        }
    }

    /**
     * 校验图片 MIME 类型是否属于接口允许集合。
     *
     * @param mimeType 待校验 MIME 类型
     */
    private void validateMimeType(String mimeType) {
        if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("不支持的图片 MIME 类型：" + mimeType);
        }
    }

    /**
     * 校验解码后图片大小是否超过场景配置限制。
     *
     * @param imageSize 解码后图片字节数
     */
    private void validateImageSize(int imageSize) {
        long maxImageBytes = properties.getMaxImageBytes() > 0
                ? properties.getMaxImageBytes() : ParseOrderImageModelProperties.DEFAULT_MAX_IMAGE_BYTES;
        if (imageSize > maxImageBytes) {
            throw new IllegalArgumentException("订单截图大小超过限制：" + imageSize + " > " + maxImageBytes + " 字节");
        }
    }

    /**
     * 规范化 MIME 类型，便于大小写不一致的浏览器输入统一判断。
     *
     * @param mimeType 原始 MIME 类型，允许为空
     * @return 小写去空白后的 MIME 类型
     */
    private String normalizeMimeType(String mimeType) {
        return mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 从前端文件名中提取安全展示名，避免保留用户本地完整路径。
     *
     * @param imageFileName 前端原始文件名，允许为空
     * @return 仅包含末级名称的安全展示名
     */
    private String safeDisplayName(String imageFileName) {
        if (!hasText(imageFileName)) {
            return DEFAULT_DISPLAY_NAME;
        }
        String normalizedName = imageFileName.trim().replace('\\', '/');
        int separatorIndex = normalizedName.lastIndexOf('/');
        String fileName = separatorIndex >= 0 ? normalizedName.substring(separatorIndex + 1) : normalizedName;
        return fileName.isBlank() ? DEFAULT_DISPLAY_NAME : fileName;
    }

    /**
     * 判断文本是否包含非空白字符。
     *
     * @param text 待判断文本，允许为空
     * @return 包含非空白字符时返回 true
     */
    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
