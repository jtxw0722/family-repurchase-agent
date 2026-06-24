package com.jtxw.familyagent.infrastructure.ocr;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 08:34:00
 * @Description: 订单截图隐私脱敏器，负责对视觉 OCR 原文中的收货人、手机号、地址和单号做兜底隐藏
 */
@Component
public class OrderImagePrivacySanitizer {
    /**
     * 完整手机号模式，单位为 11 位中国大陆手机号。
     */
    private static final Pattern FULL_MOBILE_PATTERN = Pattern.compile("1[3-9]\\d{9}");
    /**
     * 已部分脱敏手机号模式，例如 130****0624。
     */
    private static final Pattern PARTIAL_MOBILE_PATTERN = Pattern.compile("1[3-9]\\d{1,2}\\*{3,4}\\d{4}");
    /**
     * 订单、交易、快递和支付流水等编号字段模式，只替换标签后的长编号。
     */
    private static final Pattern TRACKING_NUMBER_PATTERN = Pattern.compile(
            "(订单编号|订单号|交易编号|交易号|快递单号|物流单号|支付流水号|支付流水|单号)([:：\\s]*)([A-Za-z0-9-]{8,})");
    /**
     * 收货人字段模式，只在明确标签后隐藏姓名，避免误伤商品标题中的中文人名营销词。
     */
    private static final Pattern RECIPIENT_NAME_PATTERN = Pattern.compile("(收货人|收件人|联系人)([:：\\s]*)([^\\s，,;；]+)");
    /**
     * 地址字段模式，只在明确标签后隐藏地址正文。
     */
    private static final Pattern ADDRESS_LABEL_PATTERN = Pattern.compile("(收货地址|详细地址|地址)([:：\\s]*)(.+)");
    /**
     * 地址特征词模式，用于判断手机号同一行是否包含收货地址。
     */
    private static final Pattern ADDRESS_FEATURE_PATTERN = Pattern.compile("[路街号小区社区室栋幢村镇市区县省楼单元弄巷]");

    /**
     * 对订单截图 OCR 原文做兜底脱敏。
     *
     * @param rawText OCR 原文，允许为空
     * @return 脱敏后的 OCR 文本；输入为空时返回空字符串
     */
    public String sanitizeRawText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }
        String[] lines = normalizeReadableLineBreaks(rawText).split("\\R", -1);
        StringBuilder sanitizedText = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                sanitizedText.append('\n');
            }
            sanitizedText.append(sanitizeLine(lines[index]));
        }
        return sanitizedText.toString();
    }

    /**
     * 对单行 OCR 文本脱敏，优先整行隐藏收货信息，再处理单字段隐私。
     */
    private String sanitizeLine(String line) {
        if (line == null || line.isBlank()) {
            return line == null ? "" : line;
        }
        if (TRACKING_NUMBER_PATTERN.matcher(line).find()) {
            return TRACKING_NUMBER_PATTERN.matcher(line).replaceAll("$1$2[编号已隐藏]");
        }
        if (ADDRESS_LABEL_PATTERN.matcher(line).find()) {
            return ADDRESS_LABEL_PATTERN.matcher(line).replaceAll("$1$2[地址已隐藏]");
        }
        boolean hasMobile = FULL_MOBILE_PATTERN.matcher(line).find()
                || PARTIAL_MOBILE_PATTERN.matcher(line).find()
                || line.contains("[手机号已隐藏]");
        boolean hasAddressFeature = !line.contains("手机号")
                && ADDRESS_FEATURE_PATTERN.matcher(line).find()
                || line.contains("收货地址")
                || line.contains("地址");
        if (hasMobile && hasAddressFeature) {
            return "[收货信息已隐藏]";
        }
        String sanitizedLine = RECIPIENT_NAME_PATTERN.matcher(line)
                .replaceAll("$1$2[姓名已隐藏]");
        sanitizedLine = FULL_MOBILE_PATTERN.matcher(sanitizedLine)
                .replaceAll("[手机号已隐藏]");
        return PARTIAL_MOBILE_PATTERN.matcher(sanitizedLine)
                .replaceAll("[手机号已隐藏]");
    }

    /**
     * 将真实换行和字面量 \n 统一为可按行处理的换行符。
     */
    private String normalizeReadableLineBreaks(String value) {
        return value.replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n");
    }
}
