package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.common.ClockUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:59:16
 * @Description: 导入批次仓储，负责记录每次文件导入的来源和导入阶段统计信息。
 */
@Repository
public class ImportBatchRepository {
    private final JdbcTemplate jdbcTemplate;

    public ImportBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 创建文件导入批次记录。
     *
     * @param sourceFile 来源文件路径
     * @return 导入批次 ID
     */
    public long create(String sourceFile) {
        jdbcTemplate.update("INSERT INTO raw_import_batches(source_file, status, created_at) VALUES (?, ?, ?)",
                sourceFile, "running", ClockUtils.nowText());
        return jdbcTemplate.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    /**
     * 将导入批次标记为完成并写入导入阶段统计结果。
     *
     * <p>raw_import_batches.review_count 只表示 import-file 或 record-purchase 导入阶段直接创建的复核数；
     * 后续新增的 PRODUCT_NAME_NORMALIZATION_REVIEW 不会回写该字段，避免混淆批次导入结果和后处理结果。</p>
     *
     * @param batchId       导入批次 ID
     * @param totalCount    文件总记录数
     * @param importedCount 成功导入记录数
     * @param reviewCount   导入阶段直接创建的待复核记录数
     */
    public void complete(long batchId, int totalCount, int importedCount, int reviewCount) {
        jdbcTemplate.update("UPDATE raw_import_batches SET status=?, total_count=?, imported_count=?, review_count=? WHERE id=?",
                "completed", totalCount, importedCount, reviewCount, batchId);
    }
}
