package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:27:12
 * @Description: 购买记录仓储，负责订单明细的写入和价格统计查询。
 */
@Repository
public class PurchaseRecordRepository {
    private final JdbcTemplate jdbcTemplate;

    public PurchaseRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 保存标准化后的购买记录。
     *
     * @param record 购买记录
     * @return 新增记录 ID
     */
    public long save(PurchaseRecord record) {
        String sql = """
                INSERT INTO purchase_records(
                    batch_id, order_time, platform, owner, product_name, normalized_name, sku,
                    category, sub_category, quantity, unit, total_amount, product_amount, paid_amount,
                    shipping_fee, amount_source, unit_price, currency, decision, is_duplicate,
                    dedupe_status, source_file, shop_name, note, source_text, normalization_rule, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, record.batchId());
            ps.setObject(2, record.orderTime());
            ps.setObject(3, record.platform());
            ps.setObject(4, record.owner());
            ps.setObject(5, record.productName());
            ps.setObject(6, record.normalizedName());
            ps.setObject(7, record.sku());
            ps.setObject(8, record.category());
            ps.setObject(9, record.subCategory());
            ps.setObject(10, record.quantity());
            ps.setObject(11, record.unit());
            ps.setObject(12, record.totalAmount());
            ps.setObject(13, record.productAmount());
            ps.setObject(14, record.paidAmount());
            ps.setObject(15, record.shippingFee());
            ps.setObject(16, record.amountSource());
            ps.setObject(17, record.unitPrice());
            ps.setObject(18, record.currency());
            ps.setObject(19, record.decision());
            ps.setObject(20, record.duplicate() ? 1 : 0);
            ps.setObject(21, record.dedupeStatus());
            ps.setObject(22, record.sourceFile());
            ps.setObject(23, record.shopName());
            ps.setObject(24, record.note());
            ps.setObject(25, record.sourceText());
            ps.setObject(26, record.normalizationRule());
            ps.setObject(27, ClockUtils.nowText());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("无法获取新建 purchase_records ID");
        }
        return key.longValue();
    }

    /**
     * 判断本地数据库中是否已存在相同订单。
     *
     * <p>该查询使用订单时间、平台、归属人、归一化商品名、SKU、数量、单位、
     * 当前统计金额和币种做精确匹配，不按来源文件或导入批次区分。</p>
     *
     * @param record 待检查的购买记录
     * @return 是否已存在相同订单
     */
    public boolean existsDuplicate(PurchaseRecord record) {
        // 字符字段使用 COALESCE 做空值安全比较，数值字段保留 NULL 与 NULL 等价
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM purchase_records
                WHERE COALESCE(order_time, '') = COALESCE(?, '')
                  AND COALESCE(platform, '') = COALESCE(?, '')
                  AND lower(COALESCE(owner, '')) = lower(COALESCE(?, ''))
                  AND COALESCE(normalized_name, '') = COALESCE(?, '')
                  AND COALESCE(sku, '') = COALESCE(?, '')
                  AND (quantity = ? OR (quantity IS NULL AND ? IS NULL))
                  AND COALESCE(unit, '') = COALESCE(?, '')
                  AND (total_amount = ? OR (total_amount IS NULL AND ? IS NULL))
                  AND COALESCE(currency, '') = COALESCE(?, '')
                """, Integer.class,
                record.orderTime(), record.platform(), record.owner(), record.normalizedName(), record.sku(),
                record.quantity(), record.quantity(), record.unit(), record.totalAmount(), record.totalAmount(),
                record.currency());
        return count != null && count > 0;
    }

    /**
     * 根据 ID 查询购买记录。
     *
     * @param id 购买记录 ID
     * @return 购买记录
     */
    public Optional<PurchaseRecord> findById(long id) {
        List<PurchaseRecord> records = jdbcTemplate.query("SELECT * FROM purchase_records WHERE id = ?",
                rowMapper(), id);
        return records.stream().findFirst();
    }

    /**
     * 查询指定导入批次内的购买记录。
     *
     * @param batchId 导入批次 ID
     * @return 批次内购买记录列表
     */
    public List<PurchaseRecord> listByBatchId(long batchId) {
        return jdbcTemplate.query("""
                SELECT * FROM purchase_records
                WHERE batch_id = ?
                ORDER BY id
                """, rowMapper(), batchId);
    }

    /**
     * 查询指定商品的历史有效单价样本。
     *
     * <p>默认只返回正式统计口径内的记录：
     * decision = include，is_duplicate = 0，dedupe_status = unique。</p>
     *
     * @param normalizedName 归一化商品名称
     * @return 历史单位价格列表
     */
    public List<Double> listUnitPrices(String normalizedName) {
        return jdbcTemplate.queryForList("""
                SELECT unit_price FROM purchase_records
                WHERE normalized_name = ?
                  AND decision = 'include'
                  AND is_duplicate = 0
                  AND dedupe_status = 'unique'
                  AND unit_price IS NOT NULL
                  AND total_amount > 0
                """, Double.class, normalizedName);
    }

    /**
     * 查询指定商品的历史有效价格记录。
     *
     * <p>默认只返回正式统计口径内的记录：
     * decision = include，is_duplicate = 0，dedupe_status = unique。</p>
     *
     * @param normalizedName 归一化商品名称
     * @return 历史有效价格记录列表
     */
    public List<PurchaseRecord> listPriceHistoryRecords(String normalizedName) {
        return jdbcTemplate.query("""
                SELECT * FROM purchase_records
                WHERE normalized_name = ?
                  AND decision = 'include'
                  AND is_duplicate = 0
                  AND dedupe_status = 'unique'
                  AND unit_price IS NOT NULL
                  AND total_amount > 0
                ORDER BY order_time
                """, rowMapper(), normalizedName);
    }

    /**
     * 查询指定归一化商品和单位的有效历史单价区间。
     *
     * <p>用于手动录入时拦截明显偏离历史区间的自然语言抽取结果。</p>
     *
     * @param normalizedName 归一化商品名称
     * @param unit           标准单位
     * @return 历史样本数量、最低价和最高价
     */
    public PriceRangeStats priceRangeStats(String normalizedName, String unit) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS sample_size,
                       MIN(unit_price) AS historical_min,
                       MAX(unit_price) AS historical_max
                FROM purchase_records
                WHERE normalized_name = ?
                  AND unit = ?
                  AND decision = 'include'
                  AND is_duplicate = 0
                  AND dedupe_status = 'unique'
                  AND unit_price IS NOT NULL
                  AND total_amount > 0
                """, (rs, rowNum) -> new PriceRangeStats(
                rs.getInt("sample_size"),
                nullableDouble(rs, "historical_min"),
                nullableDouble(rs, "historical_max")
        ), normalizedName, unit);
    }

    /**
     * 更新购买记录的统计决策。
     *
     * @param id       购买记录 ID
     * @param decision 统计决策，通常为 include 或 exclude
     * @return 更新记录数
     */
    public int updateDecision(long id, String decision) {
        return jdbcTemplate.update("UPDATE purchase_records SET decision = ? WHERE id = ?", decision, id);
    }

    /**
     * 更新购买记录的归一化商品名和统计决策。
     *
     * @param id             购买记录 ID
     * @param normalizedName 归一化商品名
     * @param decision       统计决策
     * @return 更新记录数
     */
    public int updateNormalizationAndDecision(long id, String normalizedName, String decision) {
        return jdbcTemplate.update("""
                UPDATE purchase_records
                SET normalized_name = ?, decision = ?
                WHERE id = ?
                """, normalizedName, decision, id);
    }

    /**
     * 更新购买记录的统计决策和去重状态。
     *
     * <p>用于处理疑似重复订单的人工复核结果：确认纳入时恢复为 unique，
     * 确认排除时保持 duplicate。</p>
     *
     * @param id           购买记录 ID
     * @param decision     统计决策
     * @param duplicate    是否为重复订单
     * @param dedupeStatus 去重状态
     * @return 更新记录数
     */
    public int updateDecisionAndDedupe(long id, String decision, boolean duplicate, String dedupeStatus) {
        return jdbcTemplate.update("""
                UPDATE purchase_records
                SET decision = ?, is_duplicate = ?, dedupe_status = ?
                WHERE id = ?
                """, decision, duplicate ? 1 : 0, dedupeStatus, id);
    }

    /**
     * 查询指定月份内纳入正式统计的购买记录。
     *
     * <p>默认只返回 decision = include、is_duplicate = 0、dedupe_status = unique，
     * 且统计金额大于 0 的记录。</p>
     *
     * @param month 月份，格式为 yyyy-MM
     * @return 月度有效购买记录列表
     */
    public List<PurchaseRecord> listIncludedByMonth(String month) {
        return jdbcTemplate.query("""
                SELECT * FROM purchase_records
                WHERE substr(order_time, 1, 7) = ?
                  AND decision = 'include'
                  AND is_duplicate = 0
                  AND dedupe_status = 'unique'
                  AND total_amount > 0
                ORDER BY order_time
                """, rowMapper(), month);
    }

    private RowMapper<PurchaseRecord> rowMapper() {
        return (rs, rowNum) -> new PurchaseRecord(
                rs.getLong("id"), rs.getLong("batch_id"), rs.getString("order_time"),
                rs.getString("platform"), rs.getString("owner"), rs.getString("product_name"),
                rs.getString("normalized_name"), rs.getString("sku"), rs.getString("category"),
                rs.getString("sub_category"), nullableDouble(rs, "quantity"), rs.getString("unit"),
                nullableDouble(rs, "total_amount"), nullableDouble(rs, "product_amount"),
                nullableDouble(rs, "paid_amount"), nullableDouble(rs, "shipping_fee"),
                rs.getString("amount_source"), nullableDouble(rs, "unit_price"), rs.getString("currency"),
                rs.getString("decision"), rs.getInt("is_duplicate") == 1, rs.getString("dedupe_status"),
                rs.getString("source_file"), rs.getString("shop_name"), rs.getString("note"),
                rs.getString("source_text"), safeColumn(rs, "normalization_rule"), rs.getString("created_at")
        );
    }

    private String safeColumn(ResultSet rs, String columnName) {
        try {
            return rs.getString(columnName);
        } catch (SQLException e) {
            return null;
        }
    }

    private Double nullableDouble(ResultSet rs, String columnName) throws SQLException {
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : value;
    }

    public record PriceRangeStats(int sampleSize, Double historicalMin, Double historicalMax) {
    }
}
