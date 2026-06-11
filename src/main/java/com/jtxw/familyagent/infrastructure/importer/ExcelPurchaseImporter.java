package com.jtxw.familyagent.infrastructure.importer;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/05/17/17:28
 * @Description: Excel 订单导入器，负责读取本地 xlsx 文件并转换为原始订单记录。
 */
@Component
public class ExcelPurchaseImporter {

    /**
     * 年月日时分秒 时间格式
     */
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OrderImportMapper orderImportMapper;
    private final ChineseOrderExportRowNormalizer chineseOrderExportRowNormalizer;
    private final DataFormatter dataFormatter = new DataFormatter();

    public ExcelPurchaseImporter(OrderImportMapper orderImportMapper) {
        this(orderImportMapper, new ChineseOrderExportRowNormalizer());
    }

    @Autowired
    public ExcelPurchaseImporter(OrderImportMapper orderImportMapper,
                                 ChineseOrderExportRowNormalizer chineseOrderExportRowNormalizer) {
        this.orderImportMapper = orderImportMapper;
        this.chineseOrderExportRowNormalizer = chineseOrderExportRowNormalizer;
    }

    /**
     * 读取本地 Excel 订单文件并转换为原始订单记录。
     *
     * <p>默认读取第一个工作表，第一行作为表头，其余行作为订单数据。</p>
     *
     * @param file          本地 Excel 文件路径
     * @param ownerOverride 导入时指定的订单归属人，为空时优先使用 Excel 字段或文件名后缀
     * @return 原始订单记录列表
     */
    public List<RawPurchaseRecord> importFile(Path file, String ownerOverride) {
        try (InputStream inputStream = Files.newInputStream(file);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new IllegalArgumentException("Excel 文件缺少表头：" + file);
            }
            List<String> headers = readHeaders(headerRow);
            OrderImportMapper.ImportSchema schema = orderImportMapper.detectSchema(new java.util.LinkedHashSet<>(headers));
            List<Map<String, String>> valueRows = new ArrayList<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlankRow(row, headers.size())) {
                    continue;
                }
                valueRows.add(toValueMap(headers, row));
            }
            if (OrderImportMapper.ImportSchema.CHINESE_ORDER_EXPORT.equals(schema)) {
                valueRows = chineseOrderExportRowNormalizer.normalize(valueRows);
            }
            List<RawPurchaseRecord> records = new ArrayList<>();
            for (Map<String, String> valueRow : valueRows) {
                RawPurchaseRecord rawRecord = orderImportMapper.map(schema, valueRow, file, ownerOverride);
                if (rawRecord != null) {
                    records.add(rawRecord);
                }
            }
            return records;
        } catch (IOException e) {
            throw new IllegalStateException("导入 Excel 文件失败: " + file, e);
        }
    }

    /**
     * 从表头行读取非空表头名称。
     *
     * @param headerRow Excel 第一行表头
     * @return 表头名称列表，顺序与 Excel 列顺序一致
     */
    private List<String> readHeaders(Row headerRow) {
        List<String> headers = new ArrayList<>();
        for (int cellIndex = 0; cellIndex < headerRow.getLastCellNum(); cellIndex++) {
            String header = cellText(headerRow.getCell(cellIndex));
            if (!header.isBlank()) {
                headers.add(header);
            }
        }
        return headers;
    }

    /**
     * 将当前 Excel 行转换为字段名到字段值的映射。
     *
     * @param headers 表头名称列表
     * @param row     当前数据行
     * @return 当前行字段值映射
     */
    private Map<String, String> toValueMap(List<String> headers, Row row) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int cellIndex = 0; cellIndex < headers.size(); cellIndex++) {
            values.put(headers.get(cellIndex), cellText(row.getCell(cellIndex)));
        }
        return values;
    }

    /**
     * 判断 Excel 行是否为空行。
     *
     * @param row         当前数据行
     * @param columnCount 需要检查的列数
     * @return 是否为空行
     */
    private boolean isBlankRow(Row row, int columnCount) {
        for (int cellIndex = 0; cellIndex < columnCount; cellIndex++) {
            if (!cellText(row.getCell(cellIndex)).isBlank()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将 Excel 单元格转换为导入流程使用的文本值。
     *
     * <p>日期单元格统一输出为 yyyy-MM-dd HH:mm:ss，其余单元格交给 POI DataFormatter
     * 按显示值转换，避免数值、金额等字段在导入时丢失格式。</p>
     *
     * @param cell Excel 单元格
     * @return 单元格文本值
     */
    private String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue().toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                    .format(DATE_TIME_FORMATTER);
        }
        return dataFormatter.formatCellValue(cell).trim();
    }
}
