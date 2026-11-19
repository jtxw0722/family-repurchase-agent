package com.jtxw.familyagent.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 22:20:17
 * @Description: 库存时间线REST与隔离SQLite集成测试，验证购买日起算、乱序同步、人工数量锚点及只读同步估算，不访问真实家庭数据库
 */
@SpringBootTest(properties = {"spring.config.import=optional:classpath:inventory-test-no-env.properties",
        "family-agent.normalization.llm.enabled=false", "family-agent.parse-order-image.model.enabled=false",
        "family-agent.parse-order-image.local-ocr.enabled=false"})
@AutoConfigureMockMvc
class InventoryTimelineIntegrationTest {
    /** 独立测试数据库连接，始终写入忽略的target目录，非空且每个测试类唯一。 */
    private static final String DATABASE_URL = "jdbc:sqlite:./target/inventory-timeline-" + UUID.randomUUID() + ".sqlite";
    /** 上海自然日时区，购买日期、入库日期及预期消耗均采用该时区。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 第一种隔离测试商品规则ID，标准单位为kg，基础单位为g。 */
    private static final long FIRST_PRODUCT_ID = 92001;
    /** 第二种隔离测试商品规则ID，用于比较批量与分批同步结果，标准单位为kg。 */
    private static final long SECOND_PRODUCT_ID = 92002;
    /** 仅用于比较只读估算前后数据的表，列表固定且不包含外部输入。 */
    private static final List<String> SNAPSHOT_TABLES = List.of("inventory_items", "inventory_receipts",
            "inventory_consumption_rates", "inventory_requests", "inventory_purchase_links",
            "replenishment_settings", "replenishment_predictions", "purchase_records", "normalization_rules");
    /** 完整REST请求入口，非空。 */
    @Autowired private MockMvc mvc;
    /** 隔离数据库夹具与数据完整性断言入口，非空。 */
    @Autowired private JdbcTemplate jdbc;
    /** JSON请求及响应编解码器，非空。 */
    @Autowired private ObjectMapper json;
    /** 本测试用例开始时的上海自然日，BeforeEach赋值后非空，避免同一用例重复取时。 */
    private LocalDate today;

    /**
     * 将应用上下文指向独立SQLite测试库，不读取或覆盖真实家庭库存。
     * @param registry Spring动态配置注册入口，非空
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE_URL);
    }

    /** 清理本测试库的输入快照、速率、库存和购买，再创建两种独立归一化商品。 */
    @BeforeEach
    void fixtures() {
        today = LocalDate.now(ZONE);
        for (String table : List.of("inventory_receipts", "inventory_consumption_rates", "inventory_requests",
                "inventory_purchase_links", "replenishment_predictions", "replenishment_settings",
                "inventory_items", "purchase_records")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("""
                INSERT OR IGNORE INTO normalization_rules(id,rule_code,normalized_name,category,standard_unit,unit_family,created_at,updated_at)
                VALUES(92001,'timeline_food_first','时间线猫粮甲','宠物用品','kg','weight','2026-01-01','2026-01-01'),
                (92002,'timeline_food_second','时间线猫粮乙','宠物用品','kg','weight','2026-01-01','2026-01-01')
                """);
    }

    /** 同样两次历史购买，批量同步与先新后旧的分批同步必须得到相同的今日剩余量。 */
    @Test
    void batchAndOutOfOrderSynchronizationProduceTheSameQuantity() throws Exception {
        long firstInventory = prepareManualRate(FIRST_PRODUCT_ID);
        long secondInventory = prepareManualRate(SECOND_PRODUCT_ID);
        seed(1, FIRST_PRODUCT_ID, today.minusDays(17), "5");
        seed(2, FIRST_PRODUCT_ID, today.minusDays(8), "5");
        seed(3, SECOND_PRODUCT_ID, today.minusDays(17), "5");
        seed(4, SECOND_PRODUCT_ID, today.minusDays(8), "5");
        var originalPurchases = jdbc.queryForList("SELECT * FROM purchase_records ORDER BY id");

        assertThat(confirm("whole-batch", List.of(1L, 2L)).get("synchronizedCount").asInt()).isEqualTo(2);
        confirm("newer-first", List.of(4L));
        assertQuantity(detail(secondInventory), "3400");
        confirm("older-later", List.of(3L));

        assertQuantity(detail(firstInventory), "6600");
        assertQuantity(detail(secondInventory), "6600");
        assertThat(detail(firstInventory).get("received_quantity").decimalValue()).isEqualByComparingTo("10000");
        assertThat(detail(secondInventory).get("received_quantity").decimalValue()).isEqualByComparingTo("10000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT * FROM purchase_records ORDER BY id")).isEqualTo(originalPurchases);
    }

    /** 重复选择、同键重放和换键再次同步都不能重复入库，也不能重复扣除历史消耗。 */
    @Test
    void repeatedRequestsDoNotIncreaseOrConsumeTheSamePurchasesAgain() throws Exception {
        long inventoryId = prepareManualRate(FIRST_PRODUCT_ID);
        seed(1, FIRST_PRODUCT_ID, today.minusDays(17), "5");
        seed(2, FIRST_PRODUCT_ID, today.minusDays(8), "5");

        confirm("first-sync", List.of(2L, 1L, 1L));
        assertThat(confirm("first-sync", List.of(1L, 2L)).get("alreadySyncedCount").asInt()).isEqualTo(2);
        assertThat(confirm("new-request-same-records", List.of(2L, 1L)).get("synchronizedCount").asInt()).isZero();

        var current = detail(inventoryId);
        assertQuantity(current, "6600");
        assertThat(current.get("received_quantity").decimalValue()).isEqualByComparingTo("10000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links", Integer.class)).isEqualTo(2);
    }

    /** 过去入库没有速度时保持待估算；首次补充4.5kg/14天后按原入库日回算今日剩余。 */
    @Test
    void firstManualRateRebuildsPreviouslyPendingHistoricalReceipt() throws Exception {
        var initial = receipt(FIRST_PRODUCT_ID, "4.5", today.minusDays(8));
        long inventoryId = initial.get("id").asLong();
        assertThat(initial.get("estimate_pending").asBoolean()).isTrue();
        assertThat(initial.get("estimated_quantity").isNull()).isTrue();
        assertThat(initial.get("reminder_date").isNull()).isTrue();
        assertThat(initial.get("received_quantity").decimalValue()).isEqualByComparingTo("4500");

        var rebuilt = action(inventoryId, "settings", Map.of("source", "MANUAL", "referenceQuantity", "4.5",
                "referenceUnit", "kg", "referenceDays", "14", "leadDays", 3));

        assertThat(rebuilt.get("estimate_pending").asBoolean()).isFalse();
        assertThat(rebuilt.get("estimated_quantity").decimalValue()).isCloseTo(
                new BigDecimal("1928.571428571432"), within(new BigDecimal("0.00000001")));
        assertThat(rebuilt.get("received_quantity").decimalValue()).isEqualByComparingTo("4500");
        assertThat(rebuilt.get("remaining_days").asInt()).isEqualTo(6);
        assertThat(rebuilt.get("depletion_date").asText()).isEqualTo(today.plusDays(6).toString());
        assertThat(rebuilt.get("reminder_date").asText()).isEqualTo(today.plusDays(3).toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_records", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links", Integer.class)).isZero();
    }

    /** 之后修改速度只影响修改日之后，晚补的历史入库仍按当时的旧速度重放。 */
    @Test
    void rateChangesPreserveHistoricalConsumptionWhenAnOlderReceiptArrivesLater() throws Exception {
        var initial = receipt(FIRST_PRODUCT_ID, "10", today.minusDays(10));
        long inventoryId = initial.get("id").asLong();
        assertThat(initial.get("estimate_pending").asBoolean()).isTrue();
        assertThat(initial.get("estimated_quantity").isNull()).isTrue();

        var firstRate = action(inventoryId, "settings", Map.of("source", "MANUAL", "referenceQuantity", "1",
                "referenceUnit", "kg", "referenceDays", "2", "leadDays", 3));
        assertQuantity(firstRate, "5000");
        assertThat(firstRate.get("effective_daily_rate").decimalValue()).isEqualByComparingTo("500");

        var changedRate = action(inventoryId, "settings", Map.of("source", "MANUAL", "referenceQuantity", "1",
                "referenceUnit", "kg", "referenceDays", "1", "leadDays", 3));
        assertQuantity(changedRate, "5000");
        assertThat(changedRate.get("effective_daily_rate").decimalValue()).isEqualByComparingTo("1000");

        var lateReceipt = receipt(FIRST_PRODUCT_ID, "5", today.minusDays(5));
        assertThat(lateReceipt.get("id").asLong()).isEqualTo(inventoryId);
        assertQuantity(lateReceipt, "10000");
        assertThat(lateReceipt.get("received_quantity").decimalValue()).isEqualByComparingTo("15000");
        assertThat(lateReceipt.get("remaining_days").asInt()).isEqualTo(10);
        assertThat(lateReceipt.get("depletion_date").asText()).isEqualTo(today.plusDays(10).toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_records", Integer.class)).isZero();
    }

    /** 确认今日实存量会覆盖更早购买；确认后新保存的今日真实购买仍应自动增加同一库存。 */
    @Test
    void confirmedAnchorCoversOldPurchasesButAllowsANewSampleToday() throws Exception {
        long inventoryId = prepareManualRate(FIRST_PRODUCT_ID);
        seed(1, FIRST_PRODUCT_ID, today.minusDays(10), "5");
        action(inventoryId, "set", Map.of("quantity", "2", "unit", "kg"));

        var covered = candidate(preview(), 1);
        assertThat(covered.get("selectable").asBoolean()).isFalse();
        assertThat(covered.get("reason").asText()).isNotBlank();
        assertBadConfirm("covered-history", List.of(1L));
        assertQuantity(detail(inventoryId), "2000");

        var sample = Map.of("dryRun", false, "records", List.of(Map.of("productName", "时间线猫粮甲",
                "normalizedName", "时间线猫粮甲", "price", 20, "quantity", "2", "unit", "kg",
                "purchaseDate", today.toString(), "owner", "timeline-test")));
        var recorded = postJson("/record-sample?receiveInventory=true", sample);
        assertThat(recorded.get("records")).hasSize(1);
        assertQuantity(detail(inventoryId), "4000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_items", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_records", Integer.class)).isEqualTo(2);
    }

    /** 重置开启新的历史重建，释放旧确认锚点；重复重置请求不能再次清空后来重建的库存。 */
    @Test
    void resetAllowsHistoricalReconstructionAfterAConfirmedQuantity() throws Exception {
        long inventoryId = prepareManualRate(FIRST_PRODUCT_ID);
        seed(1, FIRST_PRODUCT_ID, today.minusDays(17), "5");
        seed(2, FIRST_PRODUCT_ID, today.minusDays(8), "5");
        confirm("before-reset", List.of(1L, 2L));
        assertQuantity(detail(inventoryId), "6600");
        action(inventoryId, "set", Map.of("quantity", "2", "unit", "kg"));
        assertQuantity(detail(inventoryId), "2000");

        postJson("/" + inventoryId + "/reset", Map.of("requestKey", "one-reset"));
        assertQuantity(detail(inventoryId), "0");
        assertThat(candidate(preview(), 1).get("selectable").asBoolean()).isTrue();
        assertThat(candidate(preview(), 2).get("selectable").asBoolean()).isTrue();
        confirm("rebuild-after-reset", List.of(2L, 1L));
        assertQuantity(detail(inventoryId), "6600");
        assertThat(detail(inventoryId).get("effective_source").asText()).isEqualTo("MANUAL");

        postJson("/" + inventoryId + "/reset", Map.of("requestKey", "one-reset"));
        assertQuantity(detail(inventoryId), "6600");
    }

    /** 同步前的预计量按规则标准单位展示，重复预估无写入，并与实际确认后的今日剩余一致。 */
    @Test
    void selectionEstimateIsReadOnlyAndMatchesTheConfirmedQuantity() throws Exception {
        long inventoryId = prepareManualRate(FIRST_PRODUCT_ID);
        seed(1, FIRST_PRODUCT_ID, today.minusDays(17), "5");
        seed(2, FIRST_PRODUCT_ID, today.minusDays(8), "5");
        var original = snapshot();

        var estimated = postJson("/initialization/estimate", selection(List.of(2L, 1L, 1L)));
        assertThat(estimated.get("products")).hasSize(1);
        var productEstimate = estimated.get("products").get(0);
        assertThat(productEstimate.get("estimatedQuantity").decimalValue()).isEqualByComparingTo("6.6");
        postJson("/initialization/estimate", selection(List.of(1L, 2L)));
        assertThat(snapshot()).isEqualTo(original);

        confirm("confirm-after-estimate", List.of(1L, 2L));
        var current = detail(inventoryId);
        assertQuantity(current, "6600");
        assertThat(current.get("display_estimated_quantity").decimalValue())
                .isEqualByComparingTo(productEstimate.get("estimatedQuantity").decimalValue());
    }

    /** null、零和负数明细ID应作为请求错误拒绝，不能抛服务异常或遗留预估写入。 */
    @Test
    void invalidEstimatePurchaseIdsReturnBadRequestWithoutWriting() throws Exception {
        var original = snapshot();
        var invalidSelections = List.of(Collections.<Long>singletonList(null), List.of(0L), List.of(-1L));

        for (List<Long> purchaseIds : invalidSelections) {
            mvc.perform(post("/api/inventory/initialization/estimate").contentType("application/json")
                    .content(json.writeValueAsBytes(selection(purchaseIds)))).andExpect(status().isBadRequest());
            assertThat(snapshot()).isEqualTo(original);
        }
    }

    /** 未勾选的同商品历史购买可用于消耗速度统计，但不能进入库存数量的入库时间线。 */
    @Test
    void unselectedPurchasesDoNotBecomeReceiptsDuringReconstruction() throws Exception {
        long inventoryId = prepareManualRate(FIRST_PRODUCT_ID);
        seed(1, FIRST_PRODUCT_ID, today.minusDays(17), "5");
        seed(2, FIRST_PRODUCT_ID, today.minusDays(8), "5");

        confirm("only-first-purchase", List.of(1L));

        var current = detail(inventoryId);
        assertQuantity(current, "1600");
        assertThat(current.get("received_quantity").decimalValue()).isEqualByComparingTo("5000");
        assertThat(jdbc.queryForList("SELECT purchase_record_id FROM inventory_purchase_links", Long.class)).containsExactly(1L);
    }

    /**
     * 通过独立手动入库创建零库存，再预设1kg/5天的稳定人工速度；不设置当前实存锚点。
     * @param productId 测试归一化商品ID，非空正数
     * @return 对应商品的唯一库存档案ID
     */
    private long prepareManualRate(long productId) throws Exception {
        long inventoryId = receipt(productId, "0", today).get("id").asLong();
        action(inventoryId, "settings", Map.of("source", "MANUAL", "referenceQuantity", "1",
                "referenceUnit", "kg", "referenceDays", "5", "leadDays", 3));
        return inventoryId;
    }

    /**
     * 构造无购买关联的独立入库REST请求。
     * @param productId 测试商品规则ID
     * @param quantity 非负数量文本，单位kg
     * @param receivedDate 实际入库自然日，不允许未来
     * @return 创建或增加后的库存详情
     */
    private JsonNode receipt(long productId, String quantity, LocalDate receivedDate) throws Exception {
        return postJson("", Map.of("requestKey", UUID.randomUUID().toString(), "normalizedProductId", productId,
                "quantity", quantity, "unit", "kg", "receiptDate", receivedDate.toString()));
    }

    /**
     * 写入已归一化有效购买夹具，不调用库存入库，不修改其他购买明细。
     * @param purchaseId 隔离测试购买明细ID，正整数
     * @param productId 已存在的测试商品规则ID
     * @param purchasedDate 购买自然日，非空
     * @param quantity 正数购买数量文本，单位kg
     */
    private void seed(long purchaseId, long productId, LocalDate purchasedDate, String quantity) {
        jdbc.update("""
                INSERT INTO purchase_records(id,product_name,normalized_name,normalization_rule,order_time,
                quantity,unit,sku,category,total_amount,unit_price,decision,is_duplicate,dedupe_status,created_at)
                SELECT ?,normalized_name,normalized_name,rule_code,?,?,'kg',NULL,category,100,20,'include',0,'unique',?
                FROM normalization_rules WHERE id=?
                """, purchaseId, purchasedDate.toString(), quantity, purchasedDate.toString(), productId);
    }

    /**
     * 提交使用新幂等键的库存操作，执行实际REST事务。
     * @param inventoryId 已存在的库存档案ID
     * @param operation 操作名，本测试使用set及settings
     * @param parameters 业务参数，不包含requestKey
     * @return 操作成功后的详情
     */
    private JsonNode action(long inventoryId, String operation, Map<String, Object> parameters) throws Exception {
        var command = new LinkedHashMap<>(parameters);
        command.put("requestKey", UUID.randomUUID().toString());
        return postJson("/" + inventoryId + "/" + operation, command);
    }

    /**
     * 构造近一年同步选择参数，不包含幂等键，可直接用于只读预估。
     * @param purchaseIds 用户勾选的购买明细ID，非空集合
     * @return 含起止日和明细ID的请求参数
     */
    private Map<String, Object> selection(List<Long> purchaseIds) {
        return Map.of("startDate", today.minusYears(1).toString(), "endDate", today.toString(),
                "purchaseRecordIds", purchaseIds);
    }

    /**
     * 将明确选择的历史购买同步入库，提交实际幂等确认事务。
     * @param requestKey 本次确认幂等键，非空
     * @param purchaseIds 需要同步的明细ID，可包含重复项以验证去重
     * @return 实际同步及已同步跳过数量
     */
    private JsonNode confirm(String requestKey, List<Long> purchaseIds) throws Exception {
        var command = new LinkedHashMap<>(selection(purchaseIds));
        command.put("requestKey", requestKey);
        return postJson("/initialization/confirm", command);
    }

    /**
     * 验证不允许同步的购买记录无法绕过预览直接确认。
     * @param requestKey 非空幂等请求键
     * @param purchaseIds 不允许入库的购买ID
     */
    private void assertBadConfirm(String requestKey, List<Long> purchaseIds) throws Exception {
        var command = new LinkedHashMap<>(selection(purchaseIds));
        command.put("requestKey", requestKey);
        mvc.perform(post("/api/inventory/initialization/confirm").contentType("application/json")
                .content(json.writeValueAsBytes(command))).andExpect(status().isBadRequest());
    }

    /** @return 当前默认一年购买候选，仅通过只读预览REST接口读取 */
    private JsonNode preview() throws Exception {
        return json.readTree(mvc.perform(get("/api/inventory/initialization/preview")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 查找指定购买候选，不存在时直接使测试失败。
     * @param preview 包含records的预览响应
     * @param purchaseId 需要检查的购买明细ID
     * @return 对应购买候选
     */
    private JsonNode candidate(JsonNode preview, long purchaseId) {
        for (JsonNode purchase : preview.get("records")) {
            if (purchase.get("purchaseRecordId").asLong() == purchaseId) {
                return purchase;
            }
        }
        throw new AssertionError("预览缺少购买明细" + purchaseId);
    }

    /**
     * 读取动态库存详情，允许应用更新自动估算缓存。
     * @param inventoryId 目标库存档案ID
     * @return 今日库存详情
     */
    private JsonNode detail(long inventoryId) throws Exception {
        return json.readTree(mvc.perform(get("/api/inventory/" + inventoryId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 断言库存已完成估算且基础单位数量准确，不将原始采购总量混同于剩余量。
     * @param current 库存详情响应
     * @param grams 预期基础单位数量，单位g
     */
    private void assertQuantity(JsonNode current, String grams) {
        assertThat(current.get("estimate_pending").asBoolean()).isFalse();
        assertThat(current.get("estimated_quantity").decimalValue()).isEqualByComparingTo(grams);
    }

    /**
     * 提交库存REST的JSON请求，所有测试写入只作用于本类独立SQLite库。
     * @param path /api/inventory之后的接口路径
     * @param parameters 可序列化请求对象
     * @return 成功JSON响应
     */
    private JsonNode postJson(String path, Object parameters) throws Exception {
        return json.readTree(mvc.perform(post("/api/inventory" + path).contentType("application/json")
                .content(json.writeValueAsBytes(parameters))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** @return 测试表完整快照，用于比较只读预估前后数量、关联、价格和时间戳均未被修改 */
    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> snapshots = new LinkedHashMap<>();
        for (String table : SNAPSHOT_TABLES) {
            snapshots.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY rowid"));
        }
        return snapshots;
    }
}
