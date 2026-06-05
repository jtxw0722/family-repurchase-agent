package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.common.ClockUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * @Author: jtxw
 * @Date: 2026/06/05
 * @Description: 商品负向别名仓储，保存人工确认过的误判样本。
 */
@Repository
public class ProductNegativeAliasRepository {
    private final JdbcTemplate jdbcTemplate;

    public ProductNegativeAliasRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 根据 alias_key 查询负向别名。
     *
     * @param aliasKey 清洗后的商品标题匹配键
     * @return 负向别名
     */
    public Optional<ProductNegativeAlias> findByAliasKey(String aliasKey) {
        if (aliasKey == null || aliasKey.isBlank()) {
            return Optional.empty();
        }
        List<ProductNegativeAlias> aliases = jdbcTemplate.query("""
                SELECT id, alias, alias_key, rejected_normalized_name, reason
                FROM product_negative_aliases
                WHERE alias_key = ?
                """, (rs, rowNum) -> new ProductNegativeAlias(
                rs.getLong("id"),
                rs.getString("alias"),
                rs.getString("alias_key"),
                rs.getString("rejected_normalized_name"),
                rs.getString("reason")
        ), aliasKey);
        return aliases.stream().findFirst();
    }

    /**
     * 写入或更新负向别名。
     *
     * @param alias                  原始商品标题
     * @param aliasKey               清洗后的匹配键
     * @param rejectedNormalizedName 被人工否定的标准品类
     * @param reason                 否定原因
     */
    public void upsert(String alias, String aliasKey, String rejectedNormalizedName, String reason) {
        jdbcTemplate.update("""
                INSERT INTO product_negative_aliases(alias, alias_key, rejected_normalized_name, reason, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(alias_key) DO UPDATE SET
                    alias = excluded.alias,
                    rejected_normalized_name = excluded.rejected_normalized_name,
                    reason = excluded.reason
                """, alias, aliasKey, rejectedNormalizedName, reason, ClockUtils.nowText());
    }

    public record ProductNegativeAlias(Long id,
                                       String alias,
                                       String aliasKey,
                                       String rejectedNormalizedName,
                                       String reason) {
    }
}
