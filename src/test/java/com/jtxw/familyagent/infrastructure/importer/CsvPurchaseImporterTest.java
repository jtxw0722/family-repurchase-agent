package com.jtxw.familyagent.infrastructure.importer;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import com.jtxw.familyagent.domain.policy.ProductSpecParser;
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
                2026-05-01,taobao,jtxw,混合猫砂 12kg,12kg,宠物用品,猫砂,12,kg,89,CNY
                """, StandardCharsets.UTF_8);

        List<RawPurchaseRecord> records = importer().importFile(file);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).owner()).isEqualTo("jtxw");
        assertThat(records.get(0).productName()).isEqualTo("混合猫砂 12kg");
        assertThat(records.get(0).quantity()).isEqualTo(12D);
        assertThat(records.get(0).unit()).isEqualTo("kg");
        assertThat(records.get(0).totalAmount()).isEqualTo(89D);
    }

    @Test
    void shouldParseWeightSpecFromSkuBeforeProductName() throws Exception {
        Path file = testFile("spec.csv");
        Files.writeString(file, """
                order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
                2026-05-01,taobao,jtxw,名创优品钠基矿猫砂5kg*8包,【除臭加倍】升级款自然原味10斤*8包,宠物用品,猫砂,1,件,119.3,CNY
                """, StandardCharsets.UTF_8);

        RawPurchaseRecord record = importer().importFile(file).get(0);

        assertThat(record.quantity()).isEqualTo(40D);
        assertThat(record.unit()).isEqualTo("kg");
        assertThat(record.totalAmount()).isEqualTo(119.3D);
    }

    @Test
    void shouldFlagMultiDeliverySpecForReview() throws Exception {
        Path file = testFile("spec-review.csv");
        Files.writeString(file, """
                order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
                2026-05-01,taobao,jtxw,猫砂次卡,2.5kg*4包*6次,宠物用品,猫砂,1,件,119.3,CNY
                """, StandardCharsets.UTF_8);

        RawPurchaseRecord record = importer().importFile(file).get(0);

        assertThat(record.quantity()).isEqualTo(60D);
        assertThat(record.unit()).isEqualTo("kg");
        assertThat(record.specReviewRequired()).isTrue();
        assertThat(record.specReviewReasonCode()).isEqualTo("SPEC_MULTIPACK_TIMES");
    }

    @Test
    void shouldNotFlagTissuePackageCountAsMultiDeliverySpec() throws Exception {
        Path file = testFile("tissue-package.csv");
        Files.writeString(file, """
                order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
                2026-05-01,taobao,jtxw,Tempo得宝便携式小包纸巾咖啡香手帕纸4层12包,暂无,日用品,纸巾,1,件,12.9,CNY
                """, StandardCharsets.UTF_8);

        RawPurchaseRecord record = importer().importFile(file).get(0);

        assertThat(record.specReviewRequired()).isTrue();
        assertThat(record.specReviewReasonCode()).isEqualTo("QUANTITY_UNIT_PARSE_REVIEW");
        assertThat(record.specReviewReasonCode()).isNotEqualTo("SPEC_MULTIPACK_TIMES");
    }

    @Test
    void shouldImportChineseOrderExportTemplate() throws Exception {
        Path file = testFile("orders-chinese.csv");
        Files.writeString(file, """
                订单号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费
                1,2026-01-23 02:32:45,交易成功,百亿补贴品牌优选,乳铁蛋白粉,https://item.taobao.com/item.htm?id=1,100g,1,￥76.96,￥76.96,￥0.00
                2,2026-01-23 02:35:45,交易关闭,百亿补贴品牌优选,已关闭订单,https://item.taobao.com/item.htm?id=2,100g,1,￥76.96,￥76.96,￥0.00
                """, StandardCharsets.UTF_8);

        List<RawPurchaseRecord> records = importer().importFile(file, "chinese");

        assertThat(records).hasSize(1);
        RawPurchaseRecord record = records.get(0);
        assertThat(record.orderTime()).isEqualTo("2026-01-23 02:32:45");
        assertThat(record.orderGroupKey()).isEqualTo("1");
        assertThat(record.platform()).isEqualTo("taobao");
        assertThat(record.owner()).isEqualTo("chinese");
        assertThat(record.productName()).isEqualTo("乳铁蛋白粉");
        assertThat(record.sku()).isEqualTo("100g");
        assertThat(record.quantity()).isEqualTo(1D);
        assertThat(record.unit()).isEqualTo("件");
        assertThat(record.totalAmount()).isEqualTo(76.96D);
        assertThat(record.productAmount()).isEqualTo(76.96D);
        assertThat(record.paidAmount()).isEqualTo(76.96D);
        assertThat(record.shippingFee()).isZero();
        assertThat(record.currency()).isEqualTo("CNY");
    }

    @Test
    void shouldImportChineseContinuationRowsWithInheritedOrderContext() throws Exception {
        Path file = testFile("orders-continuation.csv");
        Files.writeString(file, """
                订单号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费
                T1,2025-08-07 22:28:15,交易成功,尾巴生活旗舰店,尾巴生活植物木薯豆腐猫砂,https://item.taobao.com/item.htm?id=1,15kg,1,78.85,320.84,0
                ,,,,尾巴生活彩虹泥主食餐盒,,,1,241.99,,
                """, StandardCharsets.UTF_8);

        List<RawPurchaseRecord> records = importer().importFile(file, "jtxw");

        assertThat(records).hasSize(2);
        assertThat(records)
                .extracting(RawPurchaseRecord::orderGroupKey)
                .containsOnly("T1");
        assertThat(records.get(0).productAmount()).isEqualTo(78.85D);
        assertThat(records.get(1).productName()).isEqualTo("尾巴生活彩虹泥主食餐盒");
        assertThat(records.get(1).productAmount()).isEqualTo(241.99D);
        assertThat(records.get(1).paidAmount()).isEqualTo(320.84D);
        assertThat(records)
                .extracting(RawPurchaseRecord::paidAmountIncludesShipping)
                .containsOnly(true);
    }

    @Test
    void shouldMultiplyChineseProductAmountByPurchaseQuantity() throws Exception {
        Path file = testFile("orders-quantity.csv");
        Files.writeString(file, """
                订单号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费
                T2,2025-08-08 10:00:00,交易成功,护舒宝旗舰店,护舒宝卫生巾,https://item.taobao.com/item.htm?id=2,32片*3包,3,45.88,137.63,0
                """, StandardCharsets.UTF_8);

        List<RawPurchaseRecord> records = importer().importFile(file, "jtxw");

        assertThat(records).hasSize(1);
        assertThat(records.get(0).productAmount()).isEqualTo(137.64D);
        assertThat(records.get(0).paidAmount()).isEqualTo(137.63D);
    }

    @Test
    void shouldSkipClosedChineseOrderAndItsContinuationRows() throws Exception {
        Path file = testFile("orders-closed-continuation.csv");
        Files.writeString(file, """
                订单号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费
                CLOSED-1,2025-08-08 10:00:00,交易关闭,关闭店铺,关闭主行,https://item.taobao.com/item.htm?id=1,1件,1,10,10,0
                ,,,,关闭续行,,,1,20,,
                SUCCESS-1,2025-08-08 11:00:00,交易成功,成功店铺,成功订单,https://item.taobao.com/item.htm?id=2,1件,1,30,30,0
                """, StandardCharsets.UTF_8);

        List<RawPurchaseRecord> records = importer().importFile(file, "jtxw");

        assertThat(records).hasSize(1);
        assertThat(records.get(0).orderGroupKey()).isEqualTo("SUCCESS-1");
        assertThat(records.get(0).productName()).isEqualTo("成功订单");
    }

    @Test
    void shouldRejectUnsupportedHeaders() throws Exception {
        Path file = testFile("bad.csv");
        Files.writeString(file, """
                name,amount
                猫砂,89
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> importer().importFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的订单文件表头");
    }

    @Test
    void shouldUseOwnerOverride() throws Exception {
        Path file = testFile("chinese.csv");
        Files.writeString(file, """
                订单号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费
                1,2026-01-23 02:32:45,交易成功,百亿补贴品牌优选,乳铁蛋白粉,https://item.taobao.com/item.htm?id=1,100g,1,￥76.96,￥76.96,￥0.00
                """, StandardCharsets.UTF_8);

        List<RawPurchaseRecord> records = importer().importFile(file, "jtxw");

        assertThat(records.get(0).owner()).isEqualTo("jtxw");
        assertThat(records.get(0).orderGroupKey()).isEqualTo("1");
    }

    @Test
    void shouldPreferMainOrderIdWhenMainAndSubOrderIdBothExist() throws Exception {
        Path file = testFile("main-sub-order.csv");
        Files.writeString(file, """
                main_order_id,sub_order_id,order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
                MAIN-1,SUB-1,2026-05-01,taobao,jtxw,混合猫砂 12kg,12kg,宠物用品,猫砂,12,kg,89,CNY
                """, StandardCharsets.UTF_8);

        List<RawPurchaseRecord> records = importer().importFile(file);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).orderGroupKey()).isEqualTo("MAIN-1");
    }

    @Test
    void shouldRejectChineseTemplateWithoutOwnerContext() throws Exception {
        Path file = testFile("chinese.csv");
        Files.writeString(file, """
                订单号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费
                1,2026-01-23 02:32:45,交易成功,百亿补贴品牌优选,乳铁蛋白粉,https://item.taobao.com/item.htm?id=1,100g,1,￥76.96,￥76.96,￥0.00
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> importer().importFile(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法确定订单归属 owner");
    }

    private CsvPurchaseImporter importer() {
        return new CsvPurchaseImporter(new OrderImportMapper(new ProductSpecParser()));
    }

    private Path testFile(String name) throws Exception {
        Path dir = Path.of("target", "csv-importer-test");
        Files.createDirectories(dir);
        return dir.resolve(name);
    }
}
