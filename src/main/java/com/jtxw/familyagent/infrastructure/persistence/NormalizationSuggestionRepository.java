package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.NormalizationSuggestion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 19:17:57
 * @Description: 商品归一化 LLM 建议仓储，负责建议审计记录的写入、查询和状态更新。
 */
@Repository
public class NormalizationSuggestionRepository {
    /**
     * normalization_suggestions 表访问组件。
     */
    private final JdbcTemplate jdbcTemplate;

    public NormalizationSuggestionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存商品归一化建议。
     *
     * @param suggestion 待保存的 LLM 建议
     * @return 新增建议 ID
     */
    public long save(NormalizationSuggestion suggestion) {
        String sql = """
                INSERT INTO normalization_suggestions(
                    batch_id, raw_product_name, sku, alias_key, action, suggested_normalized_name,
                    rejected_normalized_name, product_type, target_unit, unit_family, confidence,
                    review_required, reason, evidence_json, llm_provider, llm_model, prompt_version,
                    status, created_at, reviewed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, suggestion.batchId());
            ps.setObject(2, suggestion.rawProductName());
            ps.setObject(3, suggestion.sku());
            ps.setObject(4, suggestion.aliasKey());
            ps.setObject(5, suggestion.action());
            ps.setObject(6, suggestion.suggestedNormalizedName());
            ps.setObject(7, suggestion.rejectedNormalizedName());
            ps.setObject(8, suggestion.productType());
            ps.setObject(9, suggestion.targetUnit());
            ps.setObject(10, suggestion.unitFamily());
            ps.setObject(11, suggestion.confidence());
            ps.setObject(12, suggestion.reviewRequired() ? 1 : 0);
            ps.setObject(13, suggestion.reason());
            ps.setObject(14, suggestion.evidenceJson());
            ps.setObject(15, suggestion.llmProvider());
            ps.setObject(16, suggestion.llmModel());
            ps.setObject(17, suggestion.promptVersion());
            ps.setObject(18, suggestion.status());
            ps.setObject(19, suggestion.createdAt() == null ? ClockUtils.nowText() : suggestion.createdAt());
            ps.setObject(20, suggestion.reviewedAt());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("无法获取新建 normalization_suggestions ID");
        }
        return key.longValue();
    }

    /**
     * 查询指定批次的全部归一化建议。
     *
     * @param batchId 导入批次 ID
     * @return 建议列表
     */
    public List<NormalizationSuggestion> listByBatchId(long batchId) {
        return jdbcTemplate.query("""
                SELECT * FROM normalization_suggestions
                WHERE batch_id = ?
                ORDER BY id
                """, rowMapper(), batchId);
    }

    /**
     * 查询指定批次和状态的归一化建议。
     *
     * @param batchId 导入批次 ID
     * @param status  建议状态
     * @return 建议列表
     */
    public List<NormalizationSuggestion> listByBatchIdAndStatus(long batchId, String status) {
        return jdbcTemplate.query("""
                SELECT * FROM normalization_suggestions
                WHERE batch_id = ? AND status = ?
                ORDER BY id
                """, rowMapper(), batchId, status);
    }

    /**
     * 判断同一批次和 alias_key 是否已存在建议。
     *
     * @param batchId  导入批次 ID
     * @param aliasKey 商品清洗匹配键
     * @return 是否已存在建议
     */
    public boolean existsByBatchIdAndAliasKey(long batchId, String aliasKey) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM normalization_suggestions
                WHERE batch_id = ? AND alias_key = ?
                """, Integer.class, batchId, aliasKey);
        return count != null && count > 0;
    }

    /**
     * 判断同一批次和 alias_key 是否存在非 failed 建议。
     *
     * <p>failed 表示上次 LLM 调用或解析失败，允许后续自动重试；其他状态代表已经形成可审计结论，
     * forceReanalyze=false 时仍应跳过。</p>
     *
     * @param batchId  导入批次 ID
     * @param aliasKey 商品清洗匹配键
     * @return 是否存在非 failed 建议
     */
    public boolean existsNonFailedByBatchIdAndAliasKey(long batchId, String aliasKey) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM normalization_suggestions
                WHERE batch_id = ? AND alias_key = ? AND status <> 'failed'
                """, Integer.class, batchId, aliasKey);
        return count != null && count > 0;
    }

    /**
     * 判断同一批次和 alias_key 是否存在 failed 建议。
     *
     * @param batchId  导入批次 ID
     * @param aliasKey 商品清洗匹配键
     * @return 是否存在 failed 建议
     */
    public boolean existsFailedByBatchIdAndAliasKey(long batchId, String aliasKey) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM normalization_suggestions
                WHERE batch_id = ? AND alias_key = ? AND status = 'failed'
                """, Integer.class, batchId, aliasKey);
        return count != null && count > 0;
    }

    /**
     * 使用新分析结果覆盖同一批次、同一 alias_key 的最新 failed 建议。
     *
     * <p>用于网络超时等可重试失败场景，避免 failed suggestion 永久阻塞后续 analyze-normalization。</p>
     *
     * @param suggestion 新的 LLM 建议内容
     * @return 更新记录数，0 表示没有可覆盖的 failed 建议
     */
    public int replaceLatestFailed(NormalizationSuggestion suggestion) {
        return jdbcTemplate.update("""
                UPDATE normalization_suggestions
                SET raw_product_name = ?,
                    sku = ?,
                    action = ?,
                    suggested_normalized_name = ?,
                    rejected_normalized_name = ?,
                    product_type = ?,
                    target_unit = ?,
                    unit_family = ?,
                    confidence = ?,
                    review_required = ?,
                    reason = ?,
                    evidence_json = ?,
                    llm_provider = ?,
                    llm_model = ?,
                    prompt_version = ?,
                    status = ?,
                    created_at = ?,
                    reviewed_at = ?
                WHERE id = (
                    SELECT id FROM normalization_suggestions
                    WHERE batch_id = ? AND alias_key = ? AND status = 'failed'
                    ORDER BY id DESC
                    LIMIT 1
                )
                """,
                suggestion.rawProductName(),
                suggestion.sku(),
                suggestion.action(),
                suggestion.suggestedNormalizedName(),
                suggestion.rejectedNormalizedName(),
                suggestion.productType(),
                suggestion.targetUnit(),
                suggestion.unitFamily(),
                suggestion.confidence(),
                suggestion.reviewRequired() ? 1 : 0,
                suggestion.reason(),
                suggestion.evidenceJson(),
                suggestion.llmProvider(),
                suggestion.llmModel(),
                suggestion.promptVersion(),
                suggestion.status(),
                suggestion.createdAt() == null ? ClockUtils.nowText() : suggestion.createdAt(),
                suggestion.reviewedAt(),
                suggestion.batchId(),
                suggestion.aliasKey());
    }

    /**
     * 更新建议状态。
     *
     * @param id     建议 ID
     * @param status 新状态
     * @return 更新记录数
     */
    public int updateStatus(long id, String status) {
        return jdbcTemplate.update("""
                UPDATE normalization_suggestions
                SET status = ?, reviewed_at = ?
                WHERE id = ?
                """, status, ClockUtils.nowText(), id);
    }

    /**
     * 更新建议状态和原因。
     *
     * @param id     建议 ID
     * @param status 新状态
     * @param reason 新原因说明
     * @return 更新记录数
     */
    public int updateStatusAndReason(long id, String status, String reason) {
        return jdbcTemplate.update("""
                UPDATE normalization_suggestions
                SET status = ?, reason = ?, reviewed_at = ?
                WHERE id = ?
                """, status, reason, ClockUtils.nowText(), id);
    }

    /**
     * 统计指定批次和状态的建议数量。
     *
     * @param batchId 导入批次 ID
     * @param status  建议状态
     * @return 建议数量
     */
    public int countByBatchIdAndStatus(long batchId, String status) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM normalization_suggestions
                WHERE batch_id = ? AND status = ?
                """, Integer.class, batchId, status);
        return count == null ? 0 : count;
    }

    private RowMapper<NormalizationSuggestion> rowMapper() {
        return (rs, rowNum) -> new NormalizationSuggestion(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getString("raw_product_name"),
                rs.getString("sku"),
                rs.getString("alias_key"),
                rs.getString("action"),
                rs.getString("suggested_normalized_name"),
                rs.getString("rejected_normalized_name"),
                rs.getString("product_type"),
                rs.getString("target_unit"),
                rs.getString("unit_family"),
                rs.getDouble("confidence"),
                rs.getInt("review_required") == 1,
                rs.getString("reason"),
                rs.getString("evidence_json"),
                rs.getString("llm_provider"),
                rs.getString("llm_model"),
                rs.getString("prompt_version"),
                rs.getString("status"),
                rs.getString("created_at"),
                rs.getString("reviewed_at")
        );
    }
}
