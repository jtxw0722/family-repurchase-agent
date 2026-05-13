package com.jtxw.familyagent.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/00:12
 * @Description: 本地时间工具，统一生成持久化使用的时间字符串。
 */
public final class ClockUtils {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private ClockUtils() {}

    public static String nowText() {
        return LocalDateTime.now().format(FORMATTER);
    }
}
