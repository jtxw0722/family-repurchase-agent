package com.jtxw.familyagent.infrastructure.persistence;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 22:20:17
 * @Description: 库存基础设施迁移入口，在原初始化之后备份已有 SQLite 并幂等创建库存扩展表
 */
@Component
@Order(-2147483647)
public class InventoryMigration implements ApplicationRunner {
    /** SQLite 访问模板，不允许为空。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 数据库访问模板 */
    public InventoryMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 首次扩展前使用 SQLite 一致性快照备份，失败则终止启动；不触碰原业务表。 */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='inventory_items'", Integer.class);
        boolean newInventory = count != null && count == 0;
        boolean needsReceiptColumns = !newInventory && jdbc.queryForList("PRAGMA table_info(inventory_items)").stream()
                .filter(column -> "last_receipt_note".equals(column.get("name")) || "last_received_date".equals(column.get("name"))).count() < 2;
        boolean needsTimeline = newInventory || jdbc.queryForList("PRAGMA table_info(inventory_items)").stream()
                .noneMatch(column -> "timeline_version".equals(column.get("name")));
        if (newInventory || needsReceiptColumns || needsTimeline) {
            for (var database : jdbc.queryForList("PRAGMA database_list")) {
                String file = String.valueOf(database.get("file"));
                if (!file.isBlank() && !file.equals("null") && Files.exists(Path.of(file))) {
                    String suffix = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
                    String backup = file + ".inventory-v1-" + suffix + ".bak";
                    jdbc.execute("VACUUM INTO '" + backup.replace("'", "''") + "'");
                }
            }
        }
        String sql = new ClassPathResource("db/inventory-v1.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) { jdbc.execute(statement); }
        }
        // 逐列判断兼容中断后重启，保留已有数量、人工参数及购买关联。
        String extension = new ClassPathResource("db/inventory-v1.0.1.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String statement : extension.split(";")) {
            if (statement.isBlank()) { continue; }
            String column = statement.contains("last_received_date") ? "last_received_date" : "last_receipt_note";
            boolean exists = jdbc.queryForList("PRAGMA table_info(inventory_items)").stream()
                    .anyMatch(value -> column.equals(value.get("name")));
            if (!exists) { jdbc.execute(statement); }
        }
        String timeline = new ClassPathResource("db/inventory-v1.0.2.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String statement : timeline.split(";")) {
            if (statement.isBlank()) { continue; }
            if (statement.contains("ALTER TABLE")) {
                String column = statement.substring(statement.indexOf("ADD COLUMN") + "ADD COLUMN".length()).trim().split("\\s+")[0];
                if (jdbc.queryForList("PRAGMA table_info(inventory_items)").stream().anyMatch(value -> column.equals(value.get("name")))) { continue; }
            }
            jdbc.execute(statement);
        }
        // 旧版没有完整入库日期和人工确认来源，不能猜测重放；先保留旧基准并提示用户校准或重建。
        jdbc.update("""
                INSERT OR IGNORE INTO inventory_consumption_rates(inventory_item_id,effective_date,daily_rate)
                SELECT i.id,'0001-01-01',CASE WHEN s.effective_source='MANUAL' THEN s.manual_daily_rate ELSE p.auto_daily_rate END
                FROM inventory_items i JOIN replenishment_settings s ON s.inventory_item_id=i.id
                LEFT JOIN replenishment_predictions p ON p.inventory_item_id=i.id
                WHERE i.timeline_version=0 AND (CASE WHEN s.effective_source='MANUAL' THEN s.manual_daily_rate ELSE p.auto_daily_rate END) IS NOT NULL
                """);
        jdbc.update("""
                UPDATE inventory_items SET anchor_quantity=current_quantity,anchor_at=quantity_recorded_at,
                history_rebuild_required=1,timeline_version=1 WHERE timeline_version=0
                """);
    }
}
