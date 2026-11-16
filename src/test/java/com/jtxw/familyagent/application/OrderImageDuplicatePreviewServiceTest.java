package com.jtxw.familyagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.application.command.ParseOrderImageCommand;
import com.jtxw.familyagent.domain.model.ParsedDuplicatePreview;
import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.OrderImageDuplicatePolicy;
import com.jtxw.familyagent.infrastructure.ocr.OcrResult;
import com.jtxw.familyagent.infrastructure.ocr.OrderImageBase64Decoder;
import com.jtxw.familyagent.infrastructure.ocr.OrderImageRecognitionService;
import com.jtxw.familyagent.infrastructure.ocr.OrderImageTextParser;
import com.jtxw.familyagent.infrastructure.ocr.ParseOrderImageModelProperties;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @Author: jtxw
 * @Date: 2026/11/11 19:34:39
 * @Description: 截图只读查重应用测试，使用真实 SQLite 验证订单号、商品隔离、旧记录降级及解析无写入行为
 */
class OrderImageDuplicatePreviewServiceTest {
    /** 隔离数据库及合成图片目录，不读取真实家庭订单。 */
    @TempDir
    private Path directory;
    /** 隔离数据库访问模板。 */
    private JdbcTemplate jdbcTemplate;
    /** 被测应用服务复用的真实购买记录仓储。 */
    private PurchaseRecordRepository repository;
    /** 订单号和商品明细核对领域策略。 */
    private final OrderImageDuplicatePolicy policy = new OrderImageDuplicatePolicy();
    /** 被测只读查重服务。 */
    private OrderImageDuplicatePreviewService service;

    /** 创建真实 SQLite 表结构，查重测试开始后不再初始化数据库。 */
    @BeforeEach
    void setUp() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("orders.sqlite")));
        new DatabaseInitializer(jdbcTemplate).initialize();
        repository = new PurchaseRecordRepository(jdbcTemplate);
        service = new OrderImageDuplicatePreviewService(repository, policy);
    }

    /** 真实历史订单号与商品明细一致，返回已上传状态及持久化记录编号。 */
    @Test
    void shouldIdentifySavedOrderAndSerializePreview() {
        long id = repository.save(record("订单号：261108-090450235800592"));
        ParsedPurchaseCandidate enriched = enrich(candidate(), "订单号: 261108090450235800592 | 复制");
        assertThat(enriched.duplicate()).isEqualTo(new ParsedDuplicatePreview(ParsedDuplicatePreview.SAVED, id));
        assertThat(new ObjectMapper().valueToTree(enriched).path("duplicate").path("status").asText()).isEqualTo("saved");
        assertThat(enriched.note()).isEqualTo(candidate().note());
    }

    /** 缺少任一侧订单号时，仅显示可能重复，不能把同日同价当成确认已上传。 */
    @ParameterizedTest
    @ValueSource(strings = {"missing-current", "missing-history", "missing-both"})
    void shouldOnlySuspectLegacyMatchesWithoutOrderEvidence(String missingSide) {
        String savedSource = missingSide.equals("missing-current") ? "订单号：261108090450235800592" : "旧的截断原文";
        repository.save(record(savedSource));
        String rawText = missingSide.equals("missing-history") ? "订单号：261108090450235800592" : "快递单号：261108090450235800592";
        assertThat(enrich(candidate(), rawText).duplicate().status()).isEqualTo(ParsedDuplicatePreview.POSSIBLE);
    }

    /** 不同明确订单号是独立购买，不应误报重复。 */
    @Test
    void shouldNotConfuseDifferentOrdersWithSameItemAndPrice() {
        repository.save(record("订单号：261108090450235800591"));
        assertThat(enrich(candidate(), "订单编号：261108090450235800592").duplicate().status())
                .isEqualTo(ParsedDuplicatePreview.NONE);
    }

    /** 同一订单中的其他商品、规格、购买件数不能继承已经保存的状态。 */
    @ParameterizedTest
    @ValueSource(strings = {"name", "sku", "count", "unit", "platform", "owner", "date", "price"})
    void shouldKeepEachItemAndPurchaseScopeIndependent(String difference) {
        repository.save(record("订单号：261108090450235800592"));
        ParsedPurchaseCandidate original = candidate();
        ParsedPurchaseCandidate other = new ParsedPurchaseCandidate(
                difference.equals("name") ? "另一款合成商品" : original.productName(),
                difference.equals("sku") ? "10kg*1袋" : original.sku(),
                difference.equals("price") ? 40D : original.price(), original.quantity(),
                difference.equals("unit") ? "g" : original.unit(),
                difference.equals("platform") ? "jd" : original.platform(),
                difference.equals("owner") ? "another-owner" : original.owner(),
                difference.equals("date") ? "2026-11-09" : original.purchaseDate(),
                original.shopName(), original.note(), original.sourceText(), original.confidence(),
                original.warnings(), original.normalization(), difference.equals("count") ? 2D : 1D, null);
        assertThat(enrich(other, "订单号：261108090450235800592").duplicate().status())
                .isEqualTo(ParsedDuplicatePreview.NONE);
    }

    /** 多件商品按件查询，避免整个订单被一个已保存商品覆盖。 */
    @Test
    void shouldCheckMultipleCandidatesSeparately() {
        repository.save(record("订单号：261108090450235800592"));
        ParsedPurchaseCandidate other = new ParsedPurchaseCandidate("合成测试洗衣液", "2L", 20D, 2D,
                "L", "pdd", "jtxw", "2026-11-08", null, null, null, 0.9D, List.of(), null, 1D);
        assertThat(service.enrich(List.of(candidate(), other), "订单号：261108090450235800592"))
                .extracting(item -> item.duplicate().status()).containsExactly("saved", "none");
    }

    /** 必需数量缺失时不以规格重量代替件数，也不执行数据库查询。 */
    @Test
    void shouldLeaveIncompleteCandidateUnchecked() {
        PurchaseRecordRepository mockRepository = mock(PurchaseRecordRepository.class);
        ParsedPurchaseCandidate incomplete = new ParsedPurchaseCandidate("大米", "5kg", 41.41D, 5D,
                "kg", "pdd", "jtxw", "2026-11-08", null, null, null, 0.9D, List.of());
        ParsedPurchaseCandidate result = new OrderImageDuplicatePreviewService(mockRepository, policy)
                .enrich(List.of(incomplete), "订单号：261108090450235800592").get(0);
        assertThat(result.duplicate().status()).isEqualTo("unavailable");
        verifyNoInteractions(mockRepository);
    }

    /** 数据库不可用时识别结果仍可用，页面不能显示成已核对或已上传。 */
    @Test
    void shouldReturnUncheckedPreviewWhenDatabaseUnavailable() {
        PurchaseRecordRepository mockRepository = mock(PurchaseRecordRepository.class);
        when(mockRepository.findOrderImageMatches(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("synthetic database failure"));
        ParsedPurchaseCandidate result = new OrderImageDuplicatePreviewService(mockRepository, policy)
                .enrich(List.of(candidate()), "订单号：261108090450235800592").get(0);
        assertThat(result.duplicate().status()).isEqualTo("unavailable");
        assertThat(result.productName()).isEqualTo(candidate().productName());
        assertThat(result.note()).doesNotContain("failure");
    }

    /** 天猫归入淘宝的别名差异不影响历史匹配。 */
    @Test
    void shouldSupportHistoricalTmallAlias() {
        repository.save(record("订单号：261108090450235800592"));
        jdbcTemplate.update("UPDATE purchase_records SET platform = 'tmall'");
        ParsedPurchaseCandidate original = candidate();
        ParsedPurchaseCandidate taobao = new ParsedPurchaseCandidate(original.productName(), original.sku(),
                original.price(), original.quantity(), "kg", "taobao", "JTXW", "2026-11-08", null,
                null, null, 0.9D, List.of(), null, 1D);
        assertThat(enrich(taobao, "订单号：261108090450235800592").duplicate().status()).isEqualTo("saved");
    }

    /** 不同文件名的同一订单仍可匹配；解析和 dryRun=false 均不新增购买、批次或复核记录。 */
    @Test
    void shouldEnrichActualParserResultWithoutDatabaseWrites() throws Exception {
        repository.save(record("订单号：261108090450235800592"));
        String rawText = "商品名称：合成测试大米\n规格：5kg*1袋\n×1\n实付：41.41元\n订单号：261108090450235800592";
        ParseOrderImageApplicationService parseService = new ParseOrderImageApplicationService(
                OrderImageRecognitionService.localOnly(path -> new OcrResult(rawText, null, List.of())),
                new OrderImageBase64Decoder(new ParseOrderImageModelProperties()), new OrderImageTextParser(),
                NormalizationPreviewService.withoutRules(), service, List.of(directory), "jtxw");
        Path image = Files.createFile(directory.resolve("renamed-order.jpg"));
        var result = parseService.parse(new ParseOrderImageCommand(image.toString(), null, null, null,
                "jtxw", "pdd", "2026-11-08", null, false));
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).duplicate().status()).isEqualTo("saved");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM purchase_records", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM raw_import_batches", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM review_items", Integer.class)).isZero();
    }

    /** 订单号出现在长截图末尾时保留到现有溯源字段，供后续重复上传核对。 */
    @Test
    void shouldPreserveOrderNumberBeyondOriginalSourceLimit() {
        ParsedPurchaseCandidate original = candidate();
        String rawText = "合成原文".repeat(150) + "\n订单号：261108090450235800592";
        ParsedPurchaseCandidate result = enrich(original, rawText);
        assertThat(result.sourceText()).hasSizeLessThanOrEqualTo(500).endsWith("订单号：261108090450235800592");
        repository.save(record(result.sourceText()));
        assertThat(enrich(original, rawText).duplicate().status()).isEqualTo("saved");
    }

    /** 多订单截图及物流编号不能提供唯一订单证据。 */
    @Test
    void shouldRejectAmbiguousOrUnlabelledOrderNumbers() {
        assertThat(policy.orderNumber("订单号：261108090450235800592\n订单号：261108090450235800593")).isNull();
        assertThat(policy.orderNumber("快递单号：261108090450235800592")).isNull();
        assertThat(policy.purchaseDay("2026-02-30")).isNull();
    }

    /** 创建当前测试使用的单件商品候选。 */
    private ParsedPurchaseCandidate candidate() {
        return new ParsedPurchaseCandidate("合成测试大米", "5kg*1袋", 41.41D, 5D, "kg", "pdd", "jtxw",
                "2026-11-08 23:20:19", "合成店铺", "【OCR解析】-【未归一化】", "合成原文".repeat(125),
                0.9D, List.of(), null, 1D, null);
    }

    /** 创建历史持久化明细，日期只保留到日以模拟表单保存行为。 */
    private PurchaseRecord record(String sourceText) {
        return new PurchaseRecord(null, null, "2026-11-08 00:00:00", "pdd", "jtxw", "合成测试大米",
                "大米", "5kg*1袋", "食品", "", 5D, "kg", 41.41D, 41.41D, 41.41D, null,
                "manual_record", 8.282D, "CNY", "include", false, "unique", "old-name.jpg",
                "合成店铺", null, sourceText, "2026-11-11 19:34:39");
    }

    /** 执行单个候选的只读查重。 */
    private ParsedPurchaseCandidate enrich(ParsedPurchaseCandidate candidate, String rawText) {
        return service.enrich(List.of(candidate), rawText).get(0);
    }
}
