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
}
