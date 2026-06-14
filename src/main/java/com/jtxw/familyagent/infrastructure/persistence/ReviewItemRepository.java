package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.application.query.ReviewItemQuery;
import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.ReviewItem;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:27:12
 * @Description: 复核事项仓储，负责创建和查询待人工确认的异常记录。
 */
@Repository
public class ReviewItemRepository {
    /**
     * 规则回填可以自动解决的商品归一化复核原因。
     */
    private static final List<String> RULE_APPLY_RESOLVABLE_REASONS = List.of(
            "PRODUCT_NAME_NORMALIZATION_REVIEW",
            "QUANTITY_UNIT_PARSE_REVIEW"
    );
    /**
     * 规则回填必须继续阻断的风险型 pending 复核原因。
     */
    private static final List<String> RULE_APPLY_BLOCKING_PENDING_REASONS = List.of(
            "DUPLICATE_ORDER",
            "ZERO_PAYMENT",
            "PAYMENT_ADJUSTMENT",
            "PRICE_OUT_OF_BASELINE_RANGE",
            "UNIT_MISMATCH_UNPARSED"
    );

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
     * 根据查询条件筛选复核项及其关联订单摘要。
     *
     * <p>支持按状态、批次、归属人、复核原因码、统计决策和来源文件筛选，
     * 并使用分页参数控制返回数量。所有用户输入均通过参数绑定，不拼接 SQL。</p>
     *
     * @param query 查询条件
     * @return 符合条件的复核详情列表
     */
    public List<ReviewItemDetail> listDetails(ReviewItemQuery query) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
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
                WHERE 1 = 1
                """);

        if (query.status() != null && !query.status().isBlank()) {
            sql.append(" AND ri.status = ?");
            params.add(query.status().trim());
        }
        if (query.batchId() != null) {
            sql.append(" AND pr.batch_id = ?");
            params.add(query.batchId());
        }
        if (query.owner() != null && !query.owner().isBlank()) {
            sql.append(" AND pr.owner = ?");
            params.add(query.owner().trim());
        }
        if (query.reasonCode() != null && !query.reasonCode().isBlank()) {
            sql.append(" AND ri.reason_code = ?");
            params.add(query.reasonCode().trim());
        }
        if (query.decision() != null && !query.decision().isBlank()) {
            sql.append(" AND pr.decision = ?");
            params.add(query.decision().trim());
        }
        if (query.sourceFile() != null && !query.sourceFile().isBlank()) {
            sql.append(" AND pr.source_file LIKE ?");
            params.add("%" + query.sourceFile().trim() + "%");
        }

        sql.append(" ORDER BY ri.created_at ASC, ri.id ASC LIMIT ? OFFSET ?");
        params.add(query.size());
        params.add((query.page() - 1) * query.size());

        return jdbcTemplate.query(sql.toString(), detailRowMapper(), params.toArray());
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
     * 判断同一购买记录上是否仍存在其他阻塞型复核项。
     *
     * <p>阻塞条件包括仍待处理的复核项，以及已经被人工确认为 exclude 的风险复核项。
     * 这样确认商品归一化时不能把同一记录从 exclude 覆盖回 include，避免绕过已确认风险。</p>
     *
     * @param recordId        购买记录 ID
     * @param currentReviewId 当前正在处理的复核项 ID
     * @return 是否存在其他阻塞型复核项
     */
    public boolean existsOtherBlockingReviewByRecordId(long recordId, long currentReviewId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM review_items
                WHERE record_id = ?
                  AND id <> ?
                  AND (status = 'pending' OR review_decision = 'exclude')
                """, Integer.class, recordId, currentReviewId);
        return count != null && count > 0;
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

    /**
     * 判断指定购买记录是否已有同类待复核项。
     *
     * @param recordId   购买记录 ID
     * @param reasonCode 复核原因编码
     * @return 是否已存在 pending 状态同类复核项
     */
    public boolean existsPendingByRecordIdAndReasonCode(long recordId, String reasonCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM review_items
                WHERE record_id = ? AND reason_code = ? AND status = 'pending'
                """, Integer.class, recordId, reasonCode);
        return count != null && count > 0;
    }

    /**
     * 判断指定购买记录是否存在任意待处理复核项。
     *
     * @param recordId 购买记录 ID
     * @return 存在 pending 复核项时返回 true
     */
    public boolean existsPendingByRecordId(long recordId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM review_items
                WHERE record_id = ? AND status = 'pending'
                """, Integer.class, recordId);
        return count != null && count > 0;
    }

    /**
     * 判断指定购买记录是否存在会阻断自动规则回填的复核项。
     *
     * <p>pending 复核项表示当前仍需人工确认；resolved 且 review_decision=exclude 表示人工已明确排除，
     * 自动回填不得再将该记录改回 include。</p>
     *
     * @param recordId 购买记录 ID
     * @return 存在阻断复核项时返回 true
     */
    public boolean existsBlockingReviewByRecordId(long recordId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM review_items
                WHERE record_id = ?
                  AND (status = 'pending' OR (status = 'resolved' AND review_decision = 'exclude'))
                """, Integer.class, recordId);
        return count != null && count > 0;
    }

    /**
     * 判断指定购买记录是否存在会阻断规则自动回填的复核项。
     *
     * <p>阻断条件仅包括人工已确认 exclude 的复核结果，以及仍处于 pending 的风险型复核。
     * PRODUCT_NAME_NORMALIZATION_REVIEW 和 QUANTITY_UNIT_PARSE_REVIEW 属于规则补齐后可解决的复核，
     * 不能一刀切阻断 apply_rule_to_records。</p>
     *
     * @param recordId 购买记录 ID
     * @return 存在规则回填阻断项时返回 true
     */
    public boolean existsBlockingReviewForRuleApply(long recordId) {
        List<Object> params = new ArrayList<>();
        params.add(recordId);
        params.addAll(RULE_APPLY_BLOCKING_PENDING_REASONS);
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM review_items
                WHERE record_id = ?
                  AND (
                      review_decision = 'exclude'
                      OR (status = 'pending' AND reason_code IN (%s))
                  )
                """.formatted(placeholders(RULE_APPLY_BLOCKING_PENDING_REASONS.size())), Integer.class,
                params.toArray());
        return count != null && count > 0;
    }

    /**
     * 将规则回填已经解决的 pending 复核项标记为已处理。
     *
     * <p>该方法只关闭商品归一化和数量单位解析复核，不会关闭重复订单、支付、价格异常等风险型复核。</p>
     *
     * @param recordId       购买记录 ID
     * @param reviewDecision 复核处理结果，例如 rule_applied
     * @param reviewNote     复核处理说明
     * @return 被关闭的复核项数量
     */
    public int resolvePendingRuleApplyReviews(long recordId, String reviewDecision, String reviewNote) {
        List<Object> params = new ArrayList<>();
        params.add(reviewDecision);
        params.add(reviewNote);
        params.add(ClockUtils.nowText());
        params.add(recordId);
        params.addAll(RULE_APPLY_RESOLVABLE_REASONS);
        return jdbcTemplate.update("""
                UPDATE review_items
                SET status = 'resolved',
                    review_decision = ?,
                    review_note = ?,
                    resolved_at = ?
                WHERE record_id = ?
                  AND status = 'pending'
                  AND reason_code IN (%s)
                """.formatted(placeholders(RULE_APPLY_RESOLVABLE_REASONS.size())), params.toArray());
    }

    private String placeholders(int size) {
        return String.join(", ", java.util.Collections.nCopies(size, "?"));
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
