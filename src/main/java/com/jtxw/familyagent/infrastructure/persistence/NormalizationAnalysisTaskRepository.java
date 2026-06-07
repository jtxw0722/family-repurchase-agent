package com.jtxw.familyagent.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.NormalizationAnalysisTask;
import com.jtxw.familyagent.domain.model.NormalizationAnalyzeProgress;
import com.jtxw.familyagent.domain.model.NormalizationAnalyzeResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * @Author: jtxw
 * @Date: 2026/06/07 15:10:28
 * @Description: 商品归一化异步分析任务仓储，负责 normalization_analysis_tasks 表的创建后读写和状态流转。
 */
@Repository
public class NormalizationAnalysisTaskRepository {
    /**
     * 字符串列表 JSON 反序列化类型，用于读取 include/exclude 关键词列表。
     */
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * Spring JDBC 访问组件，用于执行 SQLite SQL。
     */
    private final JdbcTemplate jdbcTemplate;
    /**
     * JSON 序列化组件，用于保存和读取任务筛选关键词。
     */
    private final ObjectMapper objectMapper;

    /**
     * 创建商品归一化分析任务仓储。
     *
     * @param jdbcTemplate SQLite JDBC 访问组件，不能为空
     * @param objectMapper JSON 序列化组件，不能为空
     */
    public NormalizationAnalysisTaskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建 pending 状态的归一化分析任务。
     *
     * @param batchId         导入批次 ID，对应 raw_import_batches.id
     * @param limit           最大分析候选数，小于等于 0 时落库为默认值 100
     * @param forceReanalyze  是否强制重新分析已存在建议的商品
     * @param includeKeywords 包含关键词列表，允许为空或 null
     * @param excludeKeywords 排除关键词列表，允许为空或 null
     * @param onlyFailed      是否只重试已有 failed suggestion 对应的候选商品
     * @return 新建任务 ID
     */
    public long create(long batchId,
                       int limit,
                       boolean forceReanalyze,
                       List<String> includeKeywords,
                       List<String> excludeKeywords,
                       boolean onlyFailed) {
        String now = ClockUtils.nowText();
        // 筛选关键词以 JSON 保存，方便查询接口完整回显用户创建任务时的过滤条件。
        String sql = """
                INSERT INTO normalization_analysis_tasks(
                    batch_id, status, limit_count, force_reanalyze, include_keywords_json,
                    exclude_keywords_json, only_failed, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, batchId);
            ps.setObject(2, "pending");
            ps.setObject(3, limit <= 0 ? 100 : limit);
            ps.setObject(4, forceReanalyze ? 1 : 0);
            ps.setObject(5, keywordsJson(includeKeywords));
            ps.setObject(6, keywordsJson(excludeKeywords));
            ps.setObject(7, onlyFailed ? 1 : 0);
            ps.setObject(8, now);
            ps.setObject(9, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("无法获取新建 normalization_analysis_tasks ID");
        }
        return key.longValue();
    }

    /**
     * 根据任务 ID 查询归一化分析任务。
     *
     * @param taskId 任务 ID，对应 normalization_analysis_tasks.id
     * @return 任务详情；不存在时返回 Optional.empty()
     */
    public Optional<NormalizationAnalysisTask> findById(long taskId) {
        List<NormalizationAnalysisTask> tasks = jdbcTemplate.query("""
                SELECT * FROM normalization_analysis_tasks
                WHERE id = ?
                """, rowMapper(), taskId);
        return tasks.stream().findFirst();
    }

    /**
     * 判断当前是否存在 active 任务。
     *
     * <p>active 仅包含 pending 和 running，用于限制同一时间只有一个归一化分析任务。</p>
     *
     * @return 存在 pending/running 任务时返回 true，否则返回 false
     */
    public boolean existsActiveTask() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM normalization_analysis_tasks
                WHERE status IN ('pending', 'running')
                """, Integer.class);
        return count != null && count > 0;
    }

    /**
     * 将应用重启前遗留的 active 任务标记为 failed。
     *
     * @param errorMessage 中断失败原因，应明确提示用户重新创建任务，且不包含敏感信息
     * @return 被更新的任务数量，单位为条
     */
    public int markInterruptedActiveTasks(String errorMessage) {
        String now = ClockUtils.nowText();
        return jdbcTemplate.update("""
                UPDATE normalization_analysis_tasks
                SET status = 'failed',
                    error_message = ?,
                    finished_at = COALESCE(finished_at, ?),
                    updated_at = ?
                WHERE status IN ('pending', 'running')
                """, errorMessage, now, now);
    }

    /**
     * 将任务标记为 running。
     *
     * <p>started_at 仅在首次进入 running 时写入，避免后续重复调用覆盖真实开始时间。</p>
     *
     * @param taskId 任务 ID，对应 normalization_analysis_tasks.id
     */
    public void markRunning(long taskId) {
        String now = ClockUtils.nowText();
        jdbcTemplate.update("""
                UPDATE normalization_analysis_tasks
                SET status = 'running',
                    started_at = COALESCE(started_at, ?),
                    updated_at = ?
                WHERE id = ?
                """, now, now, taskId);
    }

    /**
     * 更新归一化分析任务进度。
     *
     * @param taskId   任务 ID，对应 normalization_analysis_tasks.id
     * @param progress 最新进度快照，不能为空
     */
    public void updateProgress(long taskId, NormalizationAnalyzeProgress progress) {
        jdbcTemplate.update("""
                UPDATE normalization_analysis_tasks
                SET candidate_count = ?,
                    analyzed_count = ?,
                    auto_excluded_count = ?,
                    pending_batch_approval_count = ?,
                    pending_review_count = ?,
                    failed_count = ?,
                    current_batch_index = ?,
                    total_batch_count = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                progress.candidateCount(),
                progress.analyzedCount(),
                progress.autoExcludedCount(),
                progress.pendingBatchApprovalCount(),
                progress.pendingReviewCount(),
                progress.failedCount(),
                progress.currentBatchIndex(),
                progress.totalBatchCount(),
                ClockUtils.nowText(),
                taskId);
    }

    /**
     * 将任务标记为 completed 并写入最终统计结果。
     *
     * @param taskId 任务 ID，对应 normalization_analysis_tasks.id
     * @param result 同步归一化分析结果，包含候选数、成功数、失败数和完成消息，不能为空
     */
    public void markCompleted(long taskId, NormalizationAnalyzeResult result) {
        String now = ClockUtils.nowText();
        jdbcTemplate.update("""
                UPDATE normalization_analysis_tasks
                SET status = 'completed',
                    candidate_count = ?,
                    analyzed_count = ?,
                    auto_excluded_count = ?,
                    pending_batch_approval_count = ?,
                    pending_review_count = ?,
                    failed_count = ?,
                    current_batch_index = total_batch_count,
                    message = ?,
                    error_message = NULL,
                    finished_at = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                result.candidateCount(),
                result.analyzedCount(),
                result.autoExcludedCount(),
                result.pendingBatchApprovalCount(),
                result.pendingReviewCount(),
                result.failedCount(),
                result.message(),
                now,
                now,
                taskId);
    }

    /**
     * 将任务标记为 failed。
     *
     * @param taskId       任务 ID，对应 normalization_analysis_tasks.id
     * @param errorMessage 任务级失败原因，允许为空但不应包含 API Key 等敏感信息
     */
    public void markFailed(long taskId, String errorMessage) {
        String now = ClockUtils.nowText();
        jdbcTemplate.update("""
                UPDATE normalization_analysis_tasks
                SET status = 'failed',
                    error_message = ?,
                    finished_at = ?,
                    updated_at = ?
                WHERE id = ?
                """, errorMessage, now, now, taskId);
    }

    /**
     * 构建任务表行映射器。
     *
     * @return normalization_analysis_tasks 查询结果到领域 DTO 的映射器
     */
    private RowMapper<NormalizationAnalysisTask> rowMapper() {
        return (rs, rowNum) -> new NormalizationAnalysisTask(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                rs.getString("status"),
                rs.getInt("limit_count"),
                rs.getInt("force_reanalyze") == 1,
                parseKeywords(rs.getString("include_keywords_json")),
                parseKeywords(rs.getString("exclude_keywords_json")),
                rs.getInt("only_failed") == 1,
                rs.getInt("candidate_count"),
                rs.getInt("analyzed_count"),
                rs.getInt("auto_excluded_count"),
                rs.getInt("pending_batch_approval_count"),
                rs.getInt("pending_review_count"),
                rs.getInt("failed_count"),
                rs.getInt("current_batch_index"),
                rs.getInt("total_batch_count"),
                rs.getString("message"),
                rs.getString("error_message"),
                rs.getString("created_at"),
                rs.getString("started_at"),
                rs.getString("finished_at"),
                rs.getString("updated_at")
        );
    }

    /**
     * 将关键词列表序列化为 JSON 字符串。
     *
     * @param keywords 关键词列表，允许为空或 null
     * @return JSON 数组字符串；序列化失败时返回空数组，避免任务创建因过滤条件回显失败而中断
     */
    private String keywordsJson(List<String> keywords) {
        try {
            return objectMapper.writeValueAsString(keywords == null ? List.of() : keywords);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * 将任务表中的关键词 JSON 还原为列表。
     *
     * @param json 数据库存储的 JSON 数组字符串，允许为空
     * @return 关键词列表；JSON 异常时返回空列表，保证查询接口可用
     */
    private List<String> parseKeywords(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }
}
