package com.jtxw.familyagent.infrastructure.llm;

/**
 * @param fileName 图片文件名，仅供调用方排查，不应写入日志
 * @param mimeType 图片 MIME 类型，例如 image/png、image/jpeg 或 image/webp
 * @param content  图片二进制内容，构造和读取时均进行防御性复制
 * @Author: jtxw
 * @Date: 2026/06/20 09:08:17
 * @Description: 通用大模型图片输入，通过防御性复制保护调用方与客户端之间的二进制内容边界
 */
public record LlmImageInput(String fileName, String mimeType, byte[] content) {
    /**
     * 对图片字节执行防御性复制，null 统一为空数组。
     */
    public LlmImageInput {
        content = content == null ? new byte[0] : content.clone();
    }

    /**
     * 返回图片二进制内容的防御性副本，避免调用方直接修改内部状态。
     *
     * @return 图片二进制内容的防御性副本
     */
    @Override
    public byte[] content() {
        return content.clone();
    }

    /**
     * 返回不包含图片字节内容的安全摘要，仅包含文件名、MIME 类型和字节长度。
     *
     * @return 不包含图片字节内容的安全摘要
     */
    @Override
    public String toString() {
        return "LlmImageInput[fileName=" + fileName + ", mimeType=" + mimeType
                + ", contentSize=" + content.length + "]";
    }
}
