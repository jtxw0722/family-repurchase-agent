package com.jtxw.familyagent.infrastructure.importer;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import com.jtxw.familyagent.domain.policy.ProductSpecParser;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/05/17/17:29
 * @Description: Excel 订单导入器测试，覆盖 xlsx 文件解析和字段映射。
 */
class ExcelPurchaseImporterTest {
    @Test
    void shouldImportChineseOrderExportWorkbook() throws Exception {
        Path file = testFile("orders-jtxw.xlsx");
        writeChineseOrderWorkbook(file);

        List<RawPurchaseRecord> records = importer().importFile(file, null);

        assertThat(records).hasSize(1);
        RawPurchaseRecord record = records.get(0);
        assertThat(record.orderTime()).isEqualTo("2026-01-23 02:32:45");
        assertThat(record.platform()).isEqualTo("taobao");
        assertThat(record.owner()).isEqualTo("jtxw");
        assertThat(record.productName()).isEqualTo("乳铁蛋白粉");
        assertThat(record.sku()).isEqualTo("100g");
        assertThat(record.quantity()).isEqualTo(1D);
        assertThat(record.unit()).isEqualTo("件");
        assertThat(record.totalAmount()).isEqualTo(76.96D);
        assertThat(record.productAmount()).isEqualTo(76.96D);
        assertThat(record.paidAmount()).isEqualTo(76.96D);
        assertThat(record.shippingFee()).isZero();
    }

    @Test
    void shouldRejectUnsupportedWorkbookHeaders() throws Exception {
        Path file = testFile("bad.xlsx");
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("orders");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("name");
            header.createCell(1).setCellValue("amount");
            try (OutputStream outputStream = Files.newOutputStream(file)) {
                workbook.write(outputStream);
            }
        }

        assertThatThrownBy(() -> importer().importFile(file, "jtxw"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的订单文件表头");
    }

    private void writeChineseOrderWorkbook(Path file) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("订单数据");
            Row header = sheet.createRow(0);
            String[] headers = {"订单号", "订单提交时间", "订单状态", "店铺名称", "商品名称", "商品链接", "型号款式",
                    "商品数量", "商品金额", "实付金额", "运费"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }

            Row success = sheet.createRow(1);
            success.createCell(0).setCellValue("1");
            success.createCell(1).setCellValue("2026-01-23 02:32:45");
            success.createCell(2).setCellValue("交易成功");
            success.createCell(3).setCellValue("百亿补贴品牌优选");
            success.createCell(4).setCellValue("乳铁蛋白粉");
            success.createCell(5).setCellValue("https://item.taobao.com/item.htm?id=1");
            success.createCell(6).setCellValue("100g");
            success.createCell(7).setCellValue(1D);
            success.createCell(8).setCellValue("￥76.96");
            success.createCell(9).setCellValue("￥76.96");
            success.createCell(10).setCellValue("￥0.00");

            Row closed = sheet.createRow(2);
            closed.createCell(0).setCellValue("2");
            closed.createCell(1).setCellValue("2026-01-23 02:35:45");
            closed.createCell(2).setCellValue("交易关闭");
            closed.createCell(4).setCellValue("已关闭订单");
            closed.createCell(5).setCellValue("https://item.taobao.com/item.htm?id=2");
            closed.createCell(6).setCellValue("100g");
            closed.createCell(7).setCellValue(1D);
            closed.createCell(9).setCellValue("￥76.96");

            try (OutputStream outputStream = Files.newOutputStream(file)) {
                workbook.write(outputStream);
            }
        }
    }

    private ExcelPurchaseImporter importer() {
        return new ExcelPurchaseImporter(new OrderImportMapper(new ProductSpecParser()));
    }

    private Path testFile(String name) throws Exception {
        Path dir = Path.of("target", "excel-importer-test");
        Files.createDirectories(dir);
        return dir.resolve(name);
    }
}
