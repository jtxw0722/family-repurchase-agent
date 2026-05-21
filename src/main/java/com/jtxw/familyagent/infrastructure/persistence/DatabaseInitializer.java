package com.jtxw.familyagent.infrastructure.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/14:22
 * @Description: 数据库初始化组件，负责创建运行目录并执行 SQLite 表结构脚本。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    /**
     * 初始化本地运行目录和 SQLite 表结构。
     *
     * <p>该方法会在应用启动时自动执行，也会被 CLI 和应用服务显式调用，
     * 确保 REST Tool API 启动后即可直接使用。</p>
     */
    public synchronized void initialize() {
        try {
            ensureRuntimeDirectories();

            ClassPathResource resource = new ClassPathResource("db/schema.sql");
            String sql = resource.getContentAsString(StandardCharsets.UTF_8);
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isBlank()) {
                    jdbcTemplate.execute(trimmed);
                }
            }
            ensurePurchaseRecordAmountColumns();
            ensureReviewItemColumns();
        } catch (IOException e) {
            throw new IllegalStateException("初始化数据库失败", e);
        }
    }

    private void ensurePurchaseRecordAmountColumns() {
        List<String> columns = jdbcTemplate.queryForList("PRAGMA table_info(purchase_records)")
                .stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
        if (!columns.contains("product_amount")) {
            jdbcTemplate.execute("ALTER TABLE purchase_records ADD COLUMN product_amount REAL");
        }
        if (!columns.contains("paid_amount")) {
            jdbcTemplate.execute("ALTER TABLE purchase_records ADD COLUMN paid_amount REAL");
        }
        if (!columns.contains("shipping_fee")) {
            jdbcTemplate.execute("ALTER TABLE purchase_records ADD COLUMN shipping_fee REAL");
        }
        if (!columns.contains("amount_source")) {
            jdbcTemplate.execute("ALTER TABLE purchase_records ADD COLUMN amount_source TEXT DEFAULT 'paid_amount'");
        }
        jdbcTemplate.update("UPDATE purchase_records SET product_amount = total_amount WHERE product_amount IS NULL AND total_amount IS NOT NULL");
        jdbcTemplate.update("UPDATE purchase_records SET paid_amount = total_amount WHERE paid_amount IS NULL AND total_amount IS NOT NULL");
        jdbcTemplate.update("UPDATE purchase_records SET amount_source = 'paid_amount' WHERE amount_source IS NULL OR amount_source = ''");
    }

    private void ensureReviewItemColumns() {
        List<String> columns = jdbcTemplate.queryForList("PRAGMA table_info(review_items)")
                .stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
        if (!columns.contains("review_decision")) {
            jdbcTemplate.execute("ALTER TABLE review_items ADD COLUMN review_decision TEXT");
        }
        if (!columns.contains("review_note")) {
            jdbcTemplate.execute("ALTER TABLE review_items ADD COLUMN review_note TEXT");
        }
    }

    private void ensureRuntimeDirectories() throws IOException {
        Files.createDirectories(Path.of("data"));
        Files.createDirectories(Path.of("data", "inbox"));
        Files.createDirectories(Path.of("reports"));
    }
}
