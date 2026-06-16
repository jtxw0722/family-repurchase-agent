package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.application.query.ReviewItemQuery;
import com.jtxw.familyagent.common.ClockUtils;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 22:20:00
 * @Description: 复核项仓储集成测试，验证 listDetails 查询条件和分页逻辑。
 */
class ReviewItemRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private ReviewItemRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        Path dir = Path.of("target", "review-item-repository-test");
        Files.createDirectories(dir);
        Path db = dir.resolve("review-query.sqlite");
        Files.deleteIfExists(db);

        DataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + db);
        jdbcTemplate = new JdbcTemplate(dataSource);
        new DatabaseInitializer(jdbcTemplate).initialize();
        repository = new ReviewItemRepository(jdbcTemplate);

        insertTestData();
    }

    /**
     * 测试 1：按 pending 状态筛选。
     */
    @Test
    void shouldReturnPendingItems() {
        ReviewItemQuery query = new ReviewItemQuery("pending", null, null, null, null, null, 1, 100);
        List<ReviewItemDetail> results = repository.listDetails(query);

        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(r -> "pending".equals(r.status()));
    }

    /**
     * 测试 2：组合筛选覆盖 status、batchId、owner、reasonCode、decision 和 sourceFile。
     */
    @Test
    void shouldFilterByCombinedConditions() {
        ReviewItemQuery query = new ReviewItemQuery("pending", 1L, "jtxw",
                "QUANTITY_UNIT_PARSE_REVIEW", "exclude", "order-sample-1.xlsx", 1, 100);
        List<ReviewItemDetail> results = repository.listDetails(query);

        assertThat(results).hasSize(1);
        ReviewItemDetail item = results.get(0);
        assertThat(item.status()).isEqualTo("pending");
        assertThat(item.batchId()).isEqualTo(1L);
        assertThat(item.owner()).isEqualTo("jtxw");
        assertThat(item.reasonCode()).isEqualTo("QUANTITY_UNIT_PARSE_REVIEW");
        assertThat(item.decision()).isEqualTo("exclude");
        assertThat(item.sourceFile()).contains("order-sample-1.xlsx");
    }

    /**
     * 测试 3：分页生效。
     */
    @Test
    void shouldPaginateResults() {
        ReviewItemQuery page1 = new ReviewItemQuery(null, null, null, null, null, null, 1, 2);
        List<ReviewItemDetail> firstPage = repository.listDetails(page1);

        ReviewItemQuery page2 = new ReviewItemQuery(null, null, null, null, null, null, 2, 2);
        List<ReviewItemDetail> secondPage = repository.listDetails(page2);

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(2);

        // 两页数据不重复
        assertThat(firstPage).extracting(ReviewItemDetail::id)
                .doesNotContainAnyElementsOf(secondPage.stream().map(ReviewItemDetail::id).toList());

        // 排序稳定：按 created_at ASC, id ASC
        assertThat(firstPage.get(0).id()).isLessThan(firstPage.get(1).id());
        assertThat(secondPage.get(0).id()).isLessThan(secondPage.get(1).id());
    }

    /**
     * 测试 4：size 上限归一化。
     */
    @Test
    void shouldClampSizeToMax() {
        ReviewItemQuery query = new ReviewItemQuery(null, null, null, null, null, null, 1, 9999);
        ReviewItemQuery normalized = query.normalize();

        assertThat(normalized.size()).isEqualTo(500);
    }

    /**
     * 测试：page 小于 1 时归一化为 1。
     */
    @Test
    void shouldClampPageToMin() {
        ReviewItemQuery query = new ReviewItemQuery(null, null, null, null, null, null, -1, 100);
        ReviewItemQuery normalized = query.normalize();

        assertThat(normalized.page()).isEqualTo(1);
    }

    /**
     * 测试：size 小于 1 时按默认值 100 处理。
     */
    @Test
    void shouldClampSizeToMin() {
        ReviewItemQuery query = new ReviewItemQuery(null, null, null, null, null, null, 1, 0);
        ReviewItemQuery normalized = query.normalize();

        assertThat(normalized.size()).isEqualTo(100);
    }

    /**
     * 测试：null status 不过滤，返回所有状态（仓储层不负责默认值，由 Service 层处理）。
     */
    @Test
    void shouldReturnAllStatusesWhenStatusIsNull() {
        ReviewItemQuery query = new ReviewItemQuery(null, null, null, null, null, null, 1, 100);
        List<ReviewItemDetail> results = repository.listDetails(query);

        assertThat(results).hasSize(6);
        assertThat(results).anyMatch(r -> "pending".equals(r.status()));
        assertThat(results).anyMatch(r -> "resolved".equals(r.status()));
    }

    /**
     * 测试：无匹配条件时返回空列表。
     */
    @Test
    void shouldReturnEmptyWhenNoMatch() {
        ReviewItemQuery query = new ReviewItemQuery(null, null, null, "NONEXISTENT_CODE", null, null, 1, 100);
        List<ReviewItemDetail> results = repository.listDetails(query);

        assertThat(results).isEmpty();
    }

    /**
     * 插入测试数据：2 个批次、2 个 owner、2 种状态、3 种 reasonCode。
     */
    private void insertTestData() {
        // 批次 1
        jdbcTemplate.update("""
                INSERT INTO raw_import_batches(id, source_file, status, total_count, imported_count, review_count, created_at)
                VALUES (1, 'data\\inbox\\order-sample-1.xlsx', 'completed', 2, 2, 0, ?)
                """, ClockUtils.nowText());
        // 批次 2
        jdbcTemplate.update("""
                INSERT INTO raw_import_batches(id, source_file, status, total_count, imported_count, review_count, created_at)
                VALUES (2, 'data\\inbox\\order-sample-2.xlsx', 'completed', 2, 2, 0, ?)
                """, ClockUtils.nowText());

        // 购买记录：batch 1, jtxw, exclude
        jdbcTemplate.update("""
                INSERT INTO purchase_records(id, batch_id, order_time, platform, owner, product_name, normalized_name,
                    sku, category, sub_category, quantity, unit, total_amount, product_amount, paid_amount, shipping_fee,
                    amount_source, unit_price, currency, decision, is_duplicate, dedupe_status, source_file, created_at)
                VALUES (1, 1, '2026-05-01', 'taobao', 'jtxw', '猫砂', '猫砂', '10kg', '宠物用品', '猫砂',
                    10, 'kg', 68.0, 68.0, 68.0, 0, 'paid_amount', 6.8, 'CNY', 'exclude', 0, 'unique',
                    'data\\inbox\\order-sample-1.xlsx', ?)
                """, ClockUtils.nowText());
        // 购买记录：batch 1, jtxw, include
        jdbcTemplate.update("""
                INSERT INTO purchase_records(id, batch_id, order_time, platform, owner, product_name, normalized_name,
                    sku, category, sub_category, quantity, unit, total_amount, product_amount, paid_amount, shipping_fee,
                    amount_source, unit_price, currency, decision, is_duplicate, dedupe_status, source_file, created_at)
                VALUES (2, 1, '2026-05-02', 'taobao', 'jtxw', '洗衣液', '洗衣液', '2L', '日用品', '洗衣液',
                    2, 'L', 39.0, 39.0, 39.0, 0, 'paid_amount', 19.5, 'CNY', 'include', 0, 'unique',
                    'data\\inbox\\order-sample-1.xlsx', ?)
                """, ClockUtils.nowText());
        // 购买记录：batch 2, jtxw, exclude
        jdbcTemplate.update("""
                INSERT INTO purchase_records(id, batch_id, order_time, platform, owner, product_name, normalized_name,
                    sku, category, sub_category, quantity, unit, total_amount, product_amount, paid_amount, shipping_fee,
                    amount_source, unit_price, currency, decision, is_duplicate, dedupe_status, source_file, created_at)
                VALUES (3, 2, '2026-05-03', 'taobao', 'jtxw', '护舒宝卫生巾', '卫生巾', '32片', '日用品', '卫生巾',
                    96, '片', 137.63, 137.64, 137.63, 0, 'paid_amount', 1.43, 'CNY', 'exclude', 0, 'unique',
                    'data\\inbox\\order-sample-2.xlsx', ?)
                """, ClockUtils.nowText());
        // 购买记录：batch 2, lj, exclude
        jdbcTemplate.update("""
                INSERT INTO purchase_records(id, batch_id, order_time, platform, owner, product_name, normalized_name,
                    sku, category, sub_category, quantity, unit, total_amount, product_amount, paid_amount, shipping_fee,
                    amount_source, unit_price, currency, decision, is_duplicate, dedupe_status, source_file, created_at)
                VALUES (4, 2, '2026-05-04', 'jd', 'lj', '洗衣凝珠', '洗衣凝珠', '30颗', '日用品', '洗衣珠',
                    30, '颗', 45.0, 45.0, 45.0, 0, 'paid_amount', 1.5, 'CNY', 'exclude', 0, 'unique',
                    'data\\inbox\\order-sample-2.xlsx', ?)
                """, ClockUtils.nowText());

        // 复核项：4 个 pending + 2 个 resolved
        jdbcTemplate.update("""
                INSERT INTO review_items(id, record_id, reason_code, reason_message, status, created_at)
                VALUES (1, 1, 'QUANTITY_UNIT_PARSE_REVIEW', '单位解析需确认', 'pending', ?)
                """, ClockUtils.nowText());
        jdbcTemplate.update("""
                INSERT INTO review_items(id, record_id, reason_code, reason_message, status, created_at)
                VALUES (2, 2, 'PAYMENT_ADJUSTMENT', '实付金额为0', 'pending', ?)
                """, ClockUtils.nowText());
        jdbcTemplate.update("""
                INSERT INTO review_items(id, record_id, reason_code, reason_message, status, created_at)
                VALUES (3, 3, 'QUANTITY_UNIT_PARSE_REVIEW', '单位解析需确认', 'pending', ?)
                """, ClockUtils.nowText());
        jdbcTemplate.update("""
                INSERT INTO review_items(id, record_id, reason_code, reason_message, status, created_at)
                VALUES (4, 4, 'ORDER_AMOUNT_ANOMALY_REVIEW', '金额异常', 'pending', ?)
                """, ClockUtils.nowText());
        jdbcTemplate.update("""
                INSERT INTO review_items(id, record_id, reason_code, reason_message, status, review_decision, review_note, created_at, resolved_at)
                VALUES (5, 1, 'DUPLICATE_ORDER', '重复订单', 'resolved', 'include', '确认非重复', ?, ?)
                """, ClockUtils.nowText(), ClockUtils.nowText());
        jdbcTemplate.update("""
                INSERT INTO review_items(id, record_id, reason_code, reason_message, status, review_decision, review_note, created_at, resolved_at)
                VALUES (6, 2, 'SPEC_MULTIPACK_TIMES', '多包倍数', 'resolved', 'exclude', '规格不匹配', ?, ?)
                """, ClockUtils.nowText(), ClockUtils.nowText());
    }
}
