package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.common.ClockUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:27:12
 * @Description: 商品正向别名仓储，保存人工确认后的 alias_key 到标准品类映射。
 */
@Repository
public class ProductAliasRepository {
    private final JdbcTemplate jdbcTemplate;

    public ProductAliasRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 根据 alias_key 查询正向别名。
     *
     * @param aliasKey 清洗后的商品标题匹配键
     * @return 正向别名映射
     */
    public Optional<ProductAlias> findByAliasKey(String aliasKey) {
        if (aliasKey == null || aliasKey.isBlank()) {
            return Optional.empty();
        }
        List<ProductAlias> aliases = jdbcTemplate.query("""
                SELECT id, alias, alias_key, normalized_name, target_unit, category
                FROM product_aliases
                WHERE alias_key = ?
                """, (rs, rowNum) -> new ProductAlias(
                rs.getLong("id"),
                rs.getString("alias"),
                rs.getString("alias_key"),
                rs.getString("normalized_name"),
                rs.getString("target_unit"),
                rs.getString("category")
        ), aliasKey);
        return aliases.stream().findFirst();
    }

    /**
     * 写入或更新正向别名。
     *
     * @param alias          原始商品标题
     * @param aliasKey       清洗后的匹配键
     * @param normalizedName 标准品类
     * @param targetUnit     标准单位
     * @param category       商品分类
     */
    public void upsert(String alias, String aliasKey, String normalizedName, String targetUnit, String category) {
        jdbcTemplate.update("""
                INSERT INTO product_aliases(alias, alias_key, normalized_name, target_unit, category, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(alias_key) DO UPDATE SET
                    alias = excluded.alias,
                    normalized_name = excluded.normalized_name,
                    target_unit = excluded.target_unit,
                    category = excluded.category
                """, alias, aliasKey, normalizedName, targetUnit, category, ClockUtils.nowText());
    }

    /**
     * 查询与 alias_key 存在简单包含关系的正向别名。
     *
     * @param aliasKey 清洗后的商品标题匹配键
     * @param limit    最大返回条数
     * @return 相似正向别名列表
     */
    public List<ProductAlias> listSimilar(String aliasKey, int limit) {
        if (aliasKey == null || aliasKey.isBlank()) {
            return List.of();
        }
        String like = "%" + aliasKey + "%";
        return jdbcTemplate.query("""
                SELECT id, alias, alias_key, normalized_name, target_unit, category
                FROM product_aliases
                WHERE alias_key LIKE ? OR ? LIKE '%' || alias_key || '%'
                ORDER BY id DESC
                LIMIT ?
                """, (rs, rowNum) -> new ProductAlias(
                rs.getLong("id"),
                rs.getString("alias"),
                rs.getString("alias_key"),
                rs.getString("normalized_name"),
                rs.getString("target_unit"),
                rs.getString("category")
        ), like, aliasKey, limit);
    }

    public record ProductAlias(Long id,
                               String alias,
                               String aliasKey,
                               String normalizedName,
                               String targetUnit,
                               String category) {
    }
}
