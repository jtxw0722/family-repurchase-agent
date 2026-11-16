package com.jtxw.familyagent.infrastructure.importer;

import org.apache.commons.csv.CSVFormat;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/15 12:51:46
 * @Description: 在线文件基础设施读取器，只在内存读取 CSV/XLSX，立即丢弃白名单以外的列，不创建临时文件
 */
@Component
public class OnlineOrderFileReader {
    /** 限制预览明细行数，防止一次构建过多预览对象。 */
    public static final int MAX_ROWS = 5000;
    /** 限制列数，避免异常宽表消耗不受控资源。 */
    private static final int MAX_COLUMNS = 100;
    /** XLSX 全部 ZIP 条目展开后的字节上限，32 MiB，避免压缩文件在工作簿加载前耗尽内存。 */
    private static final long MAX_EXPANDED_BYTES = 32L * 1024 * 1024;
    /** 已核对的中文订单模板白名单；订单号与链接仅用于内存分摊及平台识别，不返回或保存。 */
    private static final Set<String> HEADERS = Set.of("订单编号", "订单号", "主订单编号", "子订单编号",
            "订单提交时间", "订单状态", "店铺名称", "商品名称", "商品链接", "型号款式", "商品数量", "商品金额", "实付金额", "运费");

    /**
     * 从字节数组读取有效业务列，支持 UTF-8/BOM 与 GB18030 CSV；XLSX 使用内存工作簿。
     * @param bytes 上传内容，不得为空
     * @param format csv 或 xlsx，不接收或使用原文件名
     * @return 业务列映射，最大 5000 行；格式异常时抛出无原文的参数异常
     */
    public List<Map<String, String>> read(byte[] bytes, String format) {
        try {
            List<Map<String, String>> rows = new ArrayList<>();
            if ("csv".equals(format)) {
                String text;
                try {
                    text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString();
                } catch (CharacterCodingException exception) {
                    text = Charset.forName("GB18030").newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                }
                try (var parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setAllowMissingColumnNames(true).get()
                        .parse(new StringReader(text.replaceFirst("^\\uFEFF", "")))) {
                    validateHeaders(parser.getHeaderNames());
                    for (var record : parser) {
                        Map<String, String> row = new LinkedHashMap<>();
                        for (String header : parser.getHeaderNames()) {
                            if (HEADERS.contains(header)) row.put(header, record.isSet(header) ? record.get(header).trim() : "");
                        }
                        addRow(rows, row, (int) record.getRecordNumber() + 1);
                    }
                }
            } else if ("xlsx".equals(format)) {
                validateArchive(bytes);
                // 不修改 POI 的全局配置；若其他组件开启了临时文件模式则拒绝在线解析。
                if (org.apache.poi.openxml4j.util.ZipInputStreamZipEntrySource.getThresholdBytesForTempFiles() >= 0
                        || org.apache.poi.openxml4j.opc.ZipPackage.useTempFilePackageParts()) throw new IllegalArgumentException();
                // InputStream 模式由 POI 在内存中展开，不使用文件路径、临时目录或公式求值器。
                try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                    Sheet sheet = workbook.getSheetAt(0);
                    Row headerRow = sheet.getRow(sheet.getFirstRowNum());
                    if (headerRow == null || headerRow.getLastCellNum() > MAX_COLUMNS) throw new IllegalArgumentException();
                    DataFormatter formatter = new DataFormatter();
                    List<String> headers = new ArrayList<>();
                    for (int i = 0; i < headerRow.getLastCellNum(); i++) headers.add(formatter.formatCellValue(headerRow.getCell(i)).trim());
                    validateHeaders(headers);
                    if (sheet.getLastRowNum() - sheet.getFirstRowNum() > MAX_ROWS) throw new IllegalArgumentException();
                    for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                        Row source = sheet.getRow(i);
                        if (source == null) continue;
                        Map<String, String> row = new LinkedHashMap<>();
                        for (int column = 0; column < headers.size(); column++) {
                            if (HEADERS.contains(headers.get(column))) {
                                Cell cell = source.getCell(column);
                                String value = cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)
                                        ? cell.getLocalDateTimeCellValue().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                        : formatter.formatCellValue(cell).trim();
                                row.put(headers.get(column), value);
                            }
                        }
                        addRow(rows, row, i + 1);
                    }
                }
            } else throw new IllegalArgumentException();
            return rows;
        } catch (Exception exception) {
            // 解析器异常可能包含单元格内容，不传播 cause 或原始异常消息。
            throw new IllegalArgumentException("文件无法解析，请确认是支持的 CSV/XLSX 中文订单导出文件，且不超过 5000 行。");
        }
    }

    /** 校验必要列及重复列，不把未知列名写入错误消息。 */
    private void validateHeaders(List<String> headers) {
        List<String> namedHeaders = headers.stream().filter(value -> !value.isBlank()).toList();
        if (headers.size() > MAX_COLUMNS || new HashSet<>(namedHeaders).size() != namedHeaders.size()
                || !headers.containsAll(List.of("订单提交时间", "订单状态", "商品名称", "型号款式", "商品数量", "实付金额"))) {
            throw new IllegalArgumentException();
        }
    }

    /** 收集非空业务行，不保留只有地址、电话或物流信息的行。 */
    private void addRow(List<Map<String, String>> rows, Map<String, String> row, int rowNumber) {
        if (rows.size() >= MAX_ROWS) throw new IllegalArgumentException();
        if (row.values().stream().anyMatch(value -> !value.isBlank())) {
            for (String header : HEADERS) row.putIfAbsent(header, "");
            row.put("_row", Integer.toString(rowNumber));
            rows.add(row);
        }
    }

    /** 在内存检查 ZIP 实际展开字节数，不信任压缩条目声明的大小，不写任何解压文件。 */
    private void validateArchive(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw new IOException();
        long expanded = 0;
        byte[] buffer = new byte[8192];
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes))) {
            while (zip.getNextEntry() != null) {
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > MAX_EXPANDED_BYTES) throw new IOException();
                }
            }
        }
    }
}
