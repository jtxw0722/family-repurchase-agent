package com.jtxw.familyagent.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/15 12:51:46
 * @Description: 使用完整应用上下文及隔离 SQLite 验证在线接口真实映射、事务、只读预览、查重和日志隐私，不启动外部端口
 */
@SpringBootTest(properties = {
        "spring.config.import=optional:classpath:online-import-test-no-env.properties",
        "family-agent.normalization.llm.enabled=false",
        "family-agent.parse-order-image.model.enabled=false",
        "family-agent.parse-order-image.local-ocr.enabled=false"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class OnlineOrderImportIntegrationTest {
    /** 仅创建测试数据库，固定在忽略的 target 目录，不接触真实家庭数据库或用户上传文件。 */
    private static final String DATABASE_URL = "jdbc:sqlite:./target/online-integration-" + UUID.randomUUID() + ".sqlite";
    /** 实际应用的 MVC 请求入口，不替换应用服务或仓储。 */
    @Autowired private MockMvc mvc;
    /** 验证预览前后实际写入数量。 */
    @Autowired private JdbcTemplate jdbc;
    /** 协议 JSON 编解码组件。 */
    @Autowired private ObjectMapper json;

    /** 为完整应用上下文提供隔离数据库连接，不启动独立服务进程。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { registry.add("spring.datasource.url", () -> DATABASE_URL); }

    /** 验证真实预览、确认及重复提交全过程，用户隐私哨兵既不返回也不进入日志/购买记录。 */
    @Test void previewCommitAndRepeatMustRespectPrivacyAndSelection(CapturedOutput logs) throws Exception {
        String csv = "订单编号,订单提交时间,订单状态,店铺名称,商品名称,商品链接,型号款式,商品数量,商品金额,实付金额,运费,收货地址,手机号,物流单号\n"
                + "987654321012345678,2026-11-01 12:00:00,交易成功,测试店铺,猫砂,https://item.taobao.com/1,5kg,2,20,40,0,PRIVATE_ADDRESS,13800000000,PRIVATE_TRACKING\n";
        byte[] upload = json.writeValueAsBytes(Map.of("contentBase64", Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8)), "format", "csv", "owner", "tester"));
        var result = mvc.perform(post("/api/tools/online-order-import/preview").contentType("application/json").content(upload))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.rows[0].quantity").value(10)).andExpect(jsonPath("$.rows[0].selected").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result).doesNotContain("PRIVATE_ADDRESS", "PRIVATE_TRACKING", "13800000000", "987654321012345678");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_records", Integer.class)).isZero();
        byte[] confirm = json.writeValueAsBytes(json.readTree(result).get("rows"));
        mvc.perform(post("/api/tools/online-order-import/commit").contentType("application/json").content(confirm))
                .andExpect(status().isOk()).andExpect(jsonPath("$.savedRows.length()").value(1));
        mvc.perform(post("/api/tools/online-order-import/commit").contentType("application/json").content(confirm))
                .andExpect(status().isOk()).andExpect(jsonPath("$.savedRows.length()").value(0))
                .andExpect(jsonPath("$.duplicateRows.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_records", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM purchase_records").toString())
                .doesNotContain("PRIVATE_ADDRESS", "PRIVATE_TRACKING", "13800000000", "987654321012345678");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM raw_import_batches", Integer.class)).isZero();
        assertThat(logs.getAll()).doesNotContain("PRIVATE_ADDRESS", "PRIVATE_TRACKING", "13800000000", "987654321012345678");
    }
}
