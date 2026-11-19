package com.jtxw.familyagent.infrastructure.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 22:20:17
 * @Description: 库存迁移集成测试，验证旧SQLite原始购买数据、归一化规则和迁移前备份均不被扩展表修改
 */
class InventoryMigrationTest {
    /** JUnit隔离临时目录，不包含真实数据库。 */
    @TempDir Path directory;

    /** 重复迁移仅产生一份可读快照，942条原始记录及规则完整保留。 */
    @Test void backupAndIdempotentMigration() throws Exception {
        Path database=directory.resolve("household.sqlite");
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+database));
        new DatabaseInitializer(jdbc).initialize();
        Integer rules=jdbc.queryForObject("SELECT count(*) FROM normalization_rules",Integer.class);
        for (int index=0; index<942; index++) {
            jdbc.update("INSERT INTO purchase_records(product_name,normalized_name,quantity,unit,total_amount,created_at) VALUES('原始商品','历史商品',4.5,'kg',123.45,'2026-01-01')");
        }
        var migration=new InventoryMigration(jdbc);
        migration.run(new DefaultApplicationArguments());
        migration.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_records",Integer.class)).isEqualTo(942);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_records WHERE total_amount=123.45",Integer.class)).isEqualTo(942);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM normalization_rules",Integer.class)).isEqualTo(rules);
        try (var paths=Files.list(directory)) {
            var backups=paths.filter(p->p.toString().endsWith(".bak")).toList();
            assertThat(backups).hasSize(1);
            JdbcTemplate snapshot=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+backups.get(0)));
            assertThat(snapshot.queryForObject("SELECT count(*) FROM purchase_records",Integer.class)).isEqualTo(942);
            assertThat(snapshot.queryForObject("SELECT count(*) FROM sqlite_master WHERE name='inventory_items'",Integer.class)).isZero();
        }
    }
    /** 从V1已有库存升级仅补元数据列，不清空数量、设置或购买关联，并先生成旧库快照。 */
    @Test void upgradesExistingInventoryWithoutRebuildingIt() throws Exception {
        Path database=directory.resolve("existing-v1.sqlite");
        JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+database));
        new DatabaseInitializer(jdbc).initialize();
        String original=new org.springframework.core.io.ClassPathResource("db/inventory-v1.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        for (String statement:original.split(";")) { if(!statement.isBlank()) jdbc.execute(statement); }
        jdbc.update("INSERT INTO inventory_items(id,normalized_product_id,current_quantity,base_unit,quantity_recorded_at,created_at,updated_at) VALUES(77,1,'9000','g','2026-11-01','2026-11-01','2026-11-01')");
        jdbc.update("INSERT INTO replenishment_settings(inventory_item_id,effective_source,manual_daily_rate,updated_at) VALUES(77,'MANUAL','300','2026-11-01')");
        jdbc.update("INSERT INTO inventory_purchase_links(purchase_record_id,inventory_item_id,added_quantity,created_at) VALUES(123,77,'9000','2026-11-01')");
        var migration=new InventoryMigration(jdbc); migration.run(new DefaultApplicationArguments()); migration.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT current_quantity FROM inventory_items WHERE id=77",String.class)).isEqualTo("9000");
        assertThat(jdbc.queryForObject("SELECT manual_daily_rate FROM replenishment_settings WHERE inventory_item_id=77",String.class)).isEqualTo("300");
        assertThat(jdbc.queryForObject("SELECT inventory_item_id FROM inventory_purchase_links WHERE purchase_record_id=123",Integer.class)).isEqualTo(77);
        assertThat(jdbc.queryForMap("SELECT last_received_date,last_receipt_note FROM inventory_items WHERE id=77").values()).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT anchor_quantity FROM inventory_items WHERE id=77", String.class)).isEqualTo("9000");
        assertThat(jdbc.queryForObject("SELECT anchor_at FROM inventory_items WHERE id=77", String.class)).isEqualTo("2026-11-01");
        assertThat(jdbc.queryForObject("SELECT history_rebuild_required FROM inventory_items WHERE id=77", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_receipts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT daily_rate FROM inventory_consumption_rates WHERE inventory_item_id=77", String.class)).isEqualTo("300");
        try(var paths=Files.list(directory)) { assertThat(paths.filter(p->p.toString().endsWith(".bak")).count()).isEqualTo(1); }
    }

}
