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
    /**
     * 日期文本长度：yyyy-MM-dd。
     */
    private static final int DATE_TEXT_LENGTH = 10;
    /**
     * 日期时间文本长度：yyyy-MM-dd HH:mm:ss。
     */
    private static final int DATE_TIME_TEXT_LENGTH = 19;
    /**
     * 统一输出到数据库和业务结果中的时间格式：yyyy-MM-dd HH:mm:ss。
     */
    private static final DateTimeFormatter OUTPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * 严格日期解析器，用于解析 yyyy-MM-dd，不允许非法日期自动纠正。
     */
    private static final DateTimeFormatter STRICT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    /**
     * 严格日期时间解析器，用于解析 yyyy-MM-dd HH:mm:ss，不允许非法日期时间自动纠正。
     */
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

    /**
     * 标准化必填时间字段。
     *
     * <p>会 trim 输入，并兼容将 / 替换为 -、将 T 替换为空格。
     * 长度为 yyyy-MM-dd 时按日期解析并补齐零点；
     * 长度大于等于 yyyy-MM-dd HH:mm:ss 时截取前 19 位并按日期时间解析。
     * 格式非法时抛出统一的 IllegalArgumentException。</p>
     *
     * @param value     原始时间文本
     * @param fieldName 字段名，用于错误提示
     * @return yyyy-MM-dd HH:mm:ss 格式的标准化时间
     * @throws IllegalArgumentException 时间格式非法
     */
    private String normalizeRequired(String value, String fieldName) {
        String normalized = value.trim().replace('/', '-').replace('T', ' ');
        try {
            if (normalized.length() == DATE_TEXT_LENGTH) {
                return LocalDate.parse(normalized, STRICT_DATE_FORMAT).atStartOfDay().format(OUTPUT_FORMAT);
            }
            if (normalized.length() >= DATE_TIME_TEXT_LENGTH) {
                return LocalDateTime.parse(normalized.substring(0, DATE_TIME_TEXT_LENGTH), STRICT_DATE_TIME_FORMAT).format(OUTPUT_FORMAT);
            }
        } catch (DateTimeParseException e) {
            throw invalidTime(fieldName, e);
        }
        throw invalidTime(fieldName, null);
    }

    /**
     * 创建统一的时间格式错误异常。
     *
     * <p>当 cause 为空时返回不带 cause 的 IllegalArgumentException；
     * 当 cause 不为空时保留原始解析异常作为 cause。</p>
     *
     * @param fieldName 字段名，用于错误提示
     * @param cause     原始解析异常，可为空
     * @return 时间格式非法异常
     */
    private IllegalArgumentException invalidTime(String fieldName, Exception cause) {
        String message = fieldName + " 格式错误，请使用 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss，例如 2026-06-04 00:00:00。";
        return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
    }
}
