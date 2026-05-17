package com.jtxw.familyagent.infrastructure.importer;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/05/17/11:28
 * @Description: CSV 订单导入器测试，覆盖标准模板和中文订单导出模板。
 */
class CsvPurchaseImporterTest {
    @Test
    void shouldImportStandardTemplate() throws Exception {
        Path file = testFile("standard.csv");
        Files.writeString(file, """
                order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
                2026-05-01,taobao,JTXW,混合猫砂 12kg,12kg,宠物用品,猫砂,12,kg,89,CNY
                """, StandardCharsets.UTF_8);

        List<RawPurchaseRecord> records = new CsvPurchaseImporter().importFile(file);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).productName()).isEqualTo("混合猫砂 12kg");
        assertThat(records.get(0).quantity()).isEqualTo(12D);
        assertThat(records.get(0).totalAmount()).isEqualTo(89D);
    }

    @Test
    void shouldImportChineseOrderExportTemplate() throws Exception {
        Path file = testFile("chinese.csv");
        Files.writeString(file, """
                订单号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费
                1,2026-01-23 02:32:45,交易成功,百亿补贴品牌优选,乳铁蛋白粉,https://item.taobao.com/item.htm?id=1,100g,1,￥76.96,￥76.96,￥0.00
                2,2026-01-23 02:35:45,交易关闭,百亿补贴品牌优选,已关闭订单,https://item.taobao.com/item.htm?id=2,100g,1,￥76.96,￥76.96,￥0.00
                """, StandardCharsets.UTF_8);

        List<RawPurchaseRecord> records = new CsvPurchaseImporter().importFile(file);

        assertThat(records).hasSize(1);
        RawPurchaseRecord record = records.get(0);
        assertThat(record.orderTime()).isEqualTo("2026-01-23 02:32:45");
        assertThat(record.platform()).isEqualTo("taobao");
        assertThat(record.owner()).isEqualTo("default");
        assertThat(record.productName()).isEqualTo("乳铁蛋白粉");
        assertThat(record.sku()).isEqualTo("100g");
        assertThat(record.quantity()).isEqualTo(1D);
        assertThat(record.unit()).isEqualTo("件");
        assertThat(record.totalAmount()).isEqualTo(76.96D);
        assertThat(record.currency()).isEqualTo("CNY");
    }

    @Test
    void shouldRejectUnsupportedHeaders() throws Exception {
        Path file = testFile("bad.csv");
        Files.writeString(file, """
                name,amount
                猫砂,89
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new CsvPurchaseImporter().importFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 CSV 表头");
    }

    private Path testFile(String name) throws Exception {
        Path dir = Path.of("target", "csv-importer-test");
        Files.createDirectories(dir);
        return dir.resolve(name);
    }
}
