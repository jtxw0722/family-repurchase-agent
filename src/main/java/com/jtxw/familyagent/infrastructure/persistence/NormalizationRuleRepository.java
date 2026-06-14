package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.UnitFamily;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 16:50:03
 * @Description: 归一化规则仓储，负责从 SQLite normalization_rules 及关键词表读取运行期商品规则
 */
@Repository
public class NormalizationRuleRepository {
    /**
     * 正向匹配关键词类型，命中后当前规则可作为候选。
     */
    private static final String MATCH_TYPE_INCLUDE = "include";
    /**
     * 负向排除关键词类型，命中后仅排除当前规则。
     */
    private static final String MATCH_TYPE_EXCLUDE = "exclude";

    /**
     * SQLite 访问模板，用于读取归一化规则和动态样本统计。
     */
    private final JdbcTemplate jdbcTemplate;

    public NormalizationRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询启用的商品归一化规则，并组装为领域匹配器使用的 ProductRule。
     *
     * @return 按 priority 降序排列的启用规则列表
     */
    public List<ProductRule> listEnabledProductRules() {
        return jdbcTemplate.query("""
                SELECT id, rule_code, normalized_name, category, standard_unit, unit_family, priority
                FROM normalization_rules
                WHERE enabled = 1
                ORDER BY priority DESC, id ASC
                """, (rs, rowNum) -> new ProductRule(
                rs.getString("rule_code"),
                rs.getString("normalized_name"),
                rs.getString("category"),
                rs.getInt("priority"),
                listEnabledKeywords(rs.getLong("id"), MATCH_TYPE_INCLUDE),
                listEnabledKeywords(rs.getLong("id"), MATCH_TYPE_EXCLUDE),
                rs.getString("standard_unit"),
                unitFamily(rs.getString("unit_family"))
        ));
    }

    /**
     * 查询归一化名称库展示数据。
     *
     * <p>sampleCount 使用现有价格基准线仓储的有效历史样本口径：
     * decision = include、非重复、dedupe_status = unique、unit_price 有效且 total_amount 大于 0。</p>
     *
     * @return 按 priority 降序排列的名称库条目
     */
    public List<NormalizationLibraryItem> listLibraryItems() {
        return jdbcTemplate.query("""
                SELECT id, rule_code, normalized_name, category, standard_unit, unit_family,
                       priority, enabled, source
                FROM normalization_rules
                ORDER BY priority DESC, id ASC
                """, (rs, rowNum) -> {
            long ruleId = rs.getLong("id");
            String normalizedName = rs.getString("normalized_name");
            return new NormalizationLibraryItem(
                    rs.getString("rule_code"),
                    normalizedName,
                    rs.getString("category"),
                    rs.getString("standard_unit"),
                    rs.getString("unit_family"),
                    listEnabledKeywords(ruleId, MATCH_TYPE_INCLUDE),
                    listEnabledKeywords(ruleId, MATCH_TYPE_EXCLUDE),
                    countEffectiveSamples(normalizedName),
                    rs.getInt("priority"),
                    rs.getInt("enabled") == 1,
                    rs.getString("source")
            );
        });
    }

    /**
     * 根据规则编码查询规则主表记录。
     *
     * @param ruleCode 规则业务编码，不允许为空
     * @return 规则记录；不存在时返回空
     */
    public Optional<NormalizationRuleRow> findRuleByCode(String ruleCode) {
        List<NormalizationRuleRow> rows = jdbcTemplate.query("""
                SELECT id, rule_code, normalized_name, category, standard_unit, unit_family,
                       priority, enabled, source
                FROM normalization_rules
                WHERE rule_code = ?
                """, (rs, rowNum) -> new NormalizationRuleRow(
                rs.getLong("id"),
                rs.getString("rule_code"),
                rs.getString("normalized_name"),
                rs.getString("category"),
                rs.getString("standard_unit"),
                rs.getString("unit_family"),
                rs.getInt("priority"),
                rs.getInt("enabled") == 1,
                rs.getString("source")
        ), ruleCode);
        return rows.stream().findFirst();
    }

    /**
     * 判断规则编码是否已经存在。
     *
     * @param ruleCode 规则业务编码
     * @return 存在返回 true
     */
    public boolean existsRuleCode(String ruleCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM normalization_rules
                WHERE rule_code = ?
                """, Integer.class, ruleCode);
        return count != null && count > 0;
    }

    /**
     * 判断归一化名称是否已经被其他规则占用。
     *
     * @param normalizedName 归一化商品名称
     * @param excludedRuleId 需要排除的当前规则 ID；新增规则时传 null
     * @return 已被占用返回 true
     */
    public boolean existsNormalizedName(String normalizedName, Long excludedRuleId) {
        Integer count;
        if (excludedRuleId == null) {
            count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM normalization_rules
                    WHERE normalized_name = ?
                    """, Integer.class, normalizedName);
        } else {
            count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM normalization_rules
                    WHERE normalized_name = ?
                      AND id <> ?
                    """, Integer.class, normalizedName, excludedRuleId);
        }
        return count != null && count > 0;
    }

    /**
     * 插入一条启用的归一化规则。
     *
     * @param ruleCode       规则业务编码
     * @param normalizedName 归一化商品名称
     * @param category       商品品类
     * @param standardUnit   标准统计单位
     * @param unitFamily     单位族数据库文本
     * @param priority       规则优先级
     * @param source         数据来源
     * @return 新增规则的数据库 ID
     */
    public long createRule(String ruleCode,
                           String normalizedName,
                           String category,
                           String standardUnit,
                           String unitFamily,
                           int priority,
                           String source) {
        String now = ClockUtils.nowText();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement preparedStatement = connection.prepareStatement("""
                    INSERT INTO normalization_rules(rule_code, normalized_name, category, standard_unit, unit_family,
                        priority, enabled, source, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            preparedStatement.setString(1, ruleCode);
            preparedStatement.setString(2, normalizedName);
            preparedStatement.setString(3, category);
            preparedStatement.setString(4, standardUnit);
            preparedStatement.setString(5, unitFamily);
            preparedStatement.setInt(6, priority);
            preparedStatement.setString(7, source);
            preparedStatement.setString(8, now);
            preparedStatement.setString(9, now);
            return preparedStatement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) {
            throw new IllegalStateException("归一化规则新增失败，未能获取规则 ID");
        }
        return id.longValue();
    }

    /**
     * 更新规则主表基础字段，不修改关键词。
     *
     * @param ruleId         规则数据库 ID
     * @param normalizedName 归一化商品名称
     * @param category       商品品类
     * @param standardUnit   标准统计单位
     * @param unitFamily     单位族数据库文本
     * @param priority       规则优先级
     * @param enabled        是否启用
     */
    public void updateRule(long ruleId,
                           String normalizedName,
                           String category,
                           String standardUnit,
                           String unitFamily,
                           int priority,
                           boolean enabled) {
        jdbcTemplate.update("""
                UPDATE normalization_rules
                SET normalized_name = ?,
                    category = ?,
                    standard_unit = ?,
                    unit_family = ?,
                    priority = ?,
                    enabled = ?,
                    updated_at = ?
                WHERE id = ?
                """, normalizedName, category, standardUnit, unitFamily, priority, enabled ? 1 : 0,
                ClockUtils.nowText(), ruleId);
    }

    /**
     * 软禁用规则主表记录。
     *
     * @param ruleId 规则数据库 ID
     */
    public void disableRule(long ruleId) {
        jdbcTemplate.update("""
                UPDATE normalization_rules
                SET enabled = 0,
                    updated_at = ?
                WHERE id = ?
                """, ClockUtils.nowText(), ruleId);
    }

    /**
     * 查询指定关键词记录。
     *
     * @param ruleId    规则数据库 ID
     * @param keyword   关键词文本
     * @param matchType 关键词类型
     * @return 关键词记录；不存在时返回空
     */
    public Optional<NormalizationRuleKeywordRow> findKeyword(long ruleId, String keyword, String matchType) {
        List<NormalizationRuleKeywordRow> rows = jdbcTemplate.query("""
                SELECT id, rule_id, keyword, match_type, priority, enabled, source
                FROM normalization_rule_keywords
                WHERE rule_id = ?
                  AND keyword = ?
                  AND match_type = ?
                """, (rs, rowNum) -> new NormalizationRuleKeywordRow(
                rs.getLong("id"),
                rs.getLong("rule_id"),
                rs.getString("keyword"),
                rs.getString("match_type"),
                rs.getInt("priority"),
                rs.getInt("enabled") == 1,
                rs.getString("source")
        ), ruleId, keyword, matchType);
        return rows.stream().findFirst();
    }

    /**
     * 判断同一规则下同一关键词是否存在启用的相反语义。
     *
     * @param ruleId    规则数据库 ID
     * @param keyword   关键词文本
     * @param matchType 当前准备写入的关键词类型
     * @return 存在启用冲突返回 true
     */
    public boolean existsEnabledKeywordWithOtherMatchType(long ruleId, String keyword, String matchType) {
        String otherMatchType = MATCH_TYPE_INCLUDE.equals(matchType) ? MATCH_TYPE_EXCLUDE : MATCH_TYPE_INCLUDE;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM normalization_rule_keywords
                WHERE rule_id = ?
                  AND keyword = ?
                  AND match_type = ?
                  AND enabled = 1
                """, Integer.class, ruleId, keyword, otherMatchType);
        return count != null && count > 0;
    }

    /**
     * 插入新的关键词记录。
     *
     * @param ruleId    规则数据库 ID
     * @param keyword   关键词文本
     * @param matchType 关键词类型
     * @param priority  关键词优先级
     * @param source    数据来源
     */
    public void insertKeyword(long ruleId, String keyword, String matchType, int priority, String source) {
        String now = ClockUtils.nowText();
        jdbcTemplate.update("""
                INSERT INTO normalization_rule_keywords(rule_id, keyword, match_type, priority, enabled,
                    source, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?)
                """, ruleId, keyword, matchType, priority, source, now, now);
    }

    /**
     * 恢复启用已存在的关键词记录，并更新优先级和来源。
     *
     * @param keywordId 关键词数据库 ID
     * @param priority  关键词优先级
     * @param source    数据来源
     */
    public void enableKeyword(long keywordId, int priority, String source) {
        jdbcTemplate.update("""
                UPDATE normalization_rule_keywords
                SET enabled = 1,
                    priority = ?,
                    source = ?,
                    updated_at = ?
                WHERE id = ?
                """, priority, source, ClockUtils.nowText(), keywordId);
    }

    /**
     * 软禁用指定关键词记录。
     *
     * @param keywordId 关键词数据库 ID
     */
    public void disableKeyword(long keywordId) {
        jdbcTemplate.update("""
                UPDATE normalization_rule_keywords
                SET enabled = 0,
                    updated_at = ?
                WHERE id = ?
                """, ClockUtils.nowText(), keywordId);
    }

    /**
     * 查询指定规则下启用的关键词。
     *
     * @param ruleId    归一化规则数据库 ID
     * @param matchType 关键词类型，只允许 include 或 exclude
     * @return 关键词列表；无关键词时返回空集合
     */
    private List<String> listEnabledKeywords(long ruleId, String matchType) {
        return jdbcTemplate.queryForList("""
                SELECT keyword FROM normalization_rule_keywords
                WHERE rule_id = ?
                  AND match_type = ?
                  AND enabled = 1
                ORDER BY priority DESC, id ASC
                """, String.class, ruleId, matchType);
    }

    /**
     * 统计指定归一化名称的有效历史样本数量。
     *
     * @param normalizedName 归一化商品名称
     * @return 与价格基准线仓储一致口径下的历史样本数
     */
    private int countEffectiveSamples(String normalizedName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM purchase_records
                WHERE normalized_name = ?
                  AND decision = 'include'
                  AND is_duplicate = 0
                  AND dedupe_status = 'unique'
                  AND unit_price IS NOT NULL
                  AND total_amount > 0
                """, Integer.class, normalizedName);
        return count == null ? 0 : count;
    }

    /**
     * 将数据库单位族文本转换为领域枚举。
     *
     * @param value 数据库中的 unit_family 文本
     * @return 领域单位族枚举；无法识别时返回 UNKNOWN
     */
    private UnitFamily unitFamily(String value) {
        if (value == null || value.isBlank()) {
            return UnitFamily.UNKNOWN;
        }
        return UnitFamily.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 归一化规则主表记录。
     *
     * @param id             规则数据库 ID
     * @param ruleCode       规则业务编码
     * @param normalizedName 归一化商品名称
     * @param category       商品品类
     * @param standardUnit   标准统计单位
     * @param unitFamily     单位族数据库文本
     * @param priority       规则优先级
     * @param enabled        是否启用
     * @param source         数据来源
     */
    public record NormalizationRuleRow(long id,
                                       String ruleCode,
                                       String normalizedName,
                                       String category,
                                       String standardUnit,
                                       String unitFamily,
                                       int priority,
                                       boolean enabled,
                                       String source) {
    }

    /**
     * 归一化规则关键词记录。
     *
     * @param id        关键词数据库 ID
     * @param ruleId    规则数据库 ID
     * @param keyword   关键词文本
     * @param matchType 关键词类型
     * @param priority  关键词优先级
     * @param enabled   是否启用
     * @param source    数据来源
     */
    public record NormalizationRuleKeywordRow(long id,
                                              long ruleId,
                                              String keyword,
                                              String matchType,
                                              int priority,
                                              boolean enabled,
                                              String source) {
    }
}
