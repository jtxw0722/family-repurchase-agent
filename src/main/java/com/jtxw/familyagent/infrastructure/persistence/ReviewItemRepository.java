package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.ReviewItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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

    public void create(Long recordId, String reasonCode, String reasonMessage) {
        jdbcTemplate.update("INSERT INTO review_items(record_id, reason_code, reason_message, status, created_at) VALUES (?, ?, ?, ?, ?)",
                recordId, reasonCode, reasonMessage, "pending", ClockUtils.nowText());
    }

    public List<ReviewItem> listPending() {
        return jdbcTemplate.query("SELECT * FROM review_items WHERE status='pending' ORDER BY id", rowMapper());
    }

    public Optional<ReviewItem> findById(long id) {
        List<ReviewItem> items = jdbcTemplate.query("SELECT * FROM review_items WHERE id = ?", rowMapper(), id);
        return items.stream().findFirst();
    }

    public int resolve(long id, String reviewDecision, String reviewNote) {
        return jdbcTemplate.update("""
                UPDATE review_items
                SET status = ?, review_decision = ?, review_note = ?, resolved_at = ?
                WHERE id = ? AND status = 'pending'
                """, "resolved", reviewDecision, reviewNote, ClockUtils.nowText(), id);
    }

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
}
