package com.jtxw.familyagent.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.NormalizationLlmTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 归一化 LLM 通用任务仓储，负责 normalization_llm_tasks 表的创建后读写、状态流转和 JSON 结果回显
 */
@Repository
public class NormalizationLlmTaskRepository {
    /**
     * 字符串列表 JSON 反序列化类型，用于读取任务级 warnings。
     */
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    /**
     * JSON 对象反序列化类型，用于读取任务 result_json。
     */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    /**
     * SQLite 访问模板，用于执行任务表 SQL。
     */
    private final JdbcTemplate jdbcTemplate;
    /**
     * JSON 序列化组件，用于保存请求、结果和警告。
     */
    private final ObjectMapper objectMapper;

    /**
     * 创建通用 LLM 任务仓储。
     *
     * @param jdbcTemplate SQLite JDBC 访问组件，不能为空
     * @param objectMapper JSON 序列化组件，不能为空
     */
    public NormalizationLlmTaskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建 pending 状态的通用 LLM 任务。
     *
     * @param taskType      任务类型，不允许为空
     * @param batchId       导入批次 ID，允许为空
     * @param owner         订单归属人，允许为空
     * @param fullScan      是否显式全量扫描
     * @param applyChanges  是否应用建议写库
     * @param candidateMode 候选模式，允许为空
     * @param limit         最大候选数量
     * @param request       原始请求对象，将以 JSON 保存便于审计
     * @return 新建任务 ID
     */
    public long create(String taskType,
                       Long batchId,
                       String owner,
                       boolean fullScan,
                       boolean applyChanges,
                       String candidateMode,
                       int limit,
                       Object request) {
        String now = ClockUtils.nowText();
        String sql = """
                INSERT INTO normalization_llm_tasks(
                    task_type, status, batch_id, owner, full_scan, apply_changes,
                    candidate_mode, limit_count, request_json, warnings_json,
                    created_at, updated_at
                ) VALUES (?, 'pending', ?, ?, ?, ?, ?, ?, ?, '[]', ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, taskType);
            ps.setObject(2, batchId);
            ps.setObject(3, owner);
            ps.setObject(4, fullScan ? 1 : 0);
            ps.setObject(5, applyChanges ? 1 : 0);
            ps.setObject(6, candidateMode);
            ps.setObject(7, limit);
            ps.setObject(8, toJson(request));
            ps.setObject(9, now);
            ps.setObject(10, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("无法获取新建 normalization_llm_tasks ID");
        }
        return key.longValue();
    }

    /**
     * 根据任务 ID 查询通用 LLM 任务。
     *
     * @param taskId 任务 ID，对应 normalization_llm_tasks.id
     * @return 任务详情；不存在时返回 Optional.empty()
     */
    public Optional<NormalizationLlmTask> findById(long taskId) {
        List<NormalizationLlmTask> tasks = jdbcTemplate.query("""
                SELECT * FROM normalization_llm_tasks
                WHERE id = ?
                """, rowMapper(), taskId);
        return tasks.stream().findFirst();
    }

    /**
     * 判断是否存在 pending 或 running 状态任务。
     *
     * @return 存在 active 任务时返回 true，否则返回 false
     */
    public boolean existsActiveTask() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM normalization_llm_tasks
                WHERE status IN ('pending', 'running')
                """, Integer.class);
        return count != null && count > 0;
    }

    /**
     * 将应用重启前遗留的 active 任务统一标记为 failed。
     *
     * @param errorMessage 中断失败原因，应避免包含敏感信息
     * @return 被更新的任务数量，单位为条
     */
    public int markInterruptedActiveTasks(String errorMessage) {
        String now = ClockUtils.nowText();
        return jdbcTemplate.update("""
                UPDATE normalization_llm_tasks
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
     * @param taskId 任务 ID，对应 normalization_llm_tasks.id
     */
    public void markRunning(long taskId) {
        jdbcTemplate.update("""
                UPDATE normalization_llm_tasks
                SET status = 'running',
                    updated_at = ?
                WHERE id = ?
                """, ClockUtils.nowText(), taskId);
    }

    /**
     * 更新任务统计进度。
     *
     * @param taskId         任务 ID
     * @param candidateCount 候选数量
     * @param analyzedCount  已分析数量
     */
    public void updateProgress(long taskId, int candidateCount, int analyzedCount) {
        jdbcTemplate.update("""
                UPDATE normalization_llm_tasks
                SET candidate_count = ?,
                    analyzed_count = ?,
                    updated_at = ?
                WHERE id = ?
                """, candidateCount, analyzedCount, ClockUtils.nowText(), taskId);
    }

    /**
     * 将任务标记为 completed 并保存最终 JSON 结果。
     *
     * @param taskId                  任务 ID
     * @param candidateCount          候选数量
     * @param analyzedCount           已分析数量
     * @param suggestedOperationCount 建议操作数量
     * @param appliedCount            已应用数量
     * @param skippedCount            已跳过数量
     * @param result                  任务结果对象，会序列化为 JSON
     * @param warnings                任务级警告列表
     */
    public void markCompleted(long taskId,
                              int candidateCount,
                              int analyzedCount,
                              int suggestedOperationCount,
                              int appliedCount,
                              int skippedCount,
                              Object result,
                              List<String> warnings) {
        String now = ClockUtils.nowText();
        jdbcTemplate.update("""
                UPDATE normalization_llm_tasks
                SET status = 'completed',
                    candidate_count = ?,
                    analyzed_count = ?,
                    suggested_operation_count = ?,
                    applied_count = ?,
                    skipped_count = ?,
                    result_json = ?,
                    warnings_json = ?,
                    error_message = NULL,
                    finished_at = ?,
                    updated_at = ?
                WHERE id = ?
                """, candidateCount, analyzedCount, suggestedOperationCount, appliedCount, skippedCount,
                toJson(result), toJson(warnings == null ? List.of() : warnings), now, now, taskId);
    }

    /**
     * 将任务标记为 failed。
     *
     * @param taskId       任务 ID
     * @param errorMessage 任务级失败原因，允许为空但不应包含敏感信息
     */
    public void markFailed(long taskId, String errorMessage) {
        String now = ClockUtils.nowText();
        jdbcTemplate.update("""
                UPDATE normalization_llm_tasks
                SET status = 'failed',
                    error_message = ?,
                    finished_at = ?,
                    updated_at = ?
                WHERE id = ?
                """, errorMessage, now, now, taskId);
    }

    /**
     * 构建通用任务查询行映射器。
     *
     * @return normalization_llm_tasks 查询结果到领域 DTO 的映射器
     */
    private RowMapper<NormalizationLlmTask> rowMapper() {
        return (rs, rowNum) -> new NormalizationLlmTask(
                rs.getLong("id"),
                rs.getString("task_type"),
                rs.getString("status"),
                nullableLong(rs.getObject("batch_id")),
                rs.getString("owner"),
                rs.getInt("full_scan") == 1,
                rs.getInt("apply_changes") == 1,
                rs.getString("candidate_mode"),
                rs.getInt("limit_count"),
                rs.getInt("candidate_count"),
                rs.getInt("analyzed_count"),
                rs.getInt("suggested_operation_count"),
                rs.getInt("applied_count"),
                rs.getInt("skipped_count"),
                parseWarnings(rs.getString("warnings_json")),
                parseResult(rs.getString("result_json")),
                rs.getString("error_message"),
                rs.getString("created_at"),
                rs.getString("updated_at")
        );
    }

    /**
     * 将任意对象序列化为 JSON 字符串。
     *
     * @param value 待序列化对象，允许为空
     * @return JSON 字符串；序列化失败时抛出明确异常
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("通用 LLM 任务 JSON 序列化失败", e);
        }
    }

    private List<String> parseWarnings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception e) {
            return List.of("任务 warnings_json 解析失败");
        }
    }

    private Map<String, Object> parseResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of("parseError", "任务 result_json 解析失败");
        }
    }

    private Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }
}
