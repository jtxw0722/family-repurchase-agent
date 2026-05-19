package com.jtxw.familyagent.infrastructure.importer;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @Author: jtxw
 * @Date: 2026/05/17/17:26
 * @Description: 订单导入字段映射器，统一 CSV 和 Excel 的表头识别、字段转换和 owner 解析。
 */
@Component
public class OrderImportMapper {
    /**
     * 根据表头识别导入模板。
     *
     * @param headers 文件表头集合
     * @return 导入模板类型
     */
    public ImportSchema detectSchema(Set<String> headers) {
        if (hasHeaders(headers, "order_time", "product_name", "quantity", "total_amount")) {
            return ImportSchema.STANDARD;
        }
        if (hasHeaders(headers, "订单提交时间", "订单状态", "店铺名称", "商品名称", "商品链接", "型号款式", "商品数量", "实付金额")) {
            return ImportSchema.CHINESE_ORDER_EXPORT;
        }
        throw new IllegalArgumentException("不支持的订单文件表头，请使用项目标准模板或已支持的中文订单导出模板。当前表头：" + headers);
    }

    /**
     * 将一行订单字段转换为原始订单记录。
     *
     * @param schema        导入模板类型
     * @param values        当前行字段值
     * @param file          来源文件路径
     * @param ownerOverride 导入时指定的订单归属人
     * @return 原始订单记录；返回 null 表示该行应跳过
     */
    public RawPurchaseRecord map(ImportSchema schema, Map<String, String> values, Path file, String ownerOverride) {
        return switch (schema) {
            case STANDARD -> readStandardRecord(values, file, ownerOverride);
            case CHINESE_ORDER_EXPORT -> readChineseOrderExportRecord(values, file, ownerOverride);
        };
    }

    private boolean hasHeaders(Set<String> headers, String... requiredHeaders) {
        for (String header : requiredHeaders) {
            if (!headers.contains(header)) {
                return false;
            }
        }
        return true;
    }

    private RawPurchaseRecord readStandardRecord(Map<String, String> values, Path file, String ownerOverride) {
        return new RawPurchaseRecord(
                get(values, "order_time"),
                get(values, "platform"),
                resolveOwner(get(values, "owner"), file, ownerOverride),
                get(values, "product_name"),
                get(values, "sku"),
                get(values, "category"),
                get(values, "sub_category"),
                parseDouble(get(values, "quantity")),
                get(values, "unit"),
                parseDouble(get(values, "total_amount")),
                getOrDefault(values, "currency", "CNY")
        );
    }

    private RawPurchaseRecord readChineseOrderExportRecord(Map<String, String> values, Path file, String ownerOverride) {
        // 交易关闭不代表实际消费，导入阶段直接跳过，避免进入价格统计和月度报告
        if (!"交易成功".equals(get(values, "订单状态"))) {
            return null;
        }
        return new RawPurchaseRecord(
                get(values, "订单提交时间"),
                inferPlatform(get(values, "商品链接")),
                resolveOwner("", file, ownerOverride),
                get(values, "商品名称"),
                get(values, "型号款式"),
                "",
                "",
                parseDouble(get(values, "商品数量")),
                "件",
                parseDouble(get(values, "实付金额")),
                "CNY"
        );
    }

    private String get(Map<String, String> values, String name) {
        return values.getOrDefault(name, "");
    }

    private String getOrDefault(Map<String, String> values, String name, String defaultValue) {
        String value = get(values, name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String resolveOwner(String recordOwner, Path file, String ownerOverride) {
        if (ownerOverride != null && !ownerOverride.isBlank()) {
            return normalizeOwner(ownerOverride);
        }
        if (recordOwner != null && !recordOwner.isBlank()) {
            return normalizeOwner(recordOwner);
        }
        String filenameOwner = extractOwnerFromFilename(file);
        if (filenameOwner != null && !filenameOwner.isBlank()) {
            return normalizeOwner(filenameOwner);
        }
        throw new IllegalArgumentException("无法确定订单归属 owner，请传入 owner 参数，或使用 文件名-owner.csv / 文件名-owner.xlsx 格式。");
    }

    private String normalizeOwner(String owner) {
        // owner 是后续统计和重复判断维度，统一大小写避免 jtxw 与 JTXW 被拆成两个人
        return owner.trim().toUpperCase(Locale.ROOT);
    }

    private String extractOwnerFromFilename(Path file) {
        String filename = file.getFileName().toString();
        int dotIndex = filename.lastIndexOf('.');
        String basename = dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
        int dashIndex = basename.lastIndexOf('-');
        if (dashIndex < 0 || dashIndex == basename.length() - 1) {
            return null;
        }
        return basename.substring(dashIndex + 1).trim();
    }

    private Double parseDouble(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim()
                .replace("￥", "")
                .replace("¥", "")
                .replace(",", "");
        return Double.parseDouble(normalized);
    }

    private String inferPlatform(String link) {
        String value = link == null ? "" : link.toLowerCase(Locale.ROOT);
        if (value.contains("taobao.com") || value.contains("tmall.com")) {
            return "taobao";
        }
        if (value.contains("jd.com")) {
            return "jd";
        }
        if (value.contains("yangkeduo.com") || value.contains("pinduoduo.com")) {
            return "pdd";
        }
        return "unknown";
    }

    /**
     * 订单导入模板类型。
     */
    public enum ImportSchema {
        /**
         * 项目标准模板，使用 order_time、product_name 等英文字段
         */
        STANDARD,
        /**
         * 中文电商订单导出模板，使用订单提交时间、商品名称、实付金额等中文字段
         */
        CHINESE_ORDER_EXPORT
    }
}
