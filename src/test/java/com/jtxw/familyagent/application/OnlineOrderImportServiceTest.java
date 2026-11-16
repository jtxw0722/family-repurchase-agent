package com.jtxw.familyagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.domain.model.*;
import com.jtxw.familyagent.domain.policy.*;
import com.jtxw.familyagent.infrastructure.importer.*;
import com.jtxw.familyagent.infrastructure.persistence.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/15 12:51:46
 * @Description: 在线导入隐私与业务集成测试，覆盖真实 SQLite 的只读预览、选择保存、查重、异常和内存 Excel 读取
 */
class OnlineOrderImportServiceTest {
    /** 隔离数据库所在目录，不保存上传文件。 */
    @TempDir private Path directory;
    /** 隔离数据库，断言原文和个人信息没有写入。 */
    private JdbcTemplate jdbc;
    /** 被测在线业务服务，仅使用本地匹配器。 */
    private OnlineOrderImportService service;
    /** 中文导出模板，加上必须忽略的个人信息列。 */
    private static final String HEADER = "订单编号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费,收货地址,手机号,物流单号\n";

    /** 使用本地猫砂规则及真实规格解析器建立隔离测试依赖。 */
    @BeforeEach void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("online.sqlite")));
        new DatabaseInitializer(jdbc).initialize();
        ProductRuleProvider rules = () -> List.of(new ProductRule("cat_litter", "猫砂", 100,
                List.of("猫砂"), List.of(), "kg", UnitFamily.WEIGHT));
        ProductRuleMatcher matcher = new ProductRuleMatcher(rules);
        ProductSpecParser specs = new ProductSpecParser(new ProductNormalizer(matcher), List.of(new WeightSpecParser()));
        service = new OnlineOrderImportService(new OnlineOrderFileReader(),
                specs, new ChineseOrderExportRowNormalizer(),
                new OrderAmountAllocationPolicy(), new PaymentAdjustmentPolicy(), rules, new PurchaseTimeNormalizer(),
                new OwnerNormalizer(), new PurchaseRecordRepository(jdbc), new NormalizationRuleRepository(jdbc));
    }

    /** 预览不写记录、批次或复核；响应只包含白名单，并且实际购买件数参与总含量计算。 */
    @Test void previewMustBeReadOnlyAndExcludePrivateFields() throws Exception {
        var preview = preview(line("987654321012345678", "猫砂", "2.5kg*4包", "2", "80", "160", "交易成功"));
        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).quantity()).isEqualTo(20D);
        assertThat(preview.rows().get(0).amount()).isEqualTo(160D);
        assertThat(preview.rows().get(0).selected()).isTrue();
        assertThat(new ObjectMapper().writeValueAsString(preview)).doesNotContain("987654321012345678", "SECRET_ADDRESS", "13800000000", "PRIVATE_TRACKING", "商品链接", "online-order.csv");
        assertThat(count("purchase_records")).isZero();
        assertThat(count("raw_import_batches")).isZero();
        assertThat(count("review_items")).isZero();
    }

    /** 只保存勾选且修正后的业务内容，原文、文件名、订单号不进入数据库。 */
    @Test void commitMustSaveOnlySelectedEditedFieldsAndSkipRepeat() {
        var row = preview(line("1", "猫砂", "5kg", "1", "40", "40", "交易成功")).rows().get(0);
        var edited = edit(row, true, "人工修正猫砂", 42D, 5D, "猫砂");
        var ignored = edit(row, false, "不应保存的商品", 50D, 5D, "猫砂");
        var result = service.commit(List.of(edited, ignored));
        assertThat(result.savedRows()).containsExactly(row.rowNumber());
        assertThat(count("purchase_records")).isEqualTo(1);
        var saved = jdbc.queryForMap("SELECT * FROM purchase_records");
        assertThat(saved.get("product_name")).isEqualTo("人工修正猫砂");
        assertThat(saved.get("total_amount")).isEqualTo(42D);
        assertThat(saved.get("source_file")).isEqualTo("online-import");
        assertThat(saved.get("source_text")).isNull();
        assertThat(saved.get("note")).isNull();
        assertThat(saved.toString()).doesNotContain("SECRET_ADDRESS", "PRIVATE_TRACKING", "13800000000");
        assertThat(service.commit(List.of(edited)).duplicateRows()).containsExactly(row.rowNumber());
        assertThat(count("purchase_records")).isEqualTo(1);
    }

    /** 历史和文件内重复均默认不勾选，确认前查重防止重复预览导致重复保存。 */
    @Test void duplicateGiftAndInvalidRowsMustNotBeSelected() {
        String normal = line("1", "猫砂", "5kg", "1", "40", "40", "交易成功");
        var row = preview(normal).rows().get(0);
        service.commit(List.of(row));
        var duplicate = preview(normal + line("2", "猫砂", "5kg", "1", "40", "40", "交易成功"));
        assertThat(duplicate.rows()).allMatch(item -> !item.selected() && item.warnings().stream().anyMatch(value -> value.contains("重复")));
        var gift = preview(line("3", "【赠品】猫砂", "1kg", "1", "10", "0", "交易成功")).rows().get(0);
        assertThat(gift.selected()).isFalse();
        assertThat(gift.amount()).isZero();
        service.commit(List.of(edit(gift, true, gift.productName(), 0D, 1D, "猫砂")));
        assertThat(jdbc.queryForObject("SELECT decision FROM purchase_records WHERE total_amount=0", String.class)).isEqualTo("exclude");
        var invalid = preview(line("4", "猫砂", "无规格", "1", "数字错误", "40", "交易成功")).rows().get(0);
        assertThat(invalid.selected()).isFalse();
        assertThat(invalid.warnings()).isNotEmpty();
    }

    /** 不能将缺失日期默认为今天；任何一条勾选数据非法时均不保存。 */
    @Test void invalidSelectedRowsMustPreventWrites() {
        var row = preview(line("1", "猫砂", "5kg", "1", "40", "40", "交易成功")).rows().get(0);
        assertThatThrownBy(() -> service.commit(List.of(row, edit(row, true, "坏数据", -1D, 0D, "猫砂"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("purchase_records")).isZero();
        var missingDate = preview(line("1", "猫砂", "5kg", "1", "40", "40", "交易成功").replace("2026-11-01 12:00:00", "")).rows().get(0);
        assertThat(missingDate.purchaseDate()).isEmpty();
        assertThat(missingDate.selected()).isFalse();
    }

    /** 经真实事务代理保存时，第二行数据库失败必须回滚第一行，避免部分成功后用户重复操作。 */
    @Test void databaseFailureMustRollbackEntireConfirmation() {
        var first = preview(line("1", "猫砂", "5kg", "1", "40", "40", "交易成功")).rows().get(0);
        var second = new OnlineImportRow(first.rowNumber() + 1, "第二条猫砂", "猫砂", "5kg", 40D, 5D,
                "kg", "taobao", first.purchaseDate(), "tester", "测试店铺", true, List.of());
        jdbc.execute("CREATE TRIGGER reject_second BEFORE INSERT ON purchase_records WHEN NEW.product_name='第二条猫砂' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.setProxyTargetClass(true);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactional = (OnlineOrderImportService) factory.getProxy();
        assertThatThrownBy(() -> transactional.commit(List.of(first, second))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("purchase_records")).isZero();
    }

    /** CSV BOM、GB18030 编码与京东链接均按业务列解析，不依赖文件名判断平台。 */
    @Test void shouldSupportBomGbkAndJdBusinessRows() {
        String csv = HEADER + line("1", "猫砂", "5kg", "1", "40", "40", "交易成功").replace("https://item.taobao.com/1", "https://item.jd.com/1.html");
        var bom = service.preview(Base64.getEncoder().encodeToString(("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8)), "csv", "tester");
        var gbk = service.preview(Base64.getEncoder().encodeToString(csv.getBytes(java.nio.charset.Charset.forName("GB18030"))), "csv", "tester");
        assertThat(bom.rows().get(0).platform()).isEqualTo("jd");
        assertThat(gbk.rows().get(0).platform()).isEqualTo("jd");
    }

    /** 关闭交易及其续行不会挂到上一订单，不误入购买记录。 */
    @Test void closedOrdersMustBeReportedAsSkipped() {
        var result = preview(line("1", "猫砂", "5kg", "1", "40", "40", "交易关闭"));
        assertThat(result.rows()).isEmpty();
        assertThat(result.skippedRows()).isEqualTo(1);
    }

    /** 数量、规格和同单位历史中位数的明显异常均呈现核对提示。 */
    @Test void outlierAndCardMustRequireReview() {
        jdbc.update("INSERT INTO purchase_records(product_name, normalized_name, unit, quantity, total_amount, unit_price, currency, decision, is_duplicate, dedupe_status, created_at) VALUES ('历史猫砂','猫砂','kg',5,40,8,'CNY','include',0,'unique','2026-11-01')");
        assertThat(preview(line("1", "猫砂", "5kg", "1", "1000", "1000", "交易成功")).rows().get(0).warnings())
                .anyMatch(value -> value.contains("中位数"));
        var card = preview(line("2", "猫砂次卡", "2.5kg*4包*6次", "1", "440.1", "440.1", "交易成功")).rows().get(0);
        assertThat(card.quantity()).isEqualTo(60D);
        assertThat(card.selected()).isFalse();
    }

    /** XLSX 空白列不导致字段错位；只按白名单读取，不返回个人信息列。 */
    @Test void xlsxMustKeepColumnPositionsAndIgnorePrivateColumns() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            String[] headers = HEADER.trim().split(",");
            var header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            header.createCell(headers.length).setCellValue("");
            header.createCell(headers.length + 1).setCellValue("");
            var row = sheet.createRow(1);
            String[] fields = line("987654321012345678", "猫砂", "5kg", "1", "40", "40", "交易成功").trim().split(",");
            for (int i = 0; i < fields.length; i++) row.createCell(i).setCellValue(fields[i]);
            workbook.write(output); bytes = output.toByteArray();
        }
        var result = service.preview(Base64.getEncoder().encodeToString(bytes), "xlsx", "tester");
        assertThat(result.rows()).hasSize(1);
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("SECRET_ADDRESS", "13800000000", "PRIVATE_TRACKING", "987654321012345678");
    }

    /** 错误提示不得包含异常文件的原文，格式/编码/行数超限均不写库。 */
    @Test void malformedInputMustNotLeakOriginalContent() {
        assertThatThrownBy(() -> service.preview(Base64.getEncoder().encodeToString("SECRET_ADDRESS,13800000000".getBytes(StandardCharsets.UTF_8)), "csv", "tester"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("SECRET_ADDRESS");
        assertThatThrownBy(() -> service.preview("not-base64", "csv", "tester")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("purchase_records")).isZero();
    }

    /** 对原始字节、解压总量和行数设置硬上限，超限时不产生持久化记录。 */
    @Test void limitsMustRejectOversizedContentAndRows() throws Exception {
        assertThatThrownBy(() -> service.preview(Base64.getEncoder().encodeToString(new byte[OnlineOrderImportService.MAX_FILE_BYTES + 1]), "csv", "tester"))
                .isInstanceOf(IllegalArgumentException.class);
        String rows = line("1", "猫砂", "5kg", "1", "40", "40", "交易成功").repeat(OnlineOrderFileReader.MAX_ROWS + 1);
        assertThatThrownBy(() -> preview(rows)).isInstanceOf(IllegalArgumentException.class);
        byte[] bomb;
        try (var output = new ByteArrayOutputStream(); var zip = new java.util.zip.ZipOutputStream(output)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < 33; i++) zip.write(chunk);
            zip.closeEntry(); zip.finish(); bomb = output.toByteArray();
        }
        assertThatThrownBy(() -> service.preview(Base64.getEncoder().encodeToString(bomb), "xlsx", "tester"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("purchase_records")).isZero();
    }

    /** 如果 POI 被其他组件设置为落盘模式，在线导入必须拒绝，而不是创建上传临时文件。 */
    @Test void xlsxMustFailClosedWhenTemporaryFilesAreEnabled() {
        int previous = org.apache.poi.openxml4j.util.ZipInputStreamZipEntrySource.getThresholdBytesForTempFiles();
        try {
            org.apache.poi.openxml4j.util.ZipInputStreamZipEntrySource.setThresholdBytesForTempFiles(0);
            assertThatThrownBy(() -> new OnlineOrderFileReader().read(new byte[]{'P','K',3,4}, "xlsx"))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally { org.apache.poi.openxml4j.util.ZipInputStreamZipEntrySource.setThresholdBytesForTempFiles(previous); }
    }

    /** 构建内存 CSV 预览。 */
    private OnlineOrderImportService.Preview preview(String rows) {
        return service.preview(Base64.getEncoder().encodeToString((HEADER + rows).getBytes(StandardCharsets.UTF_8)), "csv", "tester");
    }
    /** 创建带隐私哨兵值的合成原始行，不使用用户真实订单。 */
    private String line(String id, String product, String sku, String count, String productAmount, String paid, String status) {
        return String.join(",", id, "2026-11-01 12:00:00", status, "测试店铺", product, "https://item.taobao.com/1", sku,
                count, productAmount, paid, "0", "SECRET_ADDRESS", "13800000000", "PRIVATE_TRACKING") + "\n";
    }
    /** 创建用户选择并修改后的业务行，丢弃服务端提示内容以验证服务端重新校验。 */
    private OnlineImportRow edit(OnlineImportRow row, boolean selected, String name, Double amount, Double quantity, String normalized) {
        return new OnlineImportRow(row.rowNumber(), name, normalized, row.sku(), amount, quantity, row.unit(), row.platform(),
                row.purchaseDate(), row.owner(), row.shopName(), selected, List.of());
    }
    /** 获取隔离表的行数，表名只来自测试常量。 */
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
