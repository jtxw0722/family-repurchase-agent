package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.common.ClockUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/15:08
 * @Description: 导入批次仓储，负责记录每次文件导入的来源和统计信息。
 */
@Repository
public class ImportBatchRepository {
    private final JdbcTemplate jdbcTemplate;

    public ImportBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long create(String sourceFile) {
        jdbcTemplate.update("INSERT INTO raw_import_batches(source_file, status, created_at) VALUES (?, ?, ?)",
                sourceFile, "running", ClockUtils.nowText());
        return jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public void complete(long batchId, int totalCount, int importedCount, int reviewCount) {
        jdbcTemplate.update("UPDATE raw_import_batches SET status=?, total_count=?, imported_count=?, review_count=? WHERE id=?",
                "completed", totalCount, importedCount, reviewCount, batchId);
    }
}
