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
 * @Date: 2026/06/14 17:05:00
 * @Description: 数据库初始化组件，负责创建运行目录并按顺序执行 SQLite 表结构脚本和种子数据脚本。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseInitializer implements ApplicationRunner {
    /**
     * SQLite 表结构脚本路径，只允许包含 DDL 和索引定义。
     */
    private static final String SCHEMA_SQL_PATH = "db/schema.sql";
    /**
     * SQLite 默认数据脚本路径，只允许包含 DML 种子数据。
     */
    private static final String DATA_SQL_PATH = "db/data.sql";

    /**
     * SQLite 访问模板，用于执行初始化 SQL 和兼容性补齐语句。
     */
    private final JdbcTemplate jdbcTemplate;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    /**
     * 初始化本地运行目录、SQLite 表结构和默认种子数据。
     *
     * <p>该方法会在应用启动时自动执行，也会被应用服务显式调用，
     * 确保 REST Tool API 启动后即可直接使用。</p>
     */
    public synchronized void initialize() {
        try {
            ensureRuntimeDirectories();

            executeSqlResource(SCHEMA_SQL_PATH);
            ensurePurchaseRecordColumns();
            ensureReviewItemColumns();
            ensureNormalizationLlmTaskTable();
            executeSqlResource(DATA_SQL_PATH);
        } catch (IOException e) {
            throw new IllegalStateException("初始化数据库失败", e);
        }
    }

    /**
     * 执行 classpath 下的 SQL 初始化脚本。
     *
     * <p>当前项目初始化脚本使用分号分隔独立语句，不支持存储过程或触发器内嵌分号。</p>
     *
     * @param resourcePath SQL 资源路径，不允许为空
     * @throws IOException SQL 资源读取失败时抛出
     */
    private void executeSqlResource(String resourcePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        String sql = resource.getContentAsString(StandardCharsets.UTF_8);
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isBlank()) {
                jdbcTemplate.execute(trimmed);
            }
        }
    }

    private void ensurePurchaseRecordColumns() {
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
        if (!columns.contains("shop_name")) {
            jdbcTemplate.execute("ALTER TABLE purchase_records ADD COLUMN shop_name TEXT");
        }
        if (!columns.contains("note")) {
            jdbcTemplate.execute("ALTER TABLE purchase_records ADD COLUMN note TEXT");
        }
        if (!columns.contains("source_text")) {
            jdbcTemplate.execute("ALTER TABLE purchase_records ADD COLUMN source_text TEXT");
        }
        if (!columns.contains("normalization_rule")) {
            jdbcTemplate.execute("ALTER TABLE purchase_records ADD COLUMN normalization_rule TEXT");
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

    /**
     * 确保归一化 LLM 通用异步任务表存在。
     *
     * <p>该表承载规则维护建议任务；初始化只做幂等创建和索引补齐，
     * 不删除用户已有历史遗留表，避免破坏已有 SQLite 数据。</p>
     */
    private void ensureNormalizationLlmTaskTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS normalization_llm_tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    batch_id INTEGER,
                    owner TEXT,
                    full_scan INTEGER DEFAULT 0,
                    apply_changes INTEGER DEFAULT 0,
                    candidate_mode TEXT,
                    limit_count INTEGER,
                    candidate_count INTEGER DEFAULT 0,
                    analyzed_count INTEGER DEFAULT 0,
                    suggested_operation_count INTEGER DEFAULT 0,
                    applied_count INTEGER DEFAULT 0,
                    skipped_count INTEGER DEFAULT 0,
                    request_json TEXT,
                    result_json TEXT,
                    warnings_json TEXT,
                    error_message TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    finished_at TEXT
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_normalization_llm_tasks_status ON normalization_llm_tasks(status)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_normalization_llm_tasks_type ON normalization_llm_tasks(task_type)");
    }
}
