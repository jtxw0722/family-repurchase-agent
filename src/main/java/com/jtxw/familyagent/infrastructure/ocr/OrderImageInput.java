package com.jtxw.familyagent.infrastructure.ocr;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 07:16:54
 * @Description: 订单截图识别输入对象，统一封装本地路径图片和接口 Base64 图片的安全摘要与二进制边界
 *
 * @param sourceType 图片来源类型，BASE64 表示接口内存图片，PATH 表示已校验服务器本地路径
 * @param displayName 图片安全展示名，仅用于摘要和响应，不允许包含完整本地路径或 Base64 内容
 * @param mimeType 图片 MIME 类型，BASE64 输入不允许为空，PATH 输入可由文件后缀推断
 * @param content 图片二进制内容，仅 BASE64 输入使用，构造和读取时均执行防御性复制
 * @param path 已完成安全校验的服务器本地图片路径，仅 PATH 输入使用
 */
public record OrderImageInput(
        OrderImageInputType sourceType,
        String displayName,
        String mimeType,
        byte[] content,
        Path path
) {
    /**
     * 统一执行图片字节防御性复制，避免识别链路外部修改内存图片内容。
     */
    public OrderImageInput {
        content = content == null ? null : Arrays.copyOf(content, content.length);
    }

    /**
     * 创建接口 Base64 图片输入对象。
     *
     * @param displayName 图片安全展示名，不允许包含 Base64 内容
     * @param mimeType 图片 MIME 类型，不允许为空
     * @param content 图片二进制内容，不允许为空
     * @return Base64 图片识别输入对象
     */
    public static OrderImageInput base64(String displayName, String mimeType, byte[] content) {
        return new OrderImageInput(OrderImageInputType.BASE64, displayName, mimeType, content, null);
    }

    /**
     * 创建已完成安全校验的本地路径图片输入对象。
     *
     * @param imagePath 已完成安全校验且真实存在的本地图片路径
     * @return 本地路径图片识别输入对象
     */
    public static OrderImageInput path(Path imagePath) {
        String displayName = imagePath == null || imagePath.getFileName() == null
                ? "local-image" : imagePath.getFileName().toString();
        return new OrderImageInput(OrderImageInputType.PATH, displayName, null, null, imagePath);
    }

    /**
     * 返回图片二进制内容副本，防止调用方直接修改 record 内部数组。
     *
     * @return 图片二进制内容副本；无内存图片时返回 null
     */
    @Override
    public byte[] content() {
        return content == null ? null : Arrays.copyOf(content, content.length);
    }

    /**
     * 返回不包含图片字节或 Base64 内容的安全摘要。
     *
     * @return 图片输入安全摘要
     */
    @Override
    public String toString() {
        int contentSize = content == null ? 0 : content.length;
        String pathName = path == null || path.getFileName() == null ? null : path.getFileName().toString();
        return "OrderImageInput[sourceType=" + sourceType + ", displayName=" + displayName
                + ", mimeType=" + mimeType + ", contentSize=" + contentSize + ", pathName=" + pathName + "]";
    }
}
