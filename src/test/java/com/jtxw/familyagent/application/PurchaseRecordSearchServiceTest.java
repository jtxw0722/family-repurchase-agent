package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.query.SearchPurchaseRecordsQuery;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/12 13:09:27
 * @Description: 原始购买记录检索应用服务测试，覆盖关键词、owner、limit 和空结果等只读查询场景。
 */
class PurchaseRecordSearchServiceTest {
    @Test
    void searchShouldMatchProductName() throws Exception {
        Fixture fixture = fixture("match-product-name.sqlite");
        fixture.save(record("2026-05-21 10:30:00", "jtxw", "名创优品猫砂", "混合 40kg",
                "宠物用品", "清洁用品", "宠物用品", 40D));

        SearchPurchaseRecordsResult result = fixture.service.search(query("猫砂", null, 20));

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.records()).extracting(SearchPurchaseRecordsResult.Item::productName)
                .containsExactly("名创优品猫砂");
    }

    @Test
    void searchShouldMatchSku() throws Exception {
        Fixture fixture = fixture("match-sku.sqlite");
        fixture.save(record("2026-05-21 10:30:00", "jtxw", "宠物用品套装", "猫砂 40kg",
                "宠物用品", "清洁用品", "宠物用品", 40D));

        SearchPurchaseRecordsResult result = fixture.service.search(query("40kg", null, 20));

        assertThat(result.records()).extracting(SearchPurchaseRecordsResult.Item::sku)
                .containsExactly("猫砂 40kg");
    }

    @Test
    void searchShouldMatchCategoryAndSubCategory() throws Exception {
        Fixture fixture = fixture("match-category.sqlite");
        fixture.save(record("2026-05-21 10:30:00", "jtxw", "通用商品 A", "默认",
                "宠物用品", "猫砂", "其他", 1D));
        fixture.save(record("2026-05-20 10:30:00", "jtxw", "通用商品 B", "默认",
                "日用品", "纸品", "其他", 1D));

        SearchPurchaseRecordsResult categoryResult = fixture.service.search(query("宠物用品", null, 20));
        SearchPurchaseRecordsResult subCategoryResult = fixture.service.search(query("纸品", null, 20));

        assertThat(categoryResult.records()).extracting(SearchPurchaseRecordsResult.Item::category)
                .containsExactly("宠物用品");
        assertThat(subCategoryResult.records()).extracting(SearchPurchaseRecordsResult.Item::subCategory)
                .containsExactly("纸品");
    }

    @Test
    void searchShouldMatchNormalizedName() throws Exception {
        Fixture fixture = fixture("match-normalized-name.sqlite");
        fixture.save(record("2026-05-21 10:30:00", "jtxw", "进口砂", "默认",
                "宠物用品", "清洁用品", "猫砂", 10D));

        SearchPurchaseRecordsResult result = fixture.service.search(query("猫砂", null, 20));

        assertThat(result.records()).extracting(SearchPurchaseRecordsResult.Item::normalizedName)
                .containsExactly("猫砂");
    }

    @Test
    void searchShouldReturnFamilyRawRecordScopeWhenOwnerOmitted() throws Exception {
        Fixture fixture = fixture("owner-omitted.sqlite");
        fixture.save(record("2026-05-21 10:30:00", "jtxw", "猫砂 A", "默认",
                "宠物用品", "猫砂", "猫砂", 10D));
        fixture.save(record("2026-05-20 10:30:00", "lj", "猫砂 B", "默认",
                "宠物用品", "猫砂", "猫砂", 10D));

        SearchPurchaseRecordsResult result = fixture.service.search(query("猫砂", null, 20));

        assertThat(result.scope()).isEqualTo("FAMILY");
        assertThat(result.owner()).isNull();
        assertThat(result.records()).extracting(SearchPurchaseRecordsResult.Item::owner)
                .containsExactly("jtxw", "lj");
    }

    @Test
    void searchShouldTreatOwnerAsRawRecordFilterWhenProvided() throws Exception {
        Fixture fixture = fixture("owner-provided.sqlite");
        fixture.save(record("2026-05-21 10:30:00", "jtxw", "猫砂 A", "默认",
                "宠物用品", "猫砂", "猫砂", 10D));
        fixture.save(record("2026-05-20 10:30:00", "lj", "猫砂 B", "默认",
                "宠物用品", "猫砂", "猫砂", 10D));

        SearchPurchaseRecordsResult result = fixture.service.search(
                new SearchPurchaseRecordsQuery("猫砂", " jtxw ", 20, null, null)
        );

        assertThat(result.scope()).isEqualTo("OWNER");
        assertThat(result.owner()).isEqualTo("jtxw");
        assertThat(result.records()).extracting(SearchPurchaseRecordsResult.Item::owner)
                .containsExactly("jtxw");
    }

    @Test
    void searchShouldUseDefaultLimitWhenLimitOmitted() throws Exception {
        Fixture fixture = fixture("default-limit.sqlite");
        seedRecords(fixture, 21);

        SearchPurchaseRecordsResult result = fixture.service.search(
                new SearchPurchaseRecordsQuery("默认限制猫砂", null, null, null, null)
        );

        assertThat(result.matchedCount()).isEqualTo(21);
        assertThat(result.returnedCount()).isEqualTo(20);
        assertThat(result.records()).hasSize(20);
    }

    @Test
    void searchShouldCapLimitAtMaxValue() throws Exception {
        Fixture fixture = fixture("max-limit.sqlite");
        seedRecords(fixture, 51);

        SearchPurchaseRecordsResult result = fixture.service.search(query("默认限制猫砂", null, 100));

        assertThat(result.matchedCount()).isEqualTo(51);
        assertThat(result.returnedCount()).isEqualTo(50);
        assertThat(result.records()).hasSize(50);
    }

    @Test
    void searchShouldRejectBlankKeyword() throws Exception {
        Fixture fixture = fixture("blank-keyword.sqlite");

        assertThatThrownBy(() -> fixture.service.search(query("   ", null, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyword 不能为空");
    }

    @Test
    void searchShouldReturnEmptyRecordsWhenNoMatch() throws Exception {
        Fixture fixture = fixture("no-match.sqlite");
        fixture.save(record("2026-05-21 10:30:00", "jtxw", "洗衣液", "2L",
                "日用品", "洗护", "洗衣液", 2D));

        SearchPurchaseRecordsResult result = fixture.service.search(query("猫砂", null, 20));

        assertThat(result.matchedCount()).isZero();
        assertThat(result.returnedCount()).isZero();
        assertThat(result.records()).isEmpty();
    }

    @Test
    void searchShouldFilterByOrderTimeRange() throws Exception {
        Fixture fixture = fixture("date-range.sqlite");
        fixture.save(record("2026-05-21 10:30:00", "jtxw", "猫砂 A", "默认",
                "宠物用品", "猫砂", "猫砂", 10D));
        fixture.save(record("2026-06-01 10:30:00", "jtxw", "猫砂 B", "默认",
                "宠物用品", "猫砂", "猫砂", 10D));

        SearchPurchaseRecordsResult result = fixture.service.search(
                new SearchPurchaseRecordsQuery("猫砂", null, 20, "2026-06-01", "2026-06-01")
        );

        assertThat(result.records()).extracting(SearchPurchaseRecordsResult.Item::productName)
                .containsExactly("猫砂 B");
    }

    @Test
    void searchShouldTreatPercentAsLiteralKeyword() throws Exception {
        Fixture fixture = fixture("literal-percent.sqlite");
        fixture.save(record("2026-05-21 10:30:00", "jtxw", "猫砂 A", "默认",
                "宠物用品", "猫砂", "猫砂", 10D));
        fixture.save(record("2026-05-20 10:30:00", "jtxw", "洗衣液 B", "默认",
                "日用品", "洗护", "洗衣液", 2D));
        fixture.save(record("2026-05-19 10:30:00", "jtxw", "百分百%棉柔巾", "默认",
                "日用品", "纸品", "棉柔巾", 1D));

        SearchPurchaseRecordsResult result = fixture.service.search(query("%", null, 20));

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.records()).extracting(SearchPurchaseRecordsResult.Item::productName)
                .containsExactly("百分百%棉柔巾");
    }

    @Test
    void searchShouldTreatUnderscoreAsLiteralKeyword() throws Exception {
        Fixture fixture = fixture("literal-underscore.sqlite");
        fixture.save(record("2026-05-21 10:30:00", "jtxw", "猫砂 A", "默认",
                "宠物用品", "猫砂", "猫砂", 10D));
        fixture.save(record("2026-05-20 10:30:00", "jtxw", "洗衣液 B", "默认",
                "日用品", "洗护", "洗衣液", 2D));
        fixture.save(record("2026-05-19 10:30:00", "jtxw", "测试_商品", "默认",
                "其他", "测试", "测试商品", 1D));

        SearchPurchaseRecordsResult result = fixture.service.search(query("_", null, 20));

        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.records()).extracting(SearchPurchaseRecordsResult.Item::productName)
                .containsExactly("测试_商品");
    }

    private SearchPurchaseRecordsQuery query(String keyword, String owner, Integer limit) {
        return new SearchPurchaseRecordsQuery(keyword, owner, limit, null, null);
    }

    private void seedRecords(Fixture fixture, int count) {
        for (int i = 0; i < count; i++) {
            fixture.save(record("2026-05-" + String.format("%02d", (i % 28) + 1) + " 10:30:00",
                    "jtxw", "默认限制猫砂 " + i, "默认", "宠物用品", "猫砂", "猫砂", 10D));
        }
    }

    private PurchaseRecord record(String orderTime,
                                  String owner,
                                  String productName,
                                  String sku,
                                  String category,
                                  String subCategory,
                                  String normalizedName,
                                  Double quantity) {
        return new PurchaseRecord(
                null, 1L, orderTime, "taobao", owner, productName, normalizedName, sku,
                category, subCategory, quantity, "kg", 119.3D, 119.3D, 119.3D, null,
                "paid_amount", 11.93D, "CNY", "include", false, "unique",
                "test.csv", null, null, null, "2026-06-12 13:09:27"
        );
    }

    private Fixture fixture(String dbName) throws Exception {
        Path dir = Path.of("target", "purchase-record-search-service-test");
        Files.createDirectories(dir);
        Path db = dir.resolve(dbName);
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new DatabaseInitializer(jdbcTemplate).initialize();
        PurchaseRecordRepository purchaseRecordRepository = new PurchaseRecordRepository(jdbcTemplate);
        return new Fixture(
                new PurchaseRecordSearchService(purchaseRecordRepository),
                purchaseRecordRepository
        );
    }

    private record Fixture(PurchaseRecordSearchService service, PurchaseRecordRepository repository) {
        void save(PurchaseRecord record) {
            repository.save(record);
        }
    }
}
