package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.ProductAliasRepository;
import com.jtxw.familyagent.infrastructure.persistence.ProductNegativeAliasRepository;
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
 * @Date: 2026/06/05
 * @Description: 学习型商品归一化组件测试，覆盖正向别名、负向别名和低置信兜底复核。
 */
class LearningProductNameNormalizerTest {
    @Test
    void shouldForceLegacyFallbackToReview() throws Exception {
        Fixture fixture = fixture("legacy-fallback.sqlite");

        ProductNameNormalizationResult result = fixture.normalizer().normalize("舒肤佳沐浴露清香型720ml", "暂无");

        assertThat(result.matchedRule()).isEqualTo("legacy_fallback");
        assertThat(result.confidence()).isEqualTo(0.5D);
        assertThat(result.needReview()).isTrue();
    }

    @Test
    void shouldUsePositiveProductAliasBeforeFallback() throws Exception {
        Fixture fixture = fixture("positive-alias.sqlite");
        String productName = "舒肤佳沐浴露清香型720ml";
        String sku = "家庭装";
        fixture.productAliasRepository().upsert(productName, fixture.cleaner().aliasKey(productName, sku),
                "沐浴露", "L", "日用品");

        ProductNameNormalizationResult result = fixture.normalizer().normalize(productName, sku);

        assertThat(result.normalizedName()).isEqualTo("沐浴露");
        assertThat(result.targetUnit()).isEqualTo("L");
        assertThat(result.matchedRule()).isEqualTo("product_alias");
        assertThat(result.needReview()).isFalse();
    }

    @Test
    void shouldBlockNegativeProductAliasBeforeRules() throws Exception {
        Fixture fixture = fixture("negative-alias.sqlite");
        String productName = "猫砂盆超大号防外溅";
        fixture.productNegativeAliasRepository().upsert(productName, fixture.cleaner().aliasKey(productName, ""),
                "猫砂", "人工确认不是猫砂耗材");

        ProductNameNormalizationResult result = fixture.normalizer().normalize(productName, "");

        assertThat(result.normalizedName()).isNotEqualTo("猫砂");
        assertThat(result.matchedRule()).isEqualTo("product_negative_alias");
        assertThat(result.needReview()).isFalse();
    }

    private Fixture fixture(String dbName) throws Exception {
        Path dir = Path.of("target", "learning-normalizer-test");
        Files.createDirectories(dir);
        Path db = dir.resolve(dbName);
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new DatabaseInitializer(jdbcTemplate).initialize();
        ProductTitleCleaner cleaner = new ProductTitleCleaner();
        ProductAliasRepository productAliasRepository = new ProductAliasRepository(jdbcTemplate);
        ProductNegativeAliasRepository productNegativeAliasRepository = new ProductNegativeAliasRepository(jdbcTemplate);
        ProductRule rule = new ProductRule("cat_litter", "猫砂", 100,
                List.of("猫砂"), List.of(), "kg", UnitFamily.WEIGHT);
        ProductNameNormalizer delegate = new ProductNameNormalizer(
                new ProductNormalizer(new ProductRuleMatcher(new ProductRuleProperties(List.of(rule)))), List.of());
        LearningProductNameNormalizer normalizer = new LearningProductNameNormalizer(
                cleaner, productAliasRepository, productNegativeAliasRepository, delegate);
        return new Fixture(cleaner, productAliasRepository, productNegativeAliasRepository, normalizer);
    }

    private record Fixture(ProductTitleCleaner cleaner,
                           ProductAliasRepository productAliasRepository,
                           ProductNegativeAliasRepository productNegativeAliasRepository,
                           LearningProductNameNormalizer normalizer) {
    }
}
