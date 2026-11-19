package com.jtxw.familyagent.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 22:20:17
 * @Description: 库存基础设施仓储，仅写库存扩展表，只读现有归一化规则和购买明细
 */
@Repository
public class InventoryRepository {
    /** 数据库模板，非空，由外层应用事务管理连接。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc SQLite 数据库模板 */
    public InventoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** @return 可选的已有归一化商品，不创建或修改规则 */
    public List<Map<String, Object>> products() {
        return jdbc.queryForList("SELECT id, normalized_name, standard_unit, unit_family FROM normalization_rules WHERE enabled=1 ORDER BY normalized_name");
    }

    /**
     * 查询已启用的归一化商品，标准单位作为创建库存的计量依据。
     * @param productId 归一化规则ID
     * @return 商品规则；不存在或已禁用时抛参数异常，无写入副作用
     */
    public Map<String, Object> product(long productId) {
        return products().stream().filter(p -> ((Number) p.get("id")).longValue() == productId)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("请选择已启用的归一化商品"));
    }

    /**
     * 查找同一家庭商品的唯一库存档案，无写入副作用。
     * @param productId 归一化规则ID
     * @return 库存ID，不存在时null
     */
    public Long findProductInventory(long productId) {
        return jdbc.queryForList("SELECT id FROM inventory_items WHERE normalized_product_id=?", Long.class, productId)
                .stream().findFirst().orElse(null);
    }

    /**
     * 只读查询自动入库需要的明细和归一化标识，不读取价格。
     * @param purchaseId 已保存的购买明细ID
     * @return 唯一明细及归一化规则ID；缺失或未归一化时抛参数异常
     */
    public Map<String, Object> receiptPurchase(long purchaseId) {
        var rows = jdbc.queryForList("""
                SELECT p.id,p.order_time,CAST(p.quantity AS TEXT) AS quantity,p.unit,p.sku,
                p.decision,p.is_duplicate,p.dedupe_status,r.id AS normalized_product_id
                FROM purchase_records p JOIN normalization_rules r
                ON (p.normalization_rule=r.rule_code OR p.normalized_name=r.normalized_name)
                WHERE p.id=? AND r.enabled=1
                """, purchaseId);
        if (rows.size() != 1) { throw new IllegalArgumentException("购买商品未唯一归一化，请先确认商品规则"); }
        return rows.get(0);
    }

    /**
     * 查询明细是否已自动入库，无写入副作用。
     * @param purchaseId 购买明细ID
     * @return 已关联的库存ID，未入库时null
     */
    public Long linkedInventory(long purchaseId) {
        return jdbc.queryForList("SELECT inventory_item_id FROM inventory_purchase_links WHERE purchase_record_id=?", Long.class, purchaseId)
                .stream().findFirst().orElse(null);
    }

    /**
     * 只读查询指定闭区间内的历史购买明细及库存兼容信息，不创建库存或购买关联。
     * 同一明细匹配多条规则时保留多行，由应用服务统一排除归一化冲突，禁止任意选择规则。
     *
     * @param startDate 起始购买自然日，ISO日期文本，不允许为空
     * @param endDate 结束购买自然日，ISO日期文本，不允许为空
     * @return 待预览明细，不包含购买金额、商家或订单敏感字段；包含已同步及无效明细供说明原因
     */
    public List<Map<String, Object>> initializationPurchases(String startDate, String endDate) {
        return jdbc.queryForList("""
                SELECT p.id,p.order_time,p.product_name,p.normalized_name,
                COALESCE(NULLIF(trim(r.category),''),NULLIF(trim(p.category),''),'未分类') AS category,
                CAST(p.quantity AS TEXT) AS quantity,p.unit,p.sku,p.decision,p.is_duplicate,p.dedupe_status,
                r.id AS normalized_product_id,r.standard_unit,r.unit_family,
                i.id AS inventory_item_id,i.base_unit,i.current_quantity,i.anchor_at,i.anchor_quantity,
                l.inventory_item_id AS linked_inventory_id
                FROM purchase_records p
                LEFT JOIN normalization_rules r ON r.enabled=1
                  AND (p.normalization_rule=r.rule_code OR p.normalized_name=r.normalized_name)
                LEFT JOIN inventory_items i ON i.normalized_product_id=r.id
                LEFT JOIN inventory_purchase_links l ON l.purchase_record_id=p.id
                WHERE substr(p.order_time,1,10)>=? AND substr(p.order_time,1,10)<=?
                ORDER BY p.order_time DESC,p.id DESC,r.id
                """, startDate, endDate);
    }

    /** @return 全部库存主键，不按价格或购买记录过滤 */
    public List<Long> ids() { return jdbc.queryForList("SELECT id FROM inventory_items ORDER BY id", Long.class); }

    /** @param id 库存主键 @return 库存及设置、估算原始快照，缺失抛参数异常 */
    public Map<String, Object> find(long id) {
        var rows = jdbc.queryForList("""
                SELECT i.*, r.normalized_name, r.standard_unit, r.unit_family, s.manual_reference_quantity, s.manual_reference_unit,
                s.manual_reference_days, s.manual_daily_rate, s.effective_source, s.reminder_lead_days,
                s.snoozed_until, p.auto_daily_rate, p.auto_sample_count, p.purchase_record_count,
                p.sample_label, p.warnings, p.calculated_at
                FROM inventory_items i JOIN normalization_rules r ON r.id=i.normalized_product_id
                JOIN replenishment_settings s ON s.inventory_item_id=i.id
                LEFT JOIN replenishment_predictions p ON p.inventory_item_id=i.id WHERE i.id=?
                """, id);
        if (rows.isEmpty()) { throw new IllegalArgumentException("库存商品不存在"); }
        return rows.get(0);
    }

    /** @param productId 规则ID @return 明细及是否已入库；不返回金额、商家或订单编号 */
    public List<Map<String, Object>> purchases(long productId) {
        return jdbc.queryForList("""
                SELECT p.id, p.order_time, p.quantity, p.unit, p.sku, p.decision,
                p.is_duplicate, p.dedupe_status, CASE WHEN l.purchase_record_id IS NULL THEN 0 ELSE 1 END AS linked
                FROM purchase_records p JOIN normalization_rules r ON r.id=?
                LEFT JOIN inventory_purchase_links l ON l.purchase_record_id=p.id
                WHERE (p.normalization_rule=r.rule_code OR p.normalized_name=r.normalized_name) ORDER BY p.order_time,p.id
                """, productId);
    }

    /** @param productId 已有启用规则ID @param quantity 基础数量，可空 @param unit 基础单位 @param now 上海时间 @return 新库存ID；写三张库存表 */
    public long create(long productId, String quantity, String unit, String now) {
        if (products().stream().noneMatch(p -> ((Number) p.get("id")).longValue() == productId)) {
            throw new IllegalArgumentException("请选择已启用的归一化商品");
        }
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM inventory_items WHERE normalized_product_id=?", Integer.class, productId);
        if (existing != null && existing > 0) { throw new IllegalArgumentException("该商品已有库存，请使用详情增加或修改数量"); }
        jdbc.update("INSERT INTO inventory_items(normalized_product_id,current_quantity,base_unit,quantity_recorded_at,created_at,updated_at,timeline_version) VALUES(?,?,?,?,?,?,1)", productId, quantity, unit, now, now, now);
        Long id = jdbc.queryForObject("SELECT id FROM inventory_items WHERE normalized_product_id=?", Long.class, productId);
        jdbc.update("INSERT INTO replenishment_settings(inventory_item_id,updated_at) VALUES(?,?)", id, now);
        return Objects.requireNonNull(id);
    }

    /** @param id 库存ID @param quantity 兼容数量缓存，可空 @param now 缓存记录时间；真实计算依据独立锚点及入库日期，同时清除延后状态 */
    public void quantity(long id, String quantity, String now) {
        jdbc.update("UPDATE inventory_items SET current_quantity=?,quantity_recorded_at=?,updated_at=? WHERE id=?", quantity, now, now, id);
        jdbc.update("UPDATE replenishment_settings SET snoozed_until=NULL WHERE inventory_item_id=?", id);
    }

    /** @param sql 库存模块内部固定参数化SQL @param arguments 绑定参数 @return 受影响行数；仅供库存应用服务编排扩展表写入 */
    public int update(String sql, Object... arguments) { return jdbc.update(sql, arguments); }

    /** @param key 请求幂等键 @return 已保存请求指纹及库存ID，首次为空 */
    public List<Map<String, Object>> request(String key) {
        return jdbc.queryForList("SELECT fingerprint,inventory_item_id FROM inventory_requests WHERE request_key=?", key);
    }

    /** @param id 库存ID @return 本周期按日期排序的入库数量快照，只读，不重新读取购买价格或规格 */
    public List<Map<String, Object>> receipts(long id) {
        return jdbc.queryForList("SELECT received_date,quantity FROM inventory_receipts WHERE inventory_item_id=? ORDER BY received_date,id", id);
    }

    /** @param id 库存ID @return 已保存的日均消耗分段，只读，空速度表示该段无可靠速度 */
    public List<Map<String, Object>> consumptionRates(long id) {
        return jdbc.queryForList("SELECT effective_date,daily_rate FROM inventory_consumption_rates WHERE inventory_item_id=? ORDER BY effective_date", id);
    }
}
