package com.jtxw.familyagent.infrastructure.importer;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/16:27
 * @Description: CSV 订单导入器，负责读取本地订单文件并转换为原始消费记录。
 */
@Component
public class CsvPurchaseImporter {
    /**
     * 读取本地 CSV 订单文件并转换为原始订单记录。
     *
     * <p>该导入器只负责文件解析和字段读取，不做商品归一化、单位价格计算或数据库写入。</p>
     *
     * @param file 本地 CSV 文件路径
     * @return 原始订单记录列表
     */
    public List<RawPurchaseRecord> importFile(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            ImportSchema schema = detectSchema(parser.getHeaderMap());
            List<RawPurchaseRecord> records = new ArrayList<>();
            for (CSVRecord record : parser) {
                RawPurchaseRecord rawRecord = switch (schema) {
                    case STANDARD -> readStandardRecord(record);
                    case CHINESE_ORDER_EXPORT -> readChineseOrderExportRecord(record);
                };
                if (rawRecord != null) {
                    records.add(rawRecord);
                }
            }
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("导入 CSV 文件失败: " + file, e);
        }
    }

    private ImportSchema detectSchema(Map<String, Integer> headerMap) {
        if (hasHeaders(headerMap, "order_time", "product_name", "quantity", "total_amount")) {
            return ImportSchema.STANDARD;
        }
        if (hasHeaders(headerMap, "订单提交时间", "订单状态", "店铺名称", "商品名称", "商品链接", "型号款式", "商品数量", "实付金额")) {
            return ImportSchema.CHINESE_ORDER_EXPORT;
        }
        throw new IllegalArgumentException("不支持的 CSV 表头，请使用项目标准模板或已支持的中文订单导出模板。当前表头：" + headerMap.keySet());
    }

    private boolean hasHeaders(Map<String, Integer> headerMap, String... headers) {
        for (String header : headers) {
            if (!headerMap.containsKey(header)) {
                return false;
            }
        }
        return true;
    }

    private RawPurchaseRecord readStandardRecord(CSVRecord record) {
        return new RawPurchaseRecord(
                get(record, "order_time"),
                get(record, "platform"),
                get(record, "owner"),
                get(record, "product_name"),
                get(record, "sku"),
                get(record, "category"),
                get(record, "sub_category"),
                parseDouble(get(record, "quantity")),
                get(record, "unit"),
                parseDouble(get(record, "total_amount")),
                getOrDefault(record, "currency", "CNY")
        );
    }

    private RawPurchaseRecord readChineseOrderExportRecord(CSVRecord record) {
        // 交易关闭不代表实际消费，导入阶段直接跳过，避免进入价格统计和月度报告
        if (!"交易成功".equals(get(record, "订单状态"))) {
            return null;
        }
        return new RawPurchaseRecord(
                get(record, "订单提交时间"),
                inferPlatform(get(record, "商品链接")),
                "default",
                get(record, "商品名称"),
                get(record, "型号款式"),
                "",
                "",
                parseDouble(get(record, "商品数量")),
                "件",
                parseDouble(get(record, "实付金额")),
                "CNY"
        );
    }

    private String get(CSVRecord record, String name) {
        return record.isMapped(name) ? record.get(name) : "";
    }

    private String getOrDefault(CSVRecord record, String name, String defaultValue) {
        String value = get(record, name);
        return value == null || value.isBlank() ? defaultValue : value;
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
        String value = link == null ? "" : link.toLowerCase();
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
     * CSV 导入模板类型。
     */
    private enum ImportSchema {
        /**
         * 项目标准 CSV 模板，使用 order_time、product_name 等英文字段
         */
        STANDARD,
        /**
         * 中文电商订单导出模板，使用订单提交时间、商品名称、实付金额等中文字段
         */
        CHINESE_ORDER_EXPORT
    }
}
