package com.jtxw.familyagent.interfaces.rest;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 19:39:30
 * @Description: 隔离SQLite与完整REST集成测试，验证库存事务幂等、每商品参数、动态提醒和价格数据隔离
 */
@SpringBootTest(properties={"spring.config.import=optional:classpath:inventory-test-no-env.properties", "family-agent.normalization.llm.enabled=false", "family-agent.parse-order-image.model.enabled=false", "family-agent.parse-order-image.local-ocr.enabled=false"})
@AutoConfigureMockMvc
class InventoryIntegrationTest {
    /** 隔离数据库路径，绝不连接真实家庭库。 */
    private static final String URL = "jdbc:sqlite:./target/inventory-test-" + UUID.randomUUID() + ".sqlite";
    /** 完整REST请求入口。 */
    @Autowired private MockMvc mvc;
    /** 测试夹具和数据安全断言使用的模板。 */
    @Autowired private JdbcTemplate jdbc;
    /** JSON协议编码器。 */
    @Autowired private ObjectMapper json;
    /** 验证内部自动入库幂等，不暴露历史明细关联API。 */
    @Autowired private com.jtxw.familyagent.application.InventoryApplicationService inventory;

    /** @param registry 测试数据库配置 */
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { registry.add("spring.datasource.url", ()->URL); }

    /** 清理仅限隔离库的本轮数据，构造两个独立归一化商品。 */
    @BeforeEach void fixtures() {
        for (String table : List.of("inventory_receipts","inventory_consumption_rates","inventory_requests","inventory_purchase_links","replenishment_predictions","replenishment_settings","inventory_items","purchase_records")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT OR IGNORE INTO normalization_rules(id,rule_code,normalized_name,standard_unit,unit_family,created_at,updated_at) VALUES(90001,'inventory_cat','测试猫粮','kg','weight','2026-01-01','2026-01-01'),(90002,'inventory_litter','测试猫砂','kg','weight','2026-01-01','2026-01-01')");
    }

    /** 独立包装库存不写购买记录；未知、零、无速度、非法单位与重复请求均区分。 */
    @Test void independentStockAndValidation() throws Exception {
        var created = create(90001,"2","kg");
        assertThat(created.get("estimated_quantity").decimalValue()).isEqualByComparingTo("2000");
        assertThat(created.get("reminder_date").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_records",Integer.class)).isZero();
        long id = created.get("id").asLong();
        var body = new LinkedHashMap<String,Object>(); body.put("quantity","1"); body.put("unit","kg"); body.put("requestKey","repeat-add");
        postJson("/"+id+"/add",body); var repeated = postJson("/"+id+"/add",body);
        assertThat(repeated.get("estimated_quantity").decimalValue()).isEqualByComparingTo("3000");
        body.put("quantity","2");
        mvc.perform(post("/api/inventory/"+id+"/add").contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest());
        var unknown = new LinkedHashMap<String,Object>(); unknown.put("quantity",null); unknown.put("unit","kg");
        assertThat(action(id,"set",unknown).get("estimated_quantity").isNull()).isTrue();
        assertThat(action(id,"set",Map.of("quantity","0","unit","kg")).get("status").asText()).contains("库存为零");
        mvc.perform(post("/api/inventory/"+id+"/set").contentType("application/json").content(json.writeValueAsBytes(Map.of("quantity",1,"unit","L","requestKey","bad-unit")))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_requests WHERE request_key='bad-unit'",Integer.class)).isZero();
    }

    /** 自动估算继续更新，人工商品不影响其他商品，恢复自动仍保留人工原始输入。 */
    @Test void perProductManualAndAutomatic() throws Exception {
        purchase(1,90001,"2026-10-01","4.5","kg","", "include",0,"unique");
        purchase(2,90001,"2026-10-15","8","kg","", "include",0,"unique");
        purchase(3,90002,"2026-10-01","2","kg","", "include",0,"unique");
        purchase(4,90002,"2026-10-11","2","kg","", "include",0,"unique");
        long cat = create(90001,"10","kg").get("id").asLong();
        long litter = create(90002,"10","kg").get("id").asLong();
        var manual = action(cat,"settings",Map.of("source","MANUAL","referenceQuantity","4.5","referenceUnit","kg","referenceDays","14","leadDays",3));
        assertThat(manual.get("effective_daily_rate").decimalValue()).isEqualByComparingTo("321.428571428571");
        assertThat(manual.get("remaining_days").asInt()).isEqualTo(31);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assertThat(manual.get("depletion_date").asText()).isEqualTo(today.plusDays(31).toString());
        assertThat(manual.get("reminder_date").asText()).isEqualTo(today.plusDays(28).toString());
        purchase(5,90001,"2026-11-09","4.5","kg","", "include",0,"unique");
        var refreshed = getJson("/"+cat);
        assertThat(refreshed.get("auto_daily_rate").asText()).isEqualTo("320.714285714286");
        assertThat(refreshed.get("effective_daily_rate").decimalValue()).isEqualByComparingTo("321.428571428571");
        assertThat(getJson("/"+litter).get("effective_source").asText()).isEqualTo("AUTO");
        assertThat(getJson("/"+litter).get("effective_daily_rate").decimalValue()).isEqualByComparingTo("200");
        var restored=action(cat,"settings",Map.of("source","AUTO","leadDays",3));
        assertThat(restored.get("effective_daily_rate").decimalValue()).isEqualByComparingTo("320.714285714286");
        assertThat(restored.get("manual_reference_quantity").asText()).isEqualTo("4.5");
        assertThat(jdbc.queryForObject("SELECT total_amount FROM purchase_records WHERE id=1",String.class)).isEqualTo("20.0");
    }

    /** 包装折算、同日合并、无效过滤及重复关联入库，购买价格和明细保持原样。 */
    @Test void linkingAndFiltering() throws Exception {
        purchase(1,90001,"2026-10-01","1","袋","4.5kg", "include",0,"unique");
        purchase(2,90001,"2026-10-01","4500","g","", "include",0,"unique");
        purchase(3,90001,"2026-10-15","8","kg","", "include",0,"unique");
        purchase(4,90001,"2026-10-20","100","kg","", "exclude",0,"unique");
        purchase(5,90001,"2026-10-21","100","kg","", "include",1,"duplicate");
        purchase(6,90001,"bad","1","kg","", "include",0,"unique");
        purchase(7,90001,"2026-10-23","0","kg","", "include",0,"unique");
        purchase(8,90001,"2026-10-24","1","袋","未知", "include",0,"unique");
        purchase(9,90001,"2026-10-25","1","L","", "include",0,"unique");
        var row=create(90001,"0","kg"); long id=row.get("id").asLong();
        assertThat(row.get("auto_sample_count").asInt()).isEqualTo(1);
        assertThat(row.get("purchase_record_count").asInt()).isEqualTo(3);
        assertThat(row.get("warnings").asText()).contains("明细4", "明细5", "明细6", "明细7", "明细8", "明细9");
        inventory.receivePurchase(1);
        var first=getJson("/"+id);
        inventory.receivePurchase(1);
        var repeat=getJson("/"+id);
        assertThat(first.get("estimated_quantity").decimalValue()).isEqualByComparingTo("0");
        assertThat(repeat.get("estimated_quantity").decimalValue()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_records",Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT sum(total_amount) FROM purchase_records",Integer.class)).isEqualTo(180);
    }

    /** 动态消耗不每日写库；新增入库结算、到期状态、延后及重新设置刷新提醒。 */
    @Test void dynamicStockAndReminder() throws Exception {
        long id=create(90001,"10","kg").get("id").asLong();
        action(id,"settings",Map.of("source","MANUAL","referenceQuantity","1","referenceUnit","kg","referenceDays","1","leadDays",3));
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        jdbc.update("UPDATE inventory_receipts SET received_date=? WHERE inventory_item_id=?",today.minusDays(2).toString(),id);
        assertThat(getJson("/"+id).get("estimated_quantity").decimalValue()).isEqualByComparingTo("8000");
        assertThat(jdbc.queryForObject("SELECT current_quantity FROM inventory_items WHERE id=?",String.class,id)).isEqualTo("10000");
        assertThat(action(id,"add",Map.of("quantity","2","unit","kg")).get("estimated_quantity").decimalValue()).isEqualByComparingTo("10000");
        jdbc.update("UPDATE inventory_receipts SET received_date=? WHERE inventory_item_id=?",today.minusDays(20).toString(),id);
        assertThat(getJson("/"+id).get("status").asText()).contains("已超过");
        assertThat(action(id,"snooze",Map.of("snoozeDays",3)).get("status").asText()).isEqualTo("已延后提醒");
        assertThat(action(id,"set",Map.of("quantity","1","unit","kg")).get("status").asText()).isEqualTo("立即补货");
        getJson(""); getJson("");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM replenishment_predictions",Integer.class)).isEqualTo(1);
    }

    /**
     * 验证首页按现有提醒分组、排除已延后的商品，读取过程不落盘库存预测。
     *
     * @throws Exception 模拟请求或响应解析失败时抛出
     */
    @Test
    void dashboardGroupsAlertsWithoutRefreshingStoredPredictions() throws Exception {
        create(90001, "0", "kg");
        long litter = create(90002, "5", "kg").get("id").asLong();
        action(litter, "settings", Map.of("source", "MANUAL", "referenceQuantity", "1",
                "referenceUnit", "kg", "referenceDays", "1", "leadDays", 3));
        var savedPredictions = jdbc.queryForList("SELECT * FROM replenishment_predictions ORDER BY inventory_item_id");
        var savedRates = jdbc.queryForList("SELECT * FROM inventory_consumption_rates ORDER BY inventory_item_id, effective_date");

        var alerts = getJson("/replenishment-overview");
        assertThat(alerts.size()).isEqualTo(2);
        assertThat(alerts.get(0).get("normalizedName").asText()).isEqualTo("测试猫粮");
        assertThat(alerts.get(0).get("immediate").asBoolean()).isTrue();
        assertThat(alerts.get(1).get("normalizedName").asText()).isEqualTo("测试猫砂");
        assertThat(alerts.get(1).get("immediate").asBoolean()).isFalse();
        assertThat(alerts.get(1).get("remainingDays").asInt()).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT * FROM replenishment_predictions ORDER BY inventory_item_id"))
                .isEqualTo(savedPredictions);
        assertThat(jdbc.queryForList("SELECT * FROM inventory_consumption_rates ORDER BY inventory_item_id, effective_date"))
                .isEqualTo(savedRates);

        action(litter, "snooze", Map.of("snoozeDays", 3));
        assertThat(getJson("/replenishment-overview").size()).isEqualTo(1);
    }

    /** 同一商品手动入库自动创建或累加；使用标准重量单位且完全不写购买和关联表。 */
    @Test void manualReceiptCreatesAndAccumulatesWithoutPurchase() throws Exception {
        var first=create(90001,"4500","g");
        var second=create(90001,"4.5","kg");
        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(second.get("base_unit").asText()).isEqualTo("g");
        assertThat(second.get("estimated_quantity").decimalValue()).isEqualByComparingTo("9000");
        var packaged=action(0,"",Map.of("normalizedProductId",90001,"quantity","2","unit","袋","specification","4.5kg/袋","receiptDate","2026-11-01","note","家中补货"));
        assertThat(packaged.get("estimated_quantity").isNull()).isTrue();
        assertThat(packaged.get("estimate_pending").asBoolean()).isTrue();
        assertThat(packaged.get("received_quantity").decimalValue()).isEqualByComparingTo("18000");
        assertThat(packaged.get("last_receipt_note").asText()).isEqualTo("家中补货");
        assertThat(packaged.get("last_received_date").asText()).isEqualTo("2026-11-01");
        var repeatedBody=Map.<String,Object>of("requestKey","same-manual-receipt","normalizedProductId",90001,"quantity","1","unit","g");
        postJson("",repeatedBody);
        assertThat(postJson("",repeatedBody).get("received_quantity").decimalValue()).isEqualByComparingTo("18001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_items",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_records",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links",Integer.class)).isZero();
    }

    /** 未知包装规格不能变成永久基础单位，不兼容单位不能破坏已有量，失败不遗留档案。 */
    @Test void incompatibleReceiptIsRejected() throws Exception {
        mvc.perform(post("/api/inventory").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestKey","no-spec","normalizedProductId",90001,"quantity",2,"unit","袋"))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_items",Integer.class)).isZero();
        var first=create(90001,"4.5","kg");
        mvc.perform(post("/api/inventory").contentType("application/json").content(json.writeValueAsBytes(Map.of("requestKey","wrong-dimension","normalizedProductId",90001,"quantity",2,"unit","L"))))
                .andExpect(status().isBadRequest());
        assertThat(getJson("/"+first.get("id").asLong()).get("estimated_quantity").decimalValue()).isEqualByComparingTo("4500");
    }

    /** 当前记录样本与手动入库共同使用唯一档案，重复保存的疑似重复明细不再增加库存。 */
    @Test void recordSampleAutomaticallyReceivesIntoSameInventory() throws Exception {
        var initial=create(90001,"1","kg");
        var payload=sample("测试猫粮","kg","2",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        var response=postJson("/record-sample?receiveInventory=true",payload);
        long recordId=response.get("records").get(0).get("recordId").asLong();
        inventory.receivePurchase(recordId);
        postJson("/record-sample?receiveInventory=true",payload);
        assertThat(getJson("/"+initial.get("id").asLong()).get("estimated_quantity").decimalValue()).isEqualByComparingTo("3000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_items",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links",Integer.class)).isEqualTo(1);
    }

    /** 无库存档案时记录真实样本自动创建；预览不写库，历史补录关闭入库。 */
    @Test void newSampleCreatesButPreviewAndHistoricalDoNotReceive() throws Exception {
        var preview=new LinkedHashMap<>(sample("测试猫粮","kg","2","2026-11-01")); preview.put("dryRun",true);
        postJson("/record-sample?receiveInventory=true",preview);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_items",Integer.class)).isZero();
        postJson("/record-sample",sample("测试猫粮","kg","2","2026-11-01"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_items",Integer.class)).isZero();
        postJson("/record-sample?receiveInventory=true",sample("测试猫砂","kg","3",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()));
        assertThat(jdbc.queryForObject("SELECT current_quantity FROM inventory_items WHERE normalized_product_id=90002",String.class)).isEqualTo("3000");
    }

    /** 自动入库失败时撤销购买保存，避免价格成功而库存失败的半提交。 */
    @Test void failedAutomaticConversionRollsBackPurchase() throws Exception {
        mvc.perform(post("/api/inventory/record-sample?receiveInventory=true").contentType("application/json")
                .content(json.writeValueAsBytes(sample("测试猫粮","袋","2",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString())))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_records",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_items",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links",Integer.class)).isZero();
    }

    /** 历史批量导入仍只写购买模块；旧关联入口不可用于入库。 */
    @Test void historicalBatchDoesNotReceiveAndLinkApiIsRemoved() throws Exception {
        String csv="订单编号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费\n"
                +"123456789,2026-11-01 12:00:00,交易成功,测试店铺,猫砂,https://item.taobao.com/1,5kg,2,20,40,0\n";
        var preview=json.readTree(mvc.perform(post("/api/tools/online-order-import/preview").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("contentBase64",Base64.getEncoder().encodeToString(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"format","csv","owner","tester"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(post("/api/tools/online-order-import/commit").contentType("application/json").content(json.writeValueAsBytes(preview.get("rows"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.savedRows.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_items",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_purchase_links",Integer.class)).isZero();
        long id=create(90001,"0","kg").get("id").asLong();
        mvc.perform(post("/api/inventory/"+id+"/link").contentType("application/json").content("{\"requestKey\":\"old-link\",\"purchaseRecordId\":1}"))
                .andExpect(status().isBadRequest());
    }

    /** @return 原购买协议样本，不额外引入价格字段或改变价格规则 */
    private Map<String,Object> sample(String name,String unit,String quantity,String day) {
        return Map.of("dryRun",false,"records",List.of(Map.of("productName",name,"normalizedName",name,"price",20,"quantity",quantity,"unit",unit,"purchaseDate",day,"owner","tester")));
    }

    /** 旧g库存通过标准kg展示，三种速度同步换算，预测日期和原存量不因换标签被改写。 */
    @Test void displaysStandardQuantityAndAllRatesWithoutRewritingStorage() throws Exception {
        purchase(1,90001,"2026-10-01","4.5","kg","", "include",0,"unique");
        purchase(2,90001,"2026-10-15","8","kg","", "include",0,"unique");
        long id=create(90001,"10","kg").get("id").asLong();
        var result=action(id,"settings",Map.of("source","MANUAL","referenceQuantity","4.5","referenceUnit","kg","referenceDays","14","leadDays",3));
        assertThat(result.get("base_unit").asText()).isEqualTo("g");
        assertThat(result.get("display_unit").asText()).isEqualTo("kg");
        assertThat(result.get("unit_family").asText()).isEqualTo("weight");
        assertThat(result.get("unit_compatible").asBoolean()).isTrue();
        assertThat(result.get("display_estimated_quantity").decimalValue()).isEqualByComparingTo("10");
        for(String key:List.of("auto_daily_rate","manual_daily_rate","effective_daily_rate")) {
            assertThat(result.get("display_"+key).decimalValue()).isEqualByComparingTo("0.321428571428571");
        }
        assertThat(result.get("remaining_days").asInt()).isEqualTo(31);
        assertThat(jdbc.queryForObject("SELECT current_quantity FROM inventory_items WHERE id=?",String.class,id)).isEqualTo("10000");
        var changed=action(id,"set",Map.of("quantity","5","unit","kg"));
        assertThat(changed.get("display_estimated_quantity").decimalValue()).isEqualByComparingTo("5");
        assertThat(action(id,"add",Map.of("quantity","2","unit","kg")).get("display_estimated_quantity").decimalValue()).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("SELECT current_quantity FROM inventory_items WHERE id=?",String.class,id)).isEqualTo("7000");
    }

    /** 同一体积/重量族按每条规则的L、ml、g分别展示，保留用户人工参数原始单位。 */
    @Test void displaysEachProductConfiguredPhysicalUnit() throws Exception {
        jdbc.update("""
                INSERT OR IGNORE INTO normalization_rules(id,rule_code,normalized_name,standard_unit,unit_family,created_at,updated_at)
                VALUES(90003,'unit_liter','测试升','L','volume','2026-01-01','2026-01-01'),
                (90004,'unit_milliliter','测试毫升','ml','volume','2026-01-01','2026-01-01'),
                (90005,'unit_gram','测试克','g','weight','2026-01-01','2026-01-01')
                """);
        long id=create(90003,"2.5","L").get("id").asLong();
        var liters=action(id,"settings",Map.of("source","MANUAL","referenceQuantity","500","referenceUnit","ml","referenceDays","10","leadDays",3));
        assertThat(liters.get("display_unit").asText()).isEqualTo("L");
        assertThat(liters.get("display_estimated_quantity").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(liters.get("display_manual_daily_rate").decimalValue()).isEqualByComparingTo("0.05");
        assertThat(liters.get("manual_reference_unit").asText()).isEqualTo("ml");
        var milliliters=create(90004,"500","ml");
        assertThat(milliliters.get("display_unit").asText()).isEqualTo("ml");
        assertThat(milliliters.get("display_estimated_quantity").decimalValue()).isEqualByComparingTo("500");
        var grams=create(90005,"0.2","kg");
        assertThat(grams.get("display_unit").asText()).isEqualTo("g");
        assertThat(grams.get("display_estimated_quantity").decimalValue()).isEqualByComparingTo("200");
    }

    /** 粒、条、复合计数单位和抽数均遵循规则，不能退回件或因固定白名单被拒绝。 */
    @Test void supportsConfiguredCountAndDrawUnits() throws Exception {
        jdbc.update("""
                INSERT OR IGNORE INTO normalization_rules(id,rule_code,normalized_name,standard_unit,unit_family,created_at,updated_at)
                VALUES(90006,'unit_grain','测试粒','粒','count','2026-01-01','2026-01-01'),
                (90007,'unit_strip','测试条','条','count','2026-01-01','2026-01-01'),
                (90008,'unit_tear','测试复合计数','cm*撕','count','2026-01-01','2026-01-01'),
                (90009,'unit_draw','测试抽','抽','draw_count','2026-01-01','2026-01-01')
                """);
        String[] configuredUnits={"粒","条","cm*撕","抽"};
        for(int index=0;index<configuredUnits.length;index++) {
            String unit=configuredUnits[index];
            long id=create(90006+index,"10",unit).get("id").asLong();
            var result=action(id,"settings",Map.of("source","MANUAL","referenceQuantity","10","referenceUnit",unit,"referenceDays","5","leadDays",0));
            assertThat(result.get("display_unit").asText()).isEqualTo(unit);
            assertThat(result.get("display_estimated_quantity").decimalValue()).isEqualByComparingTo("10");
            assertThat(result.get("display_effective_daily_rate").decimalValue()).isEqualByComparingTo("2");
        }
    }

    /** 旧包装库存与规则重量不兼容时保留袋数并提示，不把2袋伪装为2kg或继续生成日期。 */
    @Test void doesNotRelabelIncompatibleLegacyStock() throws Exception {
        long id=create(90001,"2","kg").get("id").asLong();
        jdbc.update("UPDATE inventory_items SET base_unit='袋',current_quantity='2',anchor_quantity='2',anchor_at=quantity_recorded_at WHERE id=?",id);
        jdbc.update("DELETE FROM inventory_receipts WHERE inventory_item_id=?",id);
        var result=getJson("/"+id);
        assertThat(result.get("unit_compatible").asBoolean()).isFalse();
        assertThat(result.get("display_unit").asText()).isEqualTo("袋");
        assertThat(result.get("display_estimated_quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(result.get("unit_warning").asText()).contains("不兼容");
        assertThat(result.get("depletion_date").isNull()).isTrue();
        assertThat(result.get("reminder_date").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT current_quantity FROM inventory_items WHERE id=?",String.class,id)).isEqualTo("2");
    }

    /** @return 独立创建响应 */
    private JsonNode create(long product,String quantity,String unit) throws Exception { return action(0,"",Map.of("normalizedProductId",product,"quantity",quantity,"unit",unit)); }
    /** @return 附加唯一幂等键后的写入响应 */
    private JsonNode action(long id,String operation,Map<String,Object> values) throws Exception {
        var body=new LinkedHashMap<>(values); body.put("requestKey",UUID.randomUUID().toString());
        return postJson(id==0?"":"/"+id+"/"+operation,body);
    }
    /** @return 真实REST写入结果 */
    private JsonNode postJson(String path,Map<String,Object> body) throws Exception { return json.readTree(mvc.perform(post("/api/inventory"+path).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
    /** @return 真实REST查询结果 */
    private JsonNode getJson(String path) throws Exception { return json.readTree(mvc.perform(get("/api/inventory"+path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
    /** 构造原表兼容的购买明细，价格固定用于隔离断言。 */
    private void purchase(long id,long product,String date,String quantity,String unit,String sku,String decision,int duplicate,String dedupe) {
        jdbc.update("INSERT INTO purchase_records(id,product_name,normalized_name,normalization_rule,order_time,quantity,unit,sku,total_amount,unit_price,decision,is_duplicate,dedupe_status,created_at) SELECT ?,normalized_name,normalized_name,rule_code,?,?,?,?,20,10,?,?,?,'2026-01-01' FROM normalization_rules WHERE id=?",id,date,quantity,unit,sku,decision,duplicate,dedupe,product);
    }
}
