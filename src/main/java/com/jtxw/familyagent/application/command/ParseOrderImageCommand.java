package com.jtxw.familyagent.application.command;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 18:52:00
 * @Description: 订单截图解析命令，承载本地图片路径和候选样本补充信息，不触发购买记录写入
 *
 * @param imagePath    本地图片路径，允许为空；当 imageBase64 为空时必须位于配置的允许目录内
 * @param imageBase64  前端传入订单截图 Base64，允许为空；非空时优先于 imagePath
 * @param imageFileName 前端原始图片文件名，允许为空，仅用于 MIME 推断和响应摘要
 * @param imageMimeType 前端传入图片 MIME 类型，允许为空，仅纯 Base64 输入需要兜底判断时使用
 * @param owner        订单归属人，允许为空，仅透传到候选样本
 * @param platform     购买平台，允许为空；为空时尝试从 OCR 文本识别
 * @param purchaseDate 用户补充的购买日期，允许为空；非空时优先于 OCR 识别结果
 * @param parseMode    解析模式，允许为空，默认值为 order_screenshot
 * @param dryRun       是否仅预览，允许为空且默认 true；第一阶段传 false 也不会写库
 */
public record ParseOrderImageCommand(
        String imagePath,
        String imageBase64,
        String imageFileName,
        String imageMimeType,
        String owner,
        String platform,
        String purchaseDate,
        String parseMode,
        Boolean dryRun
) {
}
