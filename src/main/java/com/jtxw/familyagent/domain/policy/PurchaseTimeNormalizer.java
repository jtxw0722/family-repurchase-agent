package com.jtxw.familyagent.domain.policy;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * @Author: jtxw
 * @Date: 2026/06/05
 * @Description: 购买时间标准化组件，将不同输入格式统一为 yyyy-MM-dd HH:mm:ss。
 */
@Component
public class PurchaseTimeNormalizer {
    private static final DateTimeFormatter OUTPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter STRICT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter STRICT_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    /**
     * 标准化手动录入的购买日期；空值默认使用当前日期零点。
     *
     * @param purchaseDate 手动录入日期或日期时间
     * @return yyyy-MM-dd HH:mm:ss 格式的入库时间
     */
    public String normalizeManualPurchaseDate(String purchaseDate) {
        if (purchaseDate == null || purchaseDate.isBlank()) {
            return LocalDate.now().atStartOfDay().format(OUTPUT_FORMAT);
        }
        return normalizeRequired(purchaseDate, "purchaseDate");
    }

    /**
     * 标准化文件导入的订单时间；空值保持为空，避免改变导入器已有的缺失值语义。
     *
     * @param orderTime 文件中的订单时间
     * @return yyyy-MM-dd HH:mm:ss 格式的入库时间，或原始空值
     */
    public String normalizeImportedOrderTime(String orderTime) {
        if (orderTime == null || orderTime.isBlank()) {
            return orderTime;
        }
        return normalizeRequired(orderTime, "orderTime");
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = value.trim().replace('/', '-').replace('T', ' ');
        try {
            if (normalized.length() == 10) {
                return LocalDate.parse(normalized, STRICT_DATE_FORMAT).atStartOfDay().format(OUTPUT_FORMAT);
            }
            if (normalized.length() >= 19) {
                return LocalDateTime.parse(normalized.substring(0, 19), STRICT_DATE_TIME_FORMAT).format(OUTPUT_FORMAT);
            }
        } catch (DateTimeParseException e) {
            throw invalidTime(fieldName, e);
        }
        throw invalidTime(fieldName, null);
    }

    private IllegalArgumentException invalidTime(String fieldName, Exception cause) {
        String message = fieldName + " 格式错误，请使用 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss，例如 2026-06-04 00:00:00。";
        return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
    }
}
