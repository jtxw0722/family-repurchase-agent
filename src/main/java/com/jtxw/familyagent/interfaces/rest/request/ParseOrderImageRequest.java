package com.jtxw.familyagent.interfaces.rest.request;

import com.jtxw.familyagent.application.command.ParseOrderImageCommand;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 09:21:54
 * @Description: 订单截图候选样本解析 REST 请求，接收本地图片路径或前端 Base64 图片及可选补充字段
 */
@Schema(description = "订单截图候选样本解析请求")
public class ParseOrderImageRequest {
    /**
     * 本地图片路径，允许为空；当 imageBase64 为空时必须提供且必须位于配置的允许目录内。
     */
    @Schema(description = "允许目录内的本地订单图片路径", example = "data/inbox/screenshots/order-001.png",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String imagePath;
    /**
     * 前端传入的订单截图 Base64，允许为空；非空时优先于 imagePath，支持 data URL 或纯 Base64。
     */
    @Schema(description = "前端传入的订单截图 Base64，支持 data URL 或纯 Base64",
            example = "data:image/jpeg;base64,...")
    private String imageBase64;
    /**
     * 前端原始图片文件名，允许为空；仅用于 MIME 推断和响应摘要，不作为服务器保存文件名。
     */
    @Schema(description = "前端原始图片文件名，仅用于格式推断和响应摘要", example = "order-img.jpg")
    private String imageFileName;
    /**
     * 前端传入的图片 MIME 类型，允许为空；纯 Base64 且无法通过 data URL 判断类型时使用。
     */
    @Schema(description = "图片 MIME 类型，例如 image/jpeg、image/png 或 image/webp", example = "image/jpeg")
    private String imageMimeType;
    /**
     * 订单归属人，允许为空，仅透传到候选样本。
     */
    private String owner;
    /**
     * 购买平台，允许为空；为空时尝试从 OCR 文本识别。
     */
    private String platform;
    /**
     * 用户补充的购买日期，允许为空且优先于 OCR 结果。
     */
    private String purchaseDate;
    /**
     * 解析模式，允许为空，默认 order_screenshot。
     */
    private String parseMode;
    /**
     * 是否仅预览，允许为空且默认 true；false 也不会写库。
     */
    private Boolean dryRun;

    public ParseOrderImageRequest() {
    }

    /**
     * @return 本地订单图片路径
     */
    public String getImagePath() {
        return imagePath;
    }

    /**
     * @param imagePath 本地订单图片路径，允许为空
     */
    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    /**
     * @return 前端传入的订单截图 Base64，允许为空
     */
    public String getImageBase64() {
        return imageBase64;
    }

    /**
     * @param imageBase64 前端传入的订单截图 Base64，允许为空
     */
    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64;
    }

    /**
     * @return 前端原始图片文件名，允许为空
     */
    public String getImageFileName() {
        return imageFileName;
    }

    /**
     * @param imageFileName 前端原始图片文件名，允许为空且仅用于安全摘要
     */
    public void setImageFileName(String imageFileName) {
        this.imageFileName = imageFileName;
    }

    /**
     * @return 图片 MIME 类型，允许为空
     */
    public String getImageMimeType() {
        return imageMimeType;
    }

    /**
     * @param imageMimeType 图片 MIME 类型，允许为空
     */
    public void setImageMimeType(String imageMimeType) {
        this.imageMimeType = imageMimeType;
    }

    /**
     * @return 订单归属人，允许为空
     */
    public String getOwner() {
        return owner;
    }

    /**
     * @param owner 订单归属人，允许为空
     */
    public void setOwner(String owner) {
        this.owner = owner;
    }

    /**
     * @return 请求指定的购买平台，允许为空
     */
    public String getPlatform() {
        return platform;
    }

    /**
     * @param platform 请求指定的购买平台，允许为空
     */
    public void setPlatform(String platform) {
        this.platform = platform;
    }

    /**
     * @return 用户补充的购买日期，允许为空
     */
    public String getPurchaseDate() {
        return purchaseDate;
    }

    /**
     * @param purchaseDate 用户补充的购买日期，允许为空
     */
    public void setPurchaseDate(String purchaseDate) {
        this.purchaseDate = purchaseDate;
    }

    /**
     * @return 解析模式，允许为空
     */
    public String getParseMode() {
        return parseMode;
    }

    /**
     * @param parseMode 解析模式，允许为空
     */
    public void setParseMode(String parseMode) {
        this.parseMode = parseMode;
    }

    /**
     * @return 是否仅预览，允许为空且默认按 true 处理
     */
    public Boolean getDryRun() {
        return dryRun;
    }

    /**
     * @param dryRun 是否仅预览；false 也不会写入购买记录
     */
    public void setDryRun(Boolean dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * 将 REST 请求转换为应用层订单截图解析命令。
     *
     * @return 不包含数据库写入语义的截图解析命令
     */
    public ParseOrderImageCommand toCommand() {
        return new ParseOrderImageCommand(imagePath, imageBase64, imageFileName, imageMimeType,
                owner, platform, purchaseDate, parseMode, dryRun);
    }
}
