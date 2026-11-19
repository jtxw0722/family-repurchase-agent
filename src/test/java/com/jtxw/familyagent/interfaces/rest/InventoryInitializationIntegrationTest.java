package com.jtxw.familyagent.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtxw.familyagent.application.InventoryApplicationService;
import com.jtxw.familyagent.application.InventoryCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 22:20:17
 * @Description: 库存同步完整REST与隔离SQLite集成测试，验证日期预览、勾选、重置后重同步、幂等及事务回滚，不访问真实家庭库
 */
@SpringBootTest(properties = {"spring.config.import=optional:classpath:inventory-test-no-env.properties",
        "family-agent.normalization.llm.enabled=false", "family-agent.parse-order-image.model.enabled=false",
        "family-agent.parse-order-image.local-ocr.enabled=false"})
@AutoConfigureMockMvc
class InventoryInitializationIntegrationTest {
    /** 隔离数据库连接，仅位于忽略的target目录，非空。 */
    private static final String DATABASE_URL = "jdbc:sqlite:./target/inventory-initialization-" + UUID.randomUUID() + ".sqlite";
    /** 上海自然日时区，保证默认一年测试不依赖宿主时区。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 测试商品规则ID，均为隔离库专用正整数。 */
    private static final long FOOD_ID = 91001;
    /** 第二种测试商品规则ID，用于验证未勾选商品不进入库存。 */
    private static final long CLEANER_ID = 91002;
    /** 真实MVC测试入口，非空。 */
    @Autowired private MockMvc mvc;
    /** 仅操作隔离数据库的夹具及断言模板，非空。 */
    @Autowired private JdbcTemplate jdbc;
    /** JSON协议编解码器，非空。 */
    @Autowired private ObjectMapper json;
    /** 复用自动入库验证与初始化共用去重关系，非空。 */
    @Autowired private InventoryApplicationService inventory;

    /** 配置隔离数据库，不改动应用的真实数据库地址。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
    }

    /** 创建两种有明确标准单位与品类的归一化商品，并只清理本测试库数据。 */
    @BeforeEach
    void fixtures() {
        for (String table : List.of("inventory_receipts", "inventory_consumption_rates", "inventory_requests", "inventory_purchase_links", "replenishment_predictions",
                "replenishment_settings", "inventory_items", "purchase_records")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("""
                INSERT OR IGNORE INTO normalization_rules(id,rule_code,normalized_name,category,standard_unit,unit_family,created_at,updated_at)
                VALUES(91001,'init_food','初始化猫粮','宠物用品','kg','weight','2026-01-01','2026-01-01'),
                (91002,'init_cleaner','初始化洗衣液','日用品','L','volume','2026-01-01','2026-01-01')
                """);
    }

    /** 默认一年且包含起止日，预览不写库存、预测、请求或价格，不泄漏金额字段。 */
    @Test
    void defaultYearPreviewIsInclusiveAndReadOnly() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        seed(1, FOOD_ID, today.minusYears(1), "4.5", "kg", null);
        seed(2, CLEANER_ID, today, "2", "L", null);
        seed(3, FOOD_ID, today.minusYears(1).minusDays(1), "1", "kg", null);
        seed(4, FOOD_ID, today.plusDays(1), "1", "kg", null);
        var originalPurchases = jdbc.queryForList("SELECT * FROM purchase_records ORDER BY id");
        var preview = preview("");
        assertThat(preview.get("startDate").asText()).isEqualTo(today.minusYears(1).toString());
        assertThat(preview.get("endDate").asText()).isEqualTo(today.toString());
        assertThat(preview.get("records")).hasSize(2);
        assertThat(candidate(preview, 1).get("selectable").asBoolean()).isTrue();
        assertThat(candidate(preview, 2).get("category").asText()).isEqualTo("日用品");
        assertThat(preview.toString()).doesNotContain("total_amount", "paid_amount", "unit_price", "source_text");
        assertThat(jdbc.queryForList("SELECT * FROM purchase_records ORDER BY id")).isEqualTo(originalPurchases);
        assertEmptyInventory();
    }

    /** 查询自定义起止日闭区间，只列出区间明细；不将区间外记录混入候选。 */
    @Test
    void customPeriodOnlyIncludesItsBoundaries() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        seed(1, FOOD_ID, today.minusDays(10), "4.5", "kg", null);
        seed(2, FOOD_ID, today.minusDays(3), "8", "kg", null);
        seed(3, FOOD_ID, today.minusDays(11), "1", "kg", null);
        seed(4, FOOD_ID, today.minusDays(2), "1", "kg", null);
        var result = preview("?startDate=" + today.minusDays(10) + "&endDate=" + today.minusDays(3));
        assertThat(result.get("records")).hasSize(2);
        assertThat(candidate(result, 1).get("inventoryQuantity").decimalValue()).isEqualByComparingTo("4500");
        assertThat(candidate(result, 2).get("inventoryQuantity").decimalValue()).isEqualByComparingTo("8000");
    }

    /** 同步预览与库存列表展示同一规则单位，保留原字段兼容旧客户端。 */
    @Test
    void previewUsesTheSameStandardUnitsAsInventoryDisplay() throws Exception {
        seed(1, FOOD_ID, LocalDate.now(ZONE), "4.5", "kg", null);
        seed(2, CLEANER_ID, LocalDate.now(ZONE), "2", "L", null);
        var result=preview("");
        assertThat(candidate(result,1).get("displayUnit").asText()).isEqualTo("kg");
        assertThat(candidate(result,1).get("displayInventoryQuantity").decimalValue()).isEqualByComparingTo("4.5");
        assertThat(candidate(result,1).get("inventoryQuantity").decimalValue()).isEqualByComparingTo("4500");
        assertThat(candidate(result,2).get("displayUnit").asText()).isEqualTo("L");
        assertThat(candidate(result,2).get("displayInventoryQuantity").decimalValue()).isEqualByComparingTo("2");
        assertEmptyInventory();
    }

    /** 只同步明确选中的明细；同商品未选明细及其他商品不入库，原购买数据完全保留。 */
    @Test
    void onlyCheckedRecordsBecomeInventory() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        seed(1, FOOD_ID, today.minusDays(10), "4.5", "kg", null);
        seed(2, FOOD_ID, today.minusDays(3), "8", "kg", null);
        seed(3, CLEANER_ID, today, "2", "L", null);
        var originalPurchases = jdbc.queryForList("SELECT * FROM purchase_records ORDER BY id");
        var result = confirm(command("only-one", List.of(1L)));
        assertThat(result.get("synchronizedCount").asInt()).isEqualTo(1);
        assertThat(stock(FOOD_ID)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_items", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT purchase_record_id FROM inventory_purchase_links", Long.class)).containsExactly(1L);
        assertThat(jdbc.queryForList("SELECT * FROM purchase_records ORDER BY id")).isEqualTo(originalPurchases);
    }

    /** 包装和g/kg折为同一库存档案；重复ID、重复请求和换键重复同步均不重复加量。 */
    @Test
    void packagingAndIdempotencyShareOneProductInventory() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        seed(1, FOOD_ID, today.minusDays(10), "2", "袋", "4.5kg/袋");
        seed(2, FOOD_ID, today.minusDays(3), "8000", "g", null);
        var result = confirm(command("repeat", List.of(2L, 1L, 1L)));
        assertThat(result.get("synchronizedCount").asInt()).isEqualTo(2);
        assertThat(stock(FOOD_ID)).isEqualByComparingTo("4142.857142857142");
        assertThat(confirm(command("repeat", List.of(1L, 2L))).get("alreadySyncedCount").asInt()).isEqualTo(2);
        assertThat(confirm(command("new-key", List.of(1L, 2L))).get("synchronizedCount").asInt()).isZero();
        assertThat(stock(FOOD_ID)).isEqualByComparingTo("4142.857142857142");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_items", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links", Integer.class)).isEqualTo(2);
        assertThat(candidate(preview(""), 1).get("selectable").asBoolean()).isFalse();
    }

    /** 重置只释放当前商品明细，保留其他商品、消耗参数、自动估算和原购买价格。 */
    @Test
    void resetOnlyReleasesTheCurrentProductAndPreservesConsumptionAndPurchases() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        seed(1, FOOD_ID, today.minusDays(26), "4.5", "kg", null);
        seed(2, FOOD_ID, today, "8", "kg", null);
        seed(3, CLEANER_ID, today, "2", "L", null);
        var originalPurchases = jdbc.queryForList("SELECT * FROM purchase_records ORDER BY id");
        var originalRules = jdbc.queryForList("SELECT * FROM normalization_rules ORDER BY id");
        confirm(command("before-reset", List.of(1L, 2L, 3L)));
        long inventoryId = inventoryId(FOOD_ID);
        inventoryAction(inventoryId, "settings", Map.of("requestKey", "manual-settings", "source", "MANUAL",
                "referenceQuantity", "4.5", "referenceUnit", "kg", "referenceDays", "14", "leadDays", 5));
        var originalManual = jdbc.queryForMap("""
                SELECT manual_reference_quantity,manual_reference_unit,manual_reference_days,manual_daily_rate,
                effective_source,reminder_lead_days FROM replenishment_settings WHERE inventory_item_id=?
                """, inventoryId);
        var originalAutomatic = jdbc.queryForMap("""
                SELECT auto_daily_rate,auto_sample_count,purchase_record_count,sample_label
                FROM replenishment_predictions WHERE inventory_item_id=?
                """, inventoryId);

        var result = inventoryAction(inventoryId, "reset", Map.of("requestKey", "reset-product"));

        assertThat(result.get("estimated_quantity").decimalValue()).isEqualByComparingTo("0");
        assertThat(result.get("base_unit").asText()).isEqualTo("g");
        assertThat(result.get("effective_source").asText()).isEqualTo("MANUAL");
        assertThat(stock(CLEANER_ID)).isEqualByComparingTo("2000");
        var candidates = preview("");
        assertThat(candidate(candidates, 1).get("linked").asBoolean()).isFalse();
        assertThat(candidate(candidates, 1).get("selectable").asBoolean()).isTrue();
        assertThat(candidate(candidates, 2).get("selectable").asBoolean()).isTrue();
        assertThat(candidate(candidates, 3).get("linked").asBoolean()).isTrue();
        assertThat(candidate(candidates, 3).get("selectable").asBoolean()).isFalse();
        assertThat(jdbc.queryForMap("""
                SELECT manual_reference_quantity,manual_reference_unit,manual_reference_days,manual_daily_rate,
                effective_source,reminder_lead_days FROM replenishment_settings WHERE inventory_item_id=?
                """, inventoryId)).isEqualTo(originalManual);
        assertThat(jdbc.queryForMap("""
                SELECT auto_daily_rate,auto_sample_count,purchase_record_count,sample_label
                FROM replenishment_predictions WHERE inventory_item_id=?
                """, inventoryId)).isEqualTo(originalAutomatic);
        assertThat(jdbc.queryForList("SELECT * FROM purchase_records ORDER BY id")).isEqualTo(originalPurchases);
        assertThat(jdbc.queryForList("SELECT * FROM normalization_rules ORDER BY id")).isEqualTo(originalRules);
    }

    /** 重同步兼容原自动入库键，旧同步及重置请求重放均不能再次加量或清空后来库存。 */
    @Test
    void resetAllowsResynchronizationWhileOldRequestsRemainIdempotent() throws Exception {
        seed(1, FOOD_ID, LocalDate.now(ZONE), "4.5", "kg", null);
        inventory.receivePurchase(1);
        long inventoryId = inventoryId(FOOD_ID);
        confirm(command("old-sync", List.of(1L)));
        var reset = Map.<String, Object>of("requestKey", "reset-once");
        inventoryAction(inventoryId, "reset", reset);
        assertThat(stock(FOOD_ID)).isZero();
        assertThat(confirm(command("old-sync", List.of(1L))).get("synchronizedCount").asInt()).isZero();
        assertThat(stock(FOOD_ID)).isZero();

        assertThat(confirm(command("new-sync", List.of(1L))).get("synchronizedCount").asInt()).isEqualTo(1);
        assertThat(stock(FOOD_ID)).isEqualByComparingTo("4500");
        inventoryAction(inventoryId, "reset", reset);
        assertThat(stock(FOOD_ID)).isEqualByComparingTo("4500");
        assertThat(candidate(preview(""), 1).get("linked").asBoolean()).isTrue();
        assertThat(confirm(command("new-sync", List.of(1L))).get("synchronizedCount").asInt()).isZero();
        assertThat(confirm(command("another-sync", List.of(1L))).get("alreadySyncedCount").asInt()).isEqualTo(1);
        inventory.receivePurchase(1);
        assertThat(stock(FOOD_ID)).isEqualByComparingTo("4500");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links", Integer.class)).isEqualTo(1);
    }

    /** 原有未知与数量校正不释放关联，只有用户明确重置才将已同步记录重新开放。 */
    @Test
    void unknownAndZeroCorrectionsAreDistinctFromExplicitReset() throws Exception {
        seed(1, FOOD_ID, LocalDate.now(ZONE), "4.5", "kg", null);
        inventory.receivePurchase(1);
        long inventoryId = inventoryId(FOOD_ID);
        var unknown = new LinkedHashMap<String, Object>();
        unknown.put("requestKey", "unknown-stock");
        unknown.put("quantity", null);
        unknown.put("unit", "g");
        assertThat(inventoryAction(inventoryId, "set", unknown).get("estimated_quantity").isNull()).isTrue();
        assertThat(candidate(preview(""), 1).get("linked").asBoolean()).isTrue();
        inventoryAction(inventoryId, "set", Map.of("requestKey", "zero-correction", "quantity", "0", "unit", "g"));
        assertThat(stock(FOOD_ID)).isZero();
        assertThat(candidate(preview(""), 1).get("linked").asBoolean()).isTrue();
        inventoryAction(inventoryId, "reset", Map.of("requestKey", "explicit-reset"));
        assertThat(candidate(preview(""), 1).get("selectable").asBoolean()).isTrue();
    }

    /** 关联释放失败时，数量、延后提醒及幂等占位必须与关联一起回滚。 */
    @Test
    void resetFailureRollsBackQuantityLinksSnoozeAndRequest() throws Exception {
        seed(1, FOOD_ID, LocalDate.now(ZONE), "4.5", "kg", null);
        inventory.receivePurchase(1);
        long inventoryId = inventoryId(FOOD_ID);
        String snoozedUntil = LocalDate.now(ZONE).plusDays(7).toString();
        jdbc.update("UPDATE replenishment_settings SET snoozed_until=? WHERE inventory_item_id=?", snoozedUntil, inventoryId);
        jdbc.execute("""
                CREATE TRIGGER fail_inventory_reset BEFORE DELETE ON inventory_purchase_links
                BEGIN SELECT RAISE(ABORT, 'reset blocked for test'); END
                """);
        try {
            assertThatThrownBy(() -> inventory.execute(inventoryId, "reset", new InventoryCommand(
                    "rollback-reset", null, null, null, null, null, null, null, null, null)))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER fail_inventory_reset");
        }
        assertThat(stock(FOOD_ID)).isEqualByComparingTo("4500");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT snoozed_until FROM replenishment_settings WHERE inventory_item_id=?", String.class, inventoryId))
                .isEqualTo(snoozedUntil);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_requests WHERE request_key='rollback-reset'", Integer.class)).isZero();
    }

    /** 自动入库过的明细显示已同步；初始化与真实样本共用去重关系及库存档案。 */
    @Test
    void automaticReceiptIsNotAddedAgainByInitialization() throws Exception {
        seed(1, FOOD_ID, LocalDate.now(ZONE), "4.5", "kg", null);
        inventory.receivePurchase(1);
        var row = candidate(preview(""), 1);
        assertThat(row.get("linked").asBoolean()).isTrue();
        assertThat(row.get("reason").asText()).contains("已同步");
        assertThat(confirm(command("existing-auto", List.of(1L))).get("alreadySyncedCount").asInt()).isEqualTo(1);
        assertThat(stock(FOOD_ID)).isEqualByComparingTo("4500");
    }

    /** 重复及未归一化明细隐藏，其他无效状态、单位、数量和日期保留原因并禁止勾选。 */
    @Test
    void invalidCandidatesAreDisabledWithReasons() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        for (int index = 1; index <= 8; index++) {
            seed(index, FOOD_ID, today, "1", "kg", null);
        }
        jdbc.update("UPDATE purchase_records SET decision='exclude' WHERE id=1");
        jdbc.update("UPDATE purchase_records SET is_duplicate=1,dedupe_status='duplicate' WHERE id=2");
        jdbc.update("UPDATE purchase_records SET unit='袋' WHERE id=3");
        jdbc.update("UPDATE purchase_records SET unit='L' WHERE id=4");
        jdbc.update("UPDATE purchase_records SET quantity=0 WHERE id=5");
        jdbc.update("UPDATE purchase_records SET quantity=NULL WHERE id=6");
        jdbc.update("UPDATE purchase_records SET normalized_name='未归一化商品',normalization_rule='legacy_fallback' WHERE id=7");
        jdbc.update("UPDATE purchase_records SET order_time='2026-02-30' WHERE id=8");
        var result = preview("");
        assertThat(result.get("records")).hasSize(6);
        for (long id : List.of(1L, 3L, 4L, 5L, 6L, 8L)) {
            var row = candidate(result, id);
            assertThat(row.get("selectable").asBoolean()).isFalse();
            assertThat(row.get("reason").asText()).isNotBlank();
        }
        assertEmptyInventory();
    }

    /** 非空原始标题不代表已归一化，空白名称及未命中规则的明细均不展示。 */
    @Test
    void hidesMissingOrFallbackNormalization() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        for (int id = 1; id <= 5; id++) {
            seed(id, FOOD_ID, today, "1", "kg", null);
        }
        jdbc.update("UPDATE purchase_records SET normalized_name='' WHERE id=2");
        jdbc.update("UPDATE purchase_records SET normalized_name='   ' WHERE id=3");
        jdbc.update("UPDATE purchase_records SET normalized_name='原始衣服标题',normalization_rule='legacy_fallback' WHERE id=4");
        jdbc.update("UPDATE purchase_records SET normalized_name='未匹配标题',normalization_rule='manual_input' WHERE id=5");
        var result = preview("");
        assertThat(result.get("records")).hasSize(1);
        assertThat(candidate(result, 1).get("normalizedName").asText()).isEqualTo("初始化猫粮");
        assertBadConfirm(command("hidden-fallback", List.of(4L)));
        assertEmptyInventory();
    }

    /** 两个去重标志任一不明确非重复就隐藏，同商品不同规格的有效购买不能被误合并。 */
    @Test
    void hidesDuplicateMarkersButKeepsDistinctValidPurchases() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        for (int id = 1; id <= 6; id++) {
            seed(id, FOOD_ID, today, "1", "kg", id == 6 ? "另一包装规格" : null);
        }
        jdbc.update("UPDATE purchase_records SET is_duplicate=1 WHERE id=2");
        jdbc.update("UPDATE purchase_records SET dedupe_status='duplicate' WHERE id=3");
        jdbc.update("UPDATE purchase_records SET dedupe_status=NULL WHERE id=4");
        jdbc.update("UPDATE purchase_records SET dedupe_status='pending' WHERE id=5");
        var result = preview("");
        assertThat(result.get("records")).hasSize(2);
        assertThat(candidate(result, 1).get("selectable").asBoolean()).isTrue();
        assertThat(candidate(result, 6).get("selectable").asBoolean()).isTrue();
        assertBadConfirm(command("hidden-duplicate", List.of(1L, 3L)));
        assertEmptyInventory();
    }

    /** 预览后被标为重复或失去归一化的明细，确认时也必须拒绝并回滚整批。 */
    @Test
    void newlyHiddenRecordsCannotBypassConfirmationValidation() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        seed(1, FOOD_ID, today, "1", "kg", null);
        seed(2, FOOD_ID, today, "1", "kg", null);
        assertThat(preview("").get("records")).hasSize(2);
        jdbc.update("UPDATE purchase_records SET is_duplicate=1,dedupe_status='duplicate' WHERE id=2");
        assertBadConfirm(command("changed-to-duplicate", List.of(1L, 2L)));
        assertEmptyInventory();
        jdbc.update("UPDATE purchase_records SET is_duplicate=0,dedupe_status='unique',normalized_name='' WHERE id=2");
        assertBadConfirm(command("changed-to-unnormalized", List.of(1L, 2L)));
        assertEmptyInventory();
    }

    /** 整批包含不可靠规格时回滚此前已加量的明细，防止部分初始化。 */
    @Test
    void invalidSelectionRollsBackTheEntireBatch() throws Exception {
        seed(1, FOOD_ID, LocalDate.now(ZONE), "4.5", "kg", null);
        seed(2, FOOD_ID, LocalDate.now(ZONE), "1", "袋", null);
        assertBadConfirm(command("rollback", List.of(1L, 2L)));
        assertEmptyInventory();
    }

    /** 预览后的数量单位或有效状态变化必须在确认时重查，不能信任旧页面。 */
    @Test
    void confirmationRevalidatesChangedPurchase() throws Exception {
        seed(1, FOOD_ID, LocalDate.now(ZONE), "4.5", "kg", null);
        assertThat(candidate(preview(""), 1).get("selectable").asBoolean()).isTrue();
        jdbc.update("UPDATE purchase_records SET unit='L' WHERE id=1");
        assertBadConfirm(command("stale-unit", List.of(1L)));
        assertEmptyInventory();
    }

    /** 区间外ID及缺失ID不能借确认接口绕过预览，也不自动同步区间内其他记录。 */
    @Test
    void outOfRangeOrMissingIdCannotBeSynchronized() throws Exception {
        seed(1, FOOD_ID, LocalDate.now(ZONE).minusYears(1).minusDays(1), "4.5", "kg", null);
        seed(2, FOOD_ID, LocalDate.now(ZONE), "8", "kg", null);
        assertBadConfirm(command("outside", List.of(1L, 2L)));
        assertBadConfirm(command("missing", List.of(2L, 99999L)));
        assertEmptyInventory();
    }

    /** 请求键不能复用于其他选择，日期非法及空选择返回400，不产生写入。 */
    @Test
    void validatesRangeSelectionAndRequestKey() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        seed(1, FOOD_ID, today, "4.5", "kg", null);
        mvc.perform(get("/api/inventory/initialization/preview").param("startDate", today.toString())
                .param("endDate", today.minusDays(1).toString())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/inventory/initialization/preview").param("endDate", today.plusDays(1).toString()))
                .andExpect(status().isBadRequest());
        assertBadConfirm(command("empty", List.of()));
        assertBadConfirm(command("invalid-id", List.of(-1L)));
        assertBadConfirm(command("", List.of(1L)));
        assertEmptyInventory();
        confirm(command("used-key", List.of(1L)));
        assertBadConfirm(command("used-key", List.of(2L)));
        assertThat(stock(FOOD_ID)).isEqualByComparingTo("4500");
    }

    /** 归一化冲突不擅自挑选规则，不重复展示同一明细，不创建错误商品库存。 */
    @Test
    void ambiguousNormalizationCannotBeSynchronized() throws Exception {
        seed(1, FOOD_ID, LocalDate.now(ZONE), "4.5", "kg", null);
        jdbc.update("UPDATE purchase_records SET normalized_name='初始化洗衣液' WHERE id=1");
        var result = preview("");
        assertThat(result.get("records")).isEmpty();
        assertBadConfirm(command("ambiguous", List.of(1L)));
        assertEmptyInventory();
    }

    /** 已有库存按基础单位累加；未知库存需先确认，不得猜测为零。 */
    @Test
    void addsToExistingStockButRejectsUnknownInventory() throws Exception {
        seed(1, FOOD_ID, LocalDate.now(ZONE), "4.5", "kg", null);
        mvc.perform(post("/api/inventory").contentType("application/json").content(json.writeValueAsBytes(
                Map.of("requestKey", "manual", "normalizedProductId", FOOD_ID, "quantity", "1", "unit", "kg"))))
                .andExpect(status().isOk());
        confirm(command("add-existing", List.of(1L)));
        assertThat(stock(FOOD_ID)).isEqualByComparingTo("5500");
        seed(2, FOOD_ID, LocalDate.now(ZONE), "1", "kg", null);
        var unknown = new LinkedHashMap<String, Object>();
        unknown.put("requestKey", "confirm-unknown");
        unknown.put("quantity", null);
        unknown.put("unit", "g");
        inventoryAction(inventoryId(FOOD_ID), "set", unknown);
        assertThat(candidate(preview(""), 2).get("reason").asText()).contains("库存未知");
        assertBadConfirm(command("unknown", List.of(2L)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links", Integer.class)).isEqualTo(1);
    }

    /**
     * 调用隔离库的库存命令REST入口，执行实际事务写入。
     * @param inventoryId 已存在的库存档案ID
     * @param operation 库存操作名
     * @param parameters 含requestKey的命令参数
     * @return 成功响应；请求或编码失败时测试直接失败
     */
    private JsonNode inventoryAction(long inventoryId, String operation, Map<String, Object> parameters) throws Exception {
        return json.readTree(mvc.perform(post("/api/inventory/" + inventoryId + "/" + operation).contentType("application/json")
                .content(json.writeValueAsBytes(parameters))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** @param productId 测试规则ID @return 对应的唯一库存ID；档案不存在时测试失败 */
    private long inventoryId(long productId) {
        return jdbc.queryForObject("SELECT id FROM inventory_items WHERE normalized_product_id=?", Long.class, productId);
    }

    /** 构造标准化购买明细，金额仅用于确认原价格数据不被修改。 */
    private void seed(long id, long productId, LocalDate day, String quantity, String unit, String specification) {
        jdbc.update("""
                INSERT INTO purchase_records(id,product_name,normalized_name,normalization_rule,order_time,
                quantity,unit,sku,category,total_amount,unit_price,decision,is_duplicate,dedupe_status,created_at)
                SELECT ?,normalized_name,normalized_name,rule_code,?,?,?,?,category,100,20,'include',0,'unique',?
                FROM normalization_rules WHERE id=?
                """, id, day.toString(), quantity, unit, specification, day.toString(), productId);
    }

    /** @return 默认一年确认命令，仅包含显式选择的ID */
    private Map<String, Object> command(String key, List<Long> ids) {
        LocalDate today = LocalDate.now(ZONE);
        return Map.of("requestKey", key, "startDate", today.minusYears(1).toString(), "endDate", today.toString(), "purchaseRecordIds", ids);
    }

    /** @return 只读预览REST响应，非法请求由调用方单独断言 */
    private JsonNode preview(String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/inventory/initialization/preview" + query)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** @return 成功确认REST响应；请求必须已明确选择明细 */
    private JsonNode confirm(Map<String, Object> command) throws Exception {
        return json.readTree(mvc.perform(post("/api/inventory/initialization/confirm").contentType("application/json")
                .content(json.writeValueAsBytes(command))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 验证非法初始化请求返回400，不隐藏服务异常。 */
    private void assertBadConfirm(Map<String, Object> command) throws Exception {
        mvc.perform(post("/api/inventory/initialization/confirm").contentType("application/json")
                .content(json.writeValueAsBytes(command))).andExpect(status().isBadRequest());
    }

    /** @return 指定购买ID的预览候选，缺失时测试失败 */
    private JsonNode candidate(JsonNode preview, long purchaseId) {
        for (var row : preview.get("records")) {
            if (row.get("purchaseRecordId").asLong() == purchaseId) {
                return row;
            }
        }
        throw new AssertionError("预览缺少购买明细" + purchaseId);
    }

    /** @return 记录库存基准量，用BigDecimal断言避免double精度干扰 */
    private BigDecimal stock(long productId) {
        return new BigDecimal(jdbc.queryForObject("SELECT current_quantity FROM inventory_items WHERE normalized_product_id=?", String.class, productId));
    }

    /** 预览或回滚后所有库存扩展表均应为空，包含幂等请求与预测。 */
    private void assertEmptyInventory() {
        for (String table : List.of("inventory_items", "inventory_purchase_links", "inventory_requests", "replenishment_settings", "replenishment_predictions")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }
}
