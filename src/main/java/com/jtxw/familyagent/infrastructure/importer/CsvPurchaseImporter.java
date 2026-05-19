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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/16:27
 * @Description: CSV 订单导入器，负责读取本地订单文件并转换为原始消费记录。
 */
@Component
public class CsvPurchaseImporter {
    private final OrderImportMapper orderImportMapper;

    public CsvPurchaseImporter(OrderImportMapper orderImportMapper) {
        this.orderImportMapper = orderImportMapper;
    }

    /**
     * 读取本地 CSV 订单文件并转换为原始订单记录。
     *
     * <p>该导入器只负责文件解析和字段读取，不做商品归一化、单位价格计算或数据库写入。</p>
     *
     * @param file 本地 CSV 文件路径
     * @return 原始订单记录列表
     */
    public List<RawPurchaseRecord> importFile(Path file) {
        return importFile(file, null);
    }

    /**
     * 读取本地 CSV 订单文件并转换为原始订单记录。
     *
     * @param file          本地 CSV 文件路径
     * @param ownerOverride 导入时指定的订单归属人，为空时优先使用 CSV 字段或文件名后缀
     * @return 原始订单记录列表
     */
    public List<RawPurchaseRecord> importFile(Path file, String ownerOverride) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            OrderImportMapper.ImportSchema schema = orderImportMapper.detectSchema(parser.getHeaderMap().keySet());
            List<RawPurchaseRecord> records = new ArrayList<>();
            for (CSVRecord record : parser) {
                RawPurchaseRecord rawRecord = orderImportMapper.map(schema, toValueMap(record, parser.getHeaderMap()), file, ownerOverride);
                if (rawRecord != null) {
                    records.add(rawRecord);
                }
            }
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("导入 CSV 文件失败: " + file, e);
        }
    }

    private Map<String, String> toValueMap(CSVRecord record, Map<String, Integer> headerMap) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String header : headerMap.keySet()) {
            values.put(header, record.isMapped(header) ? record.get(header) : "");
        }
        return values;
    }
}
