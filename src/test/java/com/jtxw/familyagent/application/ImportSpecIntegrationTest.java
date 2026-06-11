package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.PriceBaselineResult;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.ImportResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import com.jtxw.familyagent.domain.policy.*;
import com.jtxw.familyagent.infrastructure.importer.CsvPurchaseImporter;
import com.jtxw.familyagent.infrastructure.importer.ExcelPurchaseImporter;
import com.jtxw.familyagent.infrastructure.importer.OrderImportMapper;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.ImportBatchRepository;
import com.jtxw.familyagent.infrastructure.persistence.ProductAliasRepository;
import com.jtxw.familyagent.infrastructure.persistence.ProductNegativeAliasRepository;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * @Author: jtxw
 * @Date: 2026/05/29/22:45
 * @Description: 导入规格解析和价格比较命中的集成测试。
 */
class ImportSpecIntegrationTest {

    private PriceDecisionPolicy newPriceDecisionPolicy() {
        return new PriceDecisionPolicy(new PriceDecisionThresholds(0.92D, 1.12D));
    }

    @Test
    void shouldImportCatLitterAsKgPriceSampleAndMatchComparePrice() throws Exception {
        Fixture fixture = fixture("cat-litter-spec.sqlite");
        Path file = fixture.file("orders.csv");
        Files.writeString(file, """
                order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
                2026-05-01,taobao,jtxw,名创优品钠基矿猫砂5kg*8包,【除臭加倍】升级款自然原味10斤*8包,宠物用品,猫砂,1,件,119.3,CNY
                """, StandardCharsets.UTF_8);

        fixture.importService.importFile(file, "jtxw");

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂");
        assertThat(records).hasSize(1);
        PurchaseRecord record = records.get(0);
        assertThat(record.normalizedName()).isEqualTo("猫砂");
        assertThat(record.quantity()).isEqualTo(40D);
        assertThat(record.unit()).isEqualTo("kg");
        assertThat(record.unitPrice()).isCloseTo(2.9825D, offset(0.00001D));

        PriceDecisionResult result = fixture.priceService.comparePrice("名创优品猫砂", 119.3D, 40D, "kg");

        assertThat(result.baseline().sampleSize()).isGreaterThan(0);
        assertThat(result.evidence().excludedReasons())
                .noneMatch(reason -> reason.contains("单位不一致") && reason.contains("13"));
    }

    @Test
    void shouldAllocateOrderPaidAmountBeforeCalculatingCatLitterUnitPrice() throws Exception {
        Fixture fixture = fixture("cat-litter-order-allocation.sqlite");
        Path file = fixture.file("allocated-orders.csv");
        Files.writeString(file, """
                order_id,order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,product_amount,paid_amount,shipping_fee,currency
                T20260611001,2026-05-01 10:00:00,taobao,jtxw,混合猫砂 15kg,15kg,宠物用品,猫砂,1,件,320.84,78.85,320.84,0,CNY
                T20260611001,2026-05-01 10:00:00,taobao,jtxw,卫生巾 10片,10片,日用品,卫生巾,1,件,320.84,241.99,320.84,0,CNY
                """, StandardCharsets.UTF_8);

        fixture.importService.importFile(file, "jtxw");

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂");
        assertThat(records).hasSize(1);
        PurchaseRecord record = records.get(0);
        assertThat(record.totalAmount()).isEqualTo(78.85D);
        assertThat(record.paidAmount()).isEqualTo(78.85D);
        assertThat(record.shippingFee()).isZero();
        assertThat(record.amountSource()).isEqualTo(OrderAmountAllocationPolicy.SOURCE_ALLOCATED_ORDER_AMOUNT);
        assertThat(record.quantity()).isEqualTo(15D);
        assertThat(record.unit()).isEqualTo("kg");
        assertThat(record.unitPrice()).isCloseTo(5.256667D, offset(0.000001D));
        assertThat(record.unitPrice()).isNotCloseTo(21.3893D, offset(0.0001D));
    }

    @Test
    void shouldImportChineseContinuationRowsAndAllocateCatLitterLineAmount() throws Exception {
        Fixture fixture = fixture("chinese-cat-litter-continuation.sqlite");
        Path file = fixture.file("chinese-cat-litter.csv");
        Files.writeString(file, """
                订单号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费
                T1,2025-08-07 22:28:15,交易成功,尾巴生活旗舰店,尾巴生活植物木薯豆腐猫砂,https://item.taobao.com/item.htm?id=1,15kg,1,78.85,320.84,0
                ,,,,尾巴生活彩虹泥主食餐盒,,,1,241.99,,
                """, StandardCharsets.UTF_8);

        ImportResult result = fixture.importService.importFile(file, "jtxw");

        assertThat(fixture.purchaseRecordRepository.listByBatchId(result.batchId())).hasSize(2);
        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂");
        assertThat(records).hasSize(1);
        PurchaseRecord catLitter = records.get(0);
        assertThat(catLitter.totalAmount()).isEqualTo(78.85D);
        assertThat(catLitter.paidAmount()).isEqualTo(78.85D);
        assertThat(catLitter.quantity()).isEqualTo(15D);
        assertThat(catLitter.unit()).isEqualTo("kg");
        assertThat(catLitter.unitPrice()).isCloseTo(5.256667D, offset(0.000001D));
        assertThat(catLitter.unitPrice()).isNotCloseTo(21.3893D, offset(0.0001D));
    }

    @Test
    void shouldImportSanitaryPadContinuationRowsAndExcludeNewMemberGift() throws Exception {
        Fixture fixture = fixture("chinese-sanitary-continuation.sqlite");
        Path file = fixture.file("chinese-sanitary.csv");
        Files.writeString(file, """
                订单号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费
                T2,2025-08-07 22:30:00,交易成功,护舒宝旗舰店,护舒宝纯棉卫生巾,https://item.taobao.com/item.htm?id=1,44片,1,29.90,207.71,0
                ,,,,护舒宝防漏考拉安睡裤,,,1,129.90,,
                ,,,,护舒宝考拉呼呼超长夜用卫生巾,,,1,47.90,,
                ,,,,【新会礼】飘柔指定回购券,,,1,0.01,,
                """, StandardCharsets.UTF_8);

        ImportResult result = fixture.importService.importFile(file, "jtxw");

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listByBatchId(result.batchId());
        assertThat(records).hasSize(4);
        PurchaseRecord firstSanitaryPad = records.stream()
                .filter(record -> record.productName().contains("纯棉卫生巾"))
                .findFirst()
                .orElseThrow();
        assertThat(firstSanitaryPad.totalAmount()).isNotEqualTo(207.71D);
        assertThat(firstSanitaryPad.totalAmount()).isCloseTo(29.90D, offset(0.01D));
        if ("include".equals(firstSanitaryPad.decision())) {
            assertThat(firstSanitaryPad.totalAmount()).isNotEqualTo(207.71D);
        }
        PurchaseRecord newMemberGift = records.stream()
                .filter(record -> record.productName().contains("新会礼"))
                .findFirst()
                .orElseThrow();
        assertThat(newMemberGift.decision()).isEqualTo("exclude");
        assertThat(newMemberGift.amountSource())
                .isEqualTo(OrderAmountAllocationPolicy.SOURCE_ORDER_AMOUNT_ALLOCATION_FALLBACK);
    }

    @Test
    void shouldNotAddShippingTwiceForChineseTempoTissueOrder() throws Exception {
        Fixture fixture = fixture("chinese-tempo-shipping.sqlite");
        Path file = fixture.file("chinese-tempo.csv");
        Files.writeString(file, """
                订单号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费
                T3,2025-08-07 22:40:00,交易成功,Tempo旗舰店,Tempo纸巾,https://item.taobao.com/item.htm?id=1,80抽,1,9.90,52.61,9.90
                ,,,,Tempo纸巾补充装,,,1,12.90,,
                ,,,,Tempo纸巾家庭装,,,1,19.90,,
                ,,,,【新会礼】Tempo回购券,,,1,0.01,,
                """, StandardCharsets.UTF_8);

        ImportResult result = fixture.importService.importFile(file, "jtxw");

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listByBatchId(result.batchId());
        assertThat(records).hasSize(4);
        assertThat(records)
                .extracting(PurchaseRecord::totalAmount)
                .allMatch(amount -> amount <= 52.61D);
        PurchaseRecord firstTempo = records.stream()
                .filter(record -> record.productName().equals("Tempo纸巾"))
                .findFirst()
                .orElseThrow();
        assertThat(firstTempo.totalAmount()).isNotEqualTo(62.51D);
        assertThat(firstTempo.totalAmount()).isCloseTo(12.20D, offset(0.01D));
        assertThat(firstTempo.totalAmount()).isEqualTo(firstTempo.paidAmount());
    }

    @Test
    void shouldUseChineseLineProductAmountMultipliedByPurchaseQuantity() throws Exception {
        Fixture fixture = fixture("chinese-product-amount-quantity.sqlite");
        Path file = fixture.file("chinese-quantity.csv");
        Files.writeString(file, """
                订单号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费
                T4,2025-08-08 10:00:00,交易成功,护舒宝旗舰店,护舒宝卫生巾,https://item.taobao.com/item.htm?id=4,32片,3,45.88,137.63,0
                """, StandardCharsets.UTF_8);

        ImportResult result = fixture.importService.importFile(file, "jtxw");

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listByBatchId(result.batchId());
        assertThat(records).hasSize(1);
        PurchaseRecord record = records.get(0);
        assertThat(record.productAmount()).isEqualTo(137.64D);
        assertThat(record.totalAmount()).isEqualTo(137.63D);
        assertThat(record.quantity()).isEqualTo(96D);
        assertThat(record.unitPrice()).isCloseTo(1.433646D, offset(0.000001D));
        assertThat(record.amountSource()).isNotEqualTo(PaymentAdjustmentPolicy.SOURCE_ORDER_AMOUNT_ANOMALY_FALLBACK);
    }

    @Test
    void shouldExcludeSingleLineOrderAmountAnomalyFromPriceHistory() throws Exception {
        Fixture fixture = fixture("single-line-amount-anomaly.sqlite");
        Path file = fixture.file("single-line-anomaly.csv");
        Files.writeString(file, """
                order_id,order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,product_amount,paid_amount,shipping_fee,currency
                A1,2025-08-07 22:28:15,taobao,jtxw,尾巴生活植物木薯豆腐猫砂,15kg,宠物用品,猫砂,1,件,320.84,78.85,320.84,0,CNY
                """, StandardCharsets.UTF_8);

        ImportResult result = fixture.importService.importFile(file, "jtxw");

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listByBatchId(result.batchId());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).decision()).isEqualTo("exclude");
        assertThat(records.get(0).amountSource())
                .isEqualTo(PaymentAdjustmentPolicy.SOURCE_ORDER_AMOUNT_ANOMALY_FALLBACK);
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains(PaymentAdjustmentPolicy.REASON_ORDER_AMOUNT_ANOMALY);
        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂")).isEmpty();
    }

    @Test
    void shouldExcludeAmountFallbackRecordsAndCreateReviewItem() throws Exception {
        Fixture fixture = fixture("amount-allocation-fallback.sqlite");
        Path file = fixture.file("amount-fallback-orders.csv");
        Files.writeString(file, """
                order_id,order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,product_amount,paid_amount,shipping_fee,currency
                T20260611002,2026-05-01 11:00:00,taobao,jtxw,赠品礼包,赠品,其他,赠品,1,件,150,50,150,0,CNY
                T20260611002,2026-05-01 11:00:00,taobao,jtxw,邮费补拍,邮费,其他,邮费,1,件,150,100,150,0,CNY
                """, StandardCharsets.UTF_8);

        ImportResult result = fixture.importService.importFile(file, "jtxw");

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listByBatchId(result.batchId());
        assertThat(records).hasSize(2);
        assertThat(records)
                .extracting(PurchaseRecord::decision)
                .containsOnly("exclude");
        assertThat(records)
                .extracting(PurchaseRecord::amountSource)
                .containsOnly(OrderAmountAllocationPolicy.SOURCE_ORDER_AMOUNT_ALLOCATION_FALLBACK);
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains(OrderAmountAllocationPolicy.REASON_ORDER_AMOUNT_ALLOCATION);
        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords(records.get(0).normalizedName()))
                .isEmpty();
    }

    @Test
    void shouldImportTissueDrawCountAndQueryPriceBaseline() throws Exception {
        Fixture fixture = fixture("tissue-baseline.sqlite");
        Path file = fixture.file("tissue-orders.csv");
        Files.writeString(file, """
            order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
            2026-04-01,taobao,jtxw,维达超韧抽纸 3层130抽×24包（195×133mm）,3层130抽×24包,日用品,纸巾,1,件,39,CNY
            2026-05-01,taobao,jtxw,某品牌原生木浆抽纸 100抽*20包,100抽*20包,日用品,纸巾,1,件,30,CNY
            """, StandardCharsets.UTF_8);

        fixture.importService.importFile(file, "jtxw");

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listPriceHistoryRecords("纸巾");
        assertThat(records).hasSize(2);
        assertThat(records)
                .extracting(PurchaseRecord::unit)
                .containsOnly("抽");

        PriceBaselineResult result = fixture.priceService.getPriceBaseline("纸巾", null);

        assertThat(result.productName()).isEqualTo("纸巾");
        assertThat(result.normalizedName()).isEqualTo("纸巾");
        assertThat(result.baseline().unit()).isEqualTo("抽");
        assertThat(result.baseline().sampleSize()).isEqualTo(2);
        assertThat(result.baseline().historicalMin()).isNotNull();
        assertThat(result.baseline().historicalMedian()).isNotNull();
        assertThat(result.evidence().source()).isEqualTo("local_purchase_history");
        assertThat(result.evidence().sourceRecords())
                .extracting(PriceDecisionResult.SourceRecord::role)
                .contains("historical_min", "median_sample", "latest");
    }

    @Test
    void shouldImportTissuePackageSkuAsDrawCountWithoutSpecMultipackReview() throws Exception {
        Fixture fixture = fixture("tissue-package-sku.sqlite");
        Path file = fixture.file("tissue-package-sku.csv");
        Files.writeString(file, """
            order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
            2026-05-02,taobao,jtxw,洁柔无香纸巾face抽纸面巾纸130抽8包家用实惠装整箱卫生餐巾纸,48包,日用品,纸巾,1,件,69.9,CNY
            """, StandardCharsets.UTF_8);

        ImportResult result = fixture.importService.importFile(file, "jtxw");

        PurchaseRecord record = fixture.purchaseRecordRepository.listByBatchId(result.batchId()).get(0);
        assertThat(record.normalizedName()).isEqualTo("纸巾");
        assertThat(record.quantity()).isEqualTo(6240D);
        assertThat(record.unit()).isEqualTo("抽");
        assertThat(record.decision()).isEqualTo("include");
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .doesNotContain("SPEC_MULTIPACK_TIMES");
        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("纸巾")).hasSize(1);
    }

    @Test
    void shouldExcludePackageOnlyTissueReviewFromBaseline() throws Exception {
        Fixture fixture = fixture("tissue-package-only-review.sqlite");
        Path file = fixture.file("tissue-package-only.csv");
        Files.writeString(file, """
            order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
            2026-05-03,taobao,jtxw,Tempo得宝便携式小包纸巾咖啡香手帕纸4层12包,暂无,日用品,纸巾,1,件,12.9,CNY
            """, StandardCharsets.UTF_8);

        ImportResult result = fixture.importService.importFile(file, "jtxw");

        PurchaseRecord record = fixture.purchaseRecordRepository.listByBatchId(result.batchId()).get(0);
        assertThat(record.normalizedName()).isEqualTo("纸巾");
        assertThat(record.decision()).isEqualTo("exclude");
        assertThat(record.unit()).isEqualTo("抽");
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains("QUANTITY_UNIT_PARSE_REVIEW")
                .doesNotContain("SPEC_MULTIPACK_TIMES");
        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("纸巾")).isEmpty();
    }

    @Test
    void shouldExcludeRealTimesCardSpecReviewFromBaseline() throws Exception {
        Fixture fixture = fixture("cat-litter-times-card-review.sqlite");
        Path file = fixture.file("cat-litter-times-card.csv");
        Files.writeString(file, """
            order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
            2026-05-04,taobao,jtxw,许翠花植物猫砂,原味植物猫砂6次卡（2.5kg*4包*6次）,宠物用品,猫砂,1,件,299,CNY
            """, StandardCharsets.UTF_8);

        ImportResult result = fixture.importService.importFile(file, "jtxw");

        PurchaseRecord record = fixture.purchaseRecordRepository.listByBatchId(result.batchId()).get(0);
        assertThat(record.normalizedName()).isEqualTo("猫砂");
        assertThat(record.quantity()).isEqualTo(60D);
        assertThat(record.unit()).isEqualTo("kg");
        assertThat(record.decision()).isEqualTo("exclude");
        assertThat(fixture.reviewItemRepository.listPendingDetails())
                .extracting(ReviewItemDetail::reasonCode)
                .contains("SPEC_MULTIPACK_TIMES");
        assertThat(fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂")).isEmpty();
    }

    @Test
    void shouldImportNeutralCountProductsAndUseSameNormalizationOnQuerySide() throws Exception {
        Fixture fixture = fixture("neutral-count-products.sqlite");
        Path file = fixture.file("neutral-count-orders.csv");
        Files.writeString(file, """
            order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
            2026-05-02,taobao,jtxw,洗衣凝珠,12颗*3盒,日用品,洗衣用品,1,件,36.0,CNY
            2026-05-03,taobao,jtxw,凝珠,10颗+2颗,日用品,洗衣用品,1,件,24.0,CNY
            2026-05-04,taobao,jtxw,洗衣凝珠,暂无,日用品,洗衣用品,1,件,29.9,CNY
            """, StandardCharsets.UTF_8);

        fixture.importService.importFile(file, "jtxw");

        List<PurchaseRecord> laundryBeads = fixture.purchaseRecordRepository.listPriceHistoryRecords("洗衣凝珠");
        assertThat(laundryBeads).hasSize(2);
        assertThat(laundryBeads)
                .extracting(PurchaseRecord::unit)
                .containsOnly("颗");
        assertThat(laundryBeads)
                .extracting(PurchaseRecord::quantity)
                .containsExactly(36D, 12D);
        assertThat(laundryBeads.get(0).unitPrice()).isCloseTo(1.0D, offset(0.000001D));
        assertThat(laundryBeads.get(1).unitPrice()).isCloseTo(2.0D, offset(0.000001D));

        List<ReviewItemDetail> reviewItems = fixture.reviewItemRepository.listPendingDetails();
        assertThat(reviewItems).hasSize(1);
        assertThat(reviewItems.get(0).normalizedName()).isEqualTo("洗衣凝珠");
        assertThat(reviewItems.get(0).reasonCode()).isEqualTo("QUANTITY_UNIT_PARSE_REVIEW");

        assertCompareHitsLaundryBeadsHistory(fixture.priceService.comparePrice("凝珠", 18.0D, 18D, "颗"));
        assertCompareHitsLaundryBeadsHistory(fixture.priceService.comparePrice("洗衣珠", 18.0D, 18D, "颗"));

        PriceBaselineResult baseline = fixture.priceService.getPriceBaseline("洗衣凝珠", "颗");
        assertThat(baseline.normalizedName()).isEqualTo("洗衣凝珠");
        assertThat(baseline.baseline().sampleSize()).isEqualTo(2);
        assertThat(baseline.evidence().sourceRecords())
                .extracting(PriceDecisionResult.SourceRecord::productName)
                .doesNotContain("洗衣凝珠 暂无");
    }

    @Test
    void shouldNormalizeOwnerAndKeepDateTimeWhenImportingCsvAndXlsx() throws Exception {
        Fixture fixture = fixture("owner-time-import.sqlite");
        Path csv = fixture.file("owner-time.csv");
        Files.writeString(csv, """
            order_time,platform,owner,product_name,sku,category,sub_category,quantity,unit,total_amount,currency
            2019-11-11 00:12:11,taobao,JTXW,混合猫砂 12kg,12kg,宠物用品,猫砂,12,kg,89,CNY
            """, StandardCharsets.UTF_8);
        Path xlsx = fixture.file("owner-time.xlsx");
        writeStandardWorkbook(xlsx, "2019-11-12 01:02:03", "LJ", "混合猫砂 24kg", "24kg", 24D, 139D);

        fixture.importService.importFile(csv, null);
        fixture.importService.importFile(xlsx, null);

        List<PurchaseRecord> records = fixture.purchaseRecordRepository.listPriceHistoryRecords("猫砂");
        assertThat(records)
                .extracting(PurchaseRecord::owner)
                .contains("jtxw", "lj");
        assertThat(records)
                .extracting(PurchaseRecord::orderTime)
                .contains("2019-11-11 00:12:11", "2019-11-12 01:02:03");
    }

    private void assertCompareHitsLaundryBeadsHistory(PriceDecisionResult result) {
        assertThat(result.normalizedName()).isEqualTo("洗衣凝珠");
        assertThat(result.baseline().unit()).isEqualTo("颗");
        assertThat(result.baseline().sampleSize()).isEqualTo(2);
        assertThat(result.evidence().sourceRecords())
                .extracting(PriceDecisionResult.SourceRecord::productName)
                .containsOnly("洗衣凝珠", "凝珠");
        assertThat(result.evidence().excludedReasons())
                .noneMatch(reason -> reason.contains("单位不一致"));
    }

    private Fixture fixture(String dbName) throws Exception {
        Path dir = Path.of("target", "import-spec-integration-test");
        Files.createDirectories(dir);
        Path db = dir.resolve(dbName);
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        DatabaseInitializer databaseInitializer = new DatabaseInitializer(jdbcTemplate);
        databaseInitializer.initialize();
        ImportBatchRepository importBatchRepository = new ImportBatchRepository(jdbcTemplate);
        ProductAliasRepository productAliasRepository = new ProductAliasRepository(jdbcTemplate);
        ProductNegativeAliasRepository productNegativeAliasRepository = new ProductNegativeAliasRepository(jdbcTemplate);
        PurchaseRecordRepository purchaseRecordRepository = new PurchaseRecordRepository(jdbcTemplate);
        ReviewItemRepository reviewItemRepository = new ReviewItemRepository(jdbcTemplate);
        ProductSpecParser productSpecParser = new ProductSpecParser();
        OrderImportMapper orderImportMapper = new OrderImportMapper(productSpecParser);
        ProductNormalizer productNormalizer = new ProductNormalizer();
        ProductNameNormalizer productNameNormalizer = new ProductNameNormalizer(productNormalizer, testRules());
        LearningProductNameNormalizer learningProductNameNormalizer = new LearningProductNameNormalizer(
                new ProductTitleCleaner(),
                productAliasRepository,
                productNegativeAliasRepository,
                productNameNormalizer
        );
        ImportApplicationService importService = new ImportApplicationService(
                databaseInitializer,
                new CsvPurchaseImporter(orderImportMapper),
                new ExcelPurchaseImporter(orderImportMapper),
                new DuplicateDetectionPolicy(),
                new PaymentAdjustmentPolicy(),
                new OwnerNormalizer(),
                new PurchaseTimeNormalizer(),
                learningProductNameNormalizer,
                new QuantityUnitParser(),
                new UnitPriceCalculator(),
                importBatchRepository,
                purchaseRecordRepository,
                reviewItemRepository
        );
        PriceAnalysisApplicationService priceService = new PriceAnalysisApplicationService(
                databaseInitializer,
                learningProductNameNormalizer,
                purchaseRecordRepository,
                newPriceDecisionPolicy()
        );
        return new Fixture(importService, priceService, purchaseRecordRepository, reviewItemRepository, dir);
    }

    private void writeStandardWorkbook(Path file,
                                       String orderTime,
                                       String owner,
                                       String productName,
                                       String sku,
                                       double quantity,
                                       double totalAmount) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("orders");
            Row header = sheet.createRow(0);
            String[] headers = {"order_time", "platform", "owner", "product_name", "sku", "category",
                    "sub_category", "quantity", "unit", "total_amount", "currency"};
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(orderTime);
            row.createCell(1).setCellValue("taobao");
            row.createCell(2).setCellValue(owner);
            row.createCell(3).setCellValue(productName);
            row.createCell(4).setCellValue(sku);
            row.createCell(5).setCellValue("宠物用品");
            row.createCell(6).setCellValue("猫砂");
            row.createCell(7).setCellValue(quantity);
            row.createCell(8).setCellValue("kg");
            row.createCell(9).setCellValue(totalAmount);
            row.createCell(10).setCellValue("CNY");
            try (OutputStream outputStream = Files.newOutputStream(file)) {
                workbook.write(outputStream);
            }
        }
    }

    private static List<NormalizationRule> testRules() {
        return List.of(
                new NormalizationRule("test_sanitary_pad", "卫生巾", "片",
                        List.of("卫生巾", "护舒宝"), 900),
                new NormalizationRule("test_laundry_beads", "洗衣凝珠", "颗",
                        List.of("洗衣凝珠", "凝珠", "洗衣珠"), 100),
                new NormalizationRule("test_laundry_supplies", "洗衣用品", "件",
                        List.of("洗衣液", "洗衣用品"), 10)
        );
    }

    private record Fixture(ImportApplicationService importService,
                           PriceAnalysisApplicationService priceService,
                           PurchaseRecordRepository purchaseRecordRepository,
                           ReviewItemRepository reviewItemRepository,
                           Path dir) {
        Path file(String name) {
            return dir.resolve(name);
        }
    }
}
