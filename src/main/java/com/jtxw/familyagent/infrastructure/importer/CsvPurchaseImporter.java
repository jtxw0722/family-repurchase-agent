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

/**
 * @Author: jtxw
 * @Date: 2026/05/11/16:27
 * @Description: CSV 订单导入器，负责读取示例订单文件并转换为原始消费记录。
 */
@Component
public class CsvPurchaseImporter {
    public List<RawPurchaseRecord> importFile(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            List<RawPurchaseRecord> records = new ArrayList<>();
            for (CSVRecord record : parser) {
                records.add(new RawPurchaseRecord(
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
                ));
            }
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("导入 CSV 文件失败: " + file, e);
        }
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
        return Double.parseDouble(text);
    }
}
