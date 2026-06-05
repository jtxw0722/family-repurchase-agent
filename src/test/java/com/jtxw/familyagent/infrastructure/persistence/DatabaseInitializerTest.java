package com.jtxw.familyagent.infrastructure.persistence;

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
 * @Date: 2026/06/04
 * @Description: 数据库初始化兼容性测试，覆盖老库字段补齐场景。
 */
class DatabaseInitializerTest {
    @Test
    void shouldAddManualRecordSourceColumnsForLegacyPurchaseRecordsTable() throws Exception {
        Path dir = Path.of("target", "database-initializer-test");
        Files.createDirectories(dir);
        Path db = dir.resolve("legacy-purchase-records.sqlite");
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE purchase_records (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    batch_id INTEGER,
                    order_time TEXT,
                    platform TEXT,
                    owner TEXT,
                    product_name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL,
                    sku TEXT,
                    category TEXT,
                    sub_category TEXT,
                    quantity REAL,
                    unit TEXT,
                    total_amount REAL,
                    product_amount REAL,
                    paid_amount REAL,
                    shipping_fee REAL,
                    amount_source TEXT DEFAULT 'paid_amount',
                    unit_price REAL,
                    currency TEXT DEFAULT 'CNY',
                    decision TEXT DEFAULT 'include',
                    is_duplicate INTEGER DEFAULT 0,
                    dedupe_status TEXT DEFAULT 'unique',
                    source_file TEXT,
                    created_at TEXT NOT NULL
                )
                """);

        new DatabaseInitializer(jdbcTemplate).initialize();

        List<String> columns = jdbcTemplate.queryForList("PRAGMA table_info(purchase_records)")
                .stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
        assertThat(columns).contains("shop_name", "note", "source_text");
    }

    @Test
    void shouldAddProductAliasLearningSchemaForLegacyDatabase() throws Exception {
        Path dir = Path.of("target", "database-initializer-test");
        Files.createDirectories(dir);
        Path db = dir.resolve("legacy-product-aliases.sqlite");
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE product_aliases (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    alias TEXT NOT NULL UNIQUE,
                    normalized_name TEXT NOT NULL,
                    category TEXT,
                    created_at TEXT NOT NULL
                )
                """);

        new DatabaseInitializer(jdbcTemplate).initialize();

        List<String> aliasColumns = jdbcTemplate.queryForList("PRAGMA table_info(product_aliases)")
                .stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
        List<String> negativeAliasColumns = jdbcTemplate.queryForList("PRAGMA table_info(product_negative_aliases)")
                .stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
        assertThat(aliasColumns).contains("alias_key", "target_unit");
        assertThat(negativeAliasColumns).contains("alias", "alias_key", "rejected_normalized_name", "reason");
    }

    @Test
    void shouldAllowSameAliasWithDifferentAliasKeyInNewSchema() throws Exception {
        Path dir = Path.of("target", "database-initializer-test");
        Files.createDirectories(dir);
        Path db = dir.resolve("same-alias-different-sku.sqlite");
        Files.deleteIfExists(db);
        Files.deleteIfExists(Path.of(db + "-shm"));
        Files.deleteIfExists(Path.of(db + "-wal"));

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new DatabaseInitializer(jdbcTemplate).initialize();
        ProductAliasRepository repository = new ProductAliasRepository(jdbcTemplate);
        List<Object> aliasKeyNotNull = jdbcTemplate.queryForList("PRAGMA table_info(product_aliases)")
                .stream()
                .filter(row -> "alias_key".equals(row.get("name")))
                .map(row -> row.get("notnull"))
                .toList();

        repository.upsert("舒肤佳沐浴露", "shufujia720ml", "沐浴露", "L", "日用品");
        repository.upsert("舒肤佳沐浴露", "shufujia1l", "沐浴露", "L", "日用品");

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_aliases WHERE alias = ?",
                Integer.class, "舒肤佳沐浴露");
        Integer aliasKeyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT alias_key) FROM product_aliases WHERE alias = ?",
                Integer.class, "舒肤佳沐浴露");
        assertThat(aliasKeyNotNull).containsExactly(1);
        assertThat(count).isEqualTo(2);
        assertThat(aliasKeyCount).isEqualTo(2);
        assertThat(repository.findByAliasKey("shufujia720ml")).isPresent();
        assertThat(repository.findByAliasKey("shufujia1l")).isPresent();
    }
}
