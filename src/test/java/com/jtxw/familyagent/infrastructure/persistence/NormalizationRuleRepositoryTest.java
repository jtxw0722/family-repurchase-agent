package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.ProductRuleMatcher;
import com.jtxw.familyagent.domain.policy.UnitFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 16:50:03
 * @Description: 归一化规则仓储测试，验证 SQLite 规则加载、关键词拆分和动态样本统计口径
 */
class NormalizationRuleRepositoryTest {
    /**
     * 测试用数据库访问模板，每个测试使用独立 SQLite 文件。
     */
    private JdbcTemplate jdbcTemplate;
    /**
     * 被测归一化规则仓储。
     */
    private NormalizationRuleRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Path.of("target", "normalization-rule-repository-test");
        Files.createDirectories(dir);
        Path db = dir.resolve("normalization-rules.sqlite");
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        jdbcTemplate = new JdbcTemplate(dataSource);
        new DatabaseInitializer(jdbcTemplate).initialize();
        repository = new NormalizationRuleRepository(jdbcTemplate);
    }

    @Test
    void shouldLoadEnabledRulesFromSqliteWithKeywordsAndUnits() {
        List<ProductRule> rules = repository.listEnabledProductRules();

        ProductRule catLitter = rules.stream()
                .filter(rule -> "cat_litter".equals(rule.id()))
                .findFirst()
                .orElseThrow();
        assertThat(catLitter.normalizedName()).isEqualTo("猫砂");
        assertThat(catLitter.category()).isEqualTo("宠物用品");
        assertThat(catLitter.standardUnit()).isEqualTo("kg");
        assertThat(catLitter.unitFamily()).isEqualTo(UnitFamily.WEIGHT);
        assertThat(catLitter.includeKeywords()).contains("猫砂", "豆腐砂", "膨润土");
        assertThat(catLitter.excludeKeywords()).contains("猫砂盆", "猫砂铲");
        assertThat(catLitter.priority()).isEqualTo(100);
    }

    @Test
    void shouldMatchIncludeAndExcludeKeywordsThroughSqliteProvider() {
        ProductRuleMatcher matcher = new ProductRuleMatcher(new SqliteProductRuleProvider(repository));

        assertThat(matcher.match("猫砂").normalizedName()).isEqualTo("猫砂");
        assertThat(matcher.match("豆腐砂").normalizedName()).isEqualTo("猫砂");
        assertThat(matcher.match("膨润土").normalizedName()).isEqualTo("猫砂");
        assertThat(matcher.match("猫粮").normalizedName()).isEqualTo("猫粮");
        assertThat(matcher.match("纸巾").normalizedName()).isEqualTo("纸巾");
        assertThat(matcher.match("洗衣液").normalizedName()).isEqualTo("洗衣液");
        assertThat(matcher.match("猫砂盆大号防外溅").matched()).isFalse();
        assertThat(matcher.match("猫砂铲").matched()).isFalse();
        assertThat(matcher.match("猫粮勺").matched()).isFalse();
        assertThat(matcher.match("储粮桶").matched()).isFalse();
        assertThat(matcher.match("纸巾盒").matched()).isFalse();
        assertThat(matcher.match("洗衣液瓶").matched()).isFalse();
    }

    @Test
    void shouldReturnLibraryItemsWithDynamicSampleCount() {
        insertPurchaseRecord(1L, "猫砂", "include", 0, "unique", 6.8D, 68.0D);
        insertPurchaseRecord(2L, "猫砂", "exclude", 0, "unique", 6.9D, 69.0D);
        insertPurchaseRecord(3L, "猫砂", "include", 1, "duplicate", 7.0D, 70.0D);
        insertPurchaseRecord(4L, "猫砂", "include", 0, "unique", null, 71.0D);

        List<NormalizationLibraryItem> items = repository.listLibraryItems();

        NormalizationLibraryItem catLitter = items.stream()
                .filter(item -> "猫砂".equals(item.normalizedName()))
                .findFirst()
                .orElseThrow();
        assertThat(catLitter.ruleCode()).isEqualTo("cat_litter");
        assertThat(catLitter.category()).isEqualTo("宠物用品");
        assertThat(catLitter.standardUnit()).isEqualTo("kg");
        assertThat(catLitter.unitFamily()).isEqualTo("weight");
        assertThat(catLitter.keywords()).contains("猫砂", "豆腐砂");
        assertThat(catLitter.excludeKeywords()).contains("猫砂盆", "猫砂铲");
        assertThat(catLitter.sampleCount()).isEqualTo(1);
        assertThat(catLitter.enabled()).isTrue();
        assertThat(catLitter.source()).isEqualTo("system");
    }

    @Test
    void shouldSyncKeywordsByMatchTypeSnapshot() {
        NormalizationRuleRepository.NormalizationRuleRow catFood = repository.findRuleByCode("cat_food")
                .orElseThrow();

        repository.syncKeywords(catFood.id(), "exclude", List.of("猫粮勺", "冻干"), 90, "manual");

        NormalizationLibraryItem updatedItem = repository.listLibraryItems().stream()
                .filter(item -> "cat_food".equals(item.ruleCode()))
                .findFirst()
                .orElseThrow();
        assertThat(updatedItem.excludeKeywords()).containsExactly("猫粮勺", "冻干");
        assertThat(updatedItem.keywords()).contains("猫粮", "幼猫粮");

        List<NormalizationRuleRepository.NormalizationRuleKeywordRow> excludeRows =
                repository.listKeywords(catFood.id(), "exclude");
        assertThat(excludeRows).anyMatch(keyword -> "储粮桶".equals(keyword.keyword()) && !keyword.enabled());
        assertThat(excludeRows).anyMatch(keyword -> "冻干".equals(keyword.keyword()) && keyword.enabled());

        repository.syncKeywords(catFood.id(), "exclude", List.of("猫粮勺", "储粮桶"), 90, "manual");

        NormalizationLibraryItem restoredItem = repository.listLibraryItems().stream()
                .filter(item -> "cat_food".equals(item.ruleCode()))
                .findFirst()
                .orElseThrow();
        assertThat(restoredItem.excludeKeywords()).containsExactly("猫粮勺", "储粮桶");
        assertThat(restoredItem.excludeKeywords()).doesNotContain("冻干");
        assertThat(repository.listKeywords(catFood.id(), "exclude"))
                .anyMatch(keyword -> "储粮桶".equals(keyword.keyword()) && keyword.enabled());
    }

    /**
     * 插入购买记录样本，用于验证名称库 sampleCount 与价格基准线有效样本口径一致。
     *
     * @param id             购买记录 ID
     * @param normalizedName 归一化商品名称
     * @param decision       统计决策
     * @param duplicate      是否重复，1 表示重复
     * @param dedupeStatus   去重状态
     * @param unitPrice      单价，允许为空
     * @param totalAmount    统计金额，单位为元
     */
    private void insertPurchaseRecord(long id,
                                      String normalizedName,
                                      String decision,
                                      int duplicate,
                                      String dedupeStatus,
                                      Double unitPrice,
                                      Double totalAmount) {
        jdbcTemplate.update("""
                INSERT INTO purchase_records(id, product_name, normalized_name, quantity, unit, total_amount,
                    unit_price, decision, is_duplicate, dedupe_status, created_at)
                VALUES (?, ?, ?, 10, 'kg', ?, ?, ?, ?, ?, ?)
                """, id, normalizedName, normalizedName, totalAmount, unitPrice, decision, duplicate,
                dedupeStatus, ClockUtils.nowText());
    }
}
