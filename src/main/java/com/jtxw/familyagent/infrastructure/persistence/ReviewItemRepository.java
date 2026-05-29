package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.ReviewItem;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/15:58
 * @Description: 复核事项仓储，负责创建和查询待人工确认的异常记录。
 */
@Repository
public class ReviewItemRepository {
    private final JdbcTemplate jdbcTemplate;

    public ReviewItemRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 创建待人工复核项。
     *
     * @param recordId      关联购买记录 ID
     * @param reasonCode    复核原因编码
     * @param reasonMessage 复核原因说明
     */
    public void create(Long recordId, String reasonCode, String reasonMessage) {
        jdbcTemplate.update("INSERT INTO review_items(record_id, reason_code, reason_message, status, created_at) VALUES (?, ?, ?, ?, ?)",
                recordId, reasonCode, reasonMessage, "pending", ClockUtils.nowText());
    }

    /**
     * 查询所有待处理的复核项。
     *
     * @return pending 状态的复核项列表
     */
    public List<ReviewItem> listPending() {
        return jdbcTemplate.query("SELECT * FROM review_items WHERE status='pending' ORDER BY id", rowMapper());
    }

    /**
     * 查询所有待处理复核项及其关联订单摘要。
     *
     * <p>该查询用于人工复核界面，返回复核原因的同时带出商品、金额、单价、
     * owner 和来源文件等判断信息。</p>
     *
     * @return pending 状态的复核详情列表
     */
    public List<ReviewItemDetail> listPendingDetails() {
        return jdbcTemplate.query("""
                SELECT
                    ri.id AS review_id,
                    ri.record_id AS review_record_id,
                    ri.reason_code,
                    ri.reason_message,
                    ri.status AS review_status,
                    ri.review_decision,
                    ri.review_note,
                    ri.created_at AS review_created_at,
                    ri.resolved_at,
                    pr.batch_id,
                    pr.order_time,
                    pr.platform,
                    pr.owner,
                    pr.product_name,
                    pr.normalized_name,
                    pr.sku,
                    pr.category,
                    pr.sub_category,
                    pr.quantity,
                    pr.unit,
                    pr.total_amount,
                    pr.product_amount,
                    pr.paid_amount,
                    pr.shipping_fee,
                    pr.amount_source,
                    pr.unit_price,
                    pr.currency,
                    pr.decision,
                    pr.is_duplicate,
                    pr.dedupe_status,
                    pr.source_file,
                    pr.created_at AS record_created_at
                FROM review_items ri
                LEFT JOIN purchase_records pr ON pr.id = ri.record_id
                WHERE ri.status = 'pending'
                ORDER BY ri.id
                """, detailRowMapper());
    }

    /**
     * 根据 ID 查询复核项。
     *
     * @param id 复核项 ID
     * @return 复核项，不存在时为空
     */
    public Optional<ReviewItem> findById(long id) {
        List<ReviewItem> items = jdbcTemplate.query("SELECT * FROM review_items WHERE id = ?", rowMapper(), id);
        return items.stream().findFirst();
    }

    /**
     * 将待复核项标记为已处理。
     *
     * <p>该方法只更新 pending 状态的复核项，用于避免重复应用人工复核结果。</p>
     *
     * @param id             复核项 ID
     * @param reviewDecision 人工复核动作
     * @param reviewNote     人工复核备注
     * @return 更新记录数
     */
    public int resolve(long id, String reviewDecision, String reviewNote) {
        return jdbcTemplate.update("""
                UPDATE review_items
                SET status = ?, review_decision = ?, review_note = ?, resolved_at = ?
                WHERE id = ? AND status = 'pending'
                """, "resolved", reviewDecision, reviewNote, ClockUtils.nowText(), id);
    }

    /**
     * 统计当前待处理复核项数量。
     *
     * @return pending 状态复核项数量
     */
    public int countPending() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM review_items WHERE status='pending'", Integer.class);
        return count == null ? 0 : count;
    }

    private RowMapper<ReviewItem> rowMapper() {
        return (rs, rowNum) -> new ReviewItem(
                rs.getLong("id"), rs.getLong("record_id"), rs.getString("reason_code"),
                rs.getString("reason_message"), rs.getString("status"), rs.getString("review_decision"),
                rs.getString("review_note"), rs.getString("created_at"),
                rs.getString("resolved_at")
        );
    }

    private RowMapper<ReviewItemDetail> detailRowMapper() {
        return (rs, rowNum) -> new ReviewItemDetail(
                nullableLong(rs, "review_id"), nullableLong(rs, "review_record_id"), rs.getString("reason_code"),
                rs.getString("reason_message"), rs.getString("review_status"), rs.getString("review_decision"),
                rs.getString("review_note"), rs.getString("review_created_at"), rs.getString("resolved_at"),
                nullableLong(rs, "batch_id"), rs.getString("order_time"), rs.getString("platform"),
                rs.getString("owner"), rs.getString("product_name"), rs.getString("normalized_name"),
                rs.getString("sku"), rs.getString("category"), rs.getString("sub_category"),
                nullableDouble(rs, "quantity"), rs.getString("unit"), nullableDouble(rs, "total_amount"),
                nullableDouble(rs, "product_amount"), nullableDouble(rs, "paid_amount"),
                nullableDouble(rs, "shipping_fee"), rs.getString("amount_source"),
                nullableDouble(rs, "unit_price"), rs.getString("currency"), rs.getString("decision"),
                rs.getInt("is_duplicate") == 1, rs.getString("dedupe_status"),
                rs.getString("source_file"), rs.getString("record_created_at")
        );
    }

    private Long nullableLong(ResultSet rs, String columnName) throws SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? null : value;
    }

    private Double nullableDouble(ResultSet rs, String columnName) throws SQLException {
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : value;
    }
}
