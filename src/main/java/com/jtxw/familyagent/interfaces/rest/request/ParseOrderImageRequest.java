package com.jtxw.familyagent.interfaces.rest.request;

import com.jtxw.familyagent.application.command.ParseOrderImageCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 19:42:00
 * @Description: 订单截图候选样本解析 REST 请求，接收本地图片路径和可选补充字段
 */
@Schema(description = "订单截图候选样本解析请求")
public class ParseOrderImageRequest {
    /**
     * 本地图片路径，必填且必须位于配置的允许目录内。
     */
    @NotBlank
    @Schema(description = "允许目录内的本地订单图片路径", example = "data/inbox/screenshots/order-001.png",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String imagePath;
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
     * @param imagePath 本地订单图片路径，不允许为空
     */
    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
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
        return new ParseOrderImageCommand(imagePath, owner, platform, purchaseDate, parseMode, dryRun);
    }
}
