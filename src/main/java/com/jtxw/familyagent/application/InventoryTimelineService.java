package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.policy.InventoryPolicy;
import com.jtxw.familyagent.domain.policy.InventoryPolicy.Projection;
import com.jtxw.familyagent.domain.policy.InventoryPolicy.Purchase;
import com.jtxw.familyagent.domain.policy.InventoryPolicy.RatePeriod;
import com.jtxw.familyagent.infrastructure.persistence.InventoryRepository;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 22:20:17
 * @Description: 库存时间线应用服务，在外层库存事务内保存最小入库快照、人工锚点和消耗分段，协调只读重放
 */
@Service
public class InventoryTimelineService {
    /** 上海自然日时区，非空，所有入库与分段使用同一时区。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 首次可靠速度回推本周期全部历史，固定为ISO最早四位年日期。 */
    private static final LocalDate INITIAL_RATE_DATE = LocalDate.of(1, 1, 1);
    /** 库存专用仓储，非空，不写购买或价格。 */
    private final InventoryRepository repository;

    /** @param repository 库存专用仓储，非空，无创建数据副作用 */
    public InventoryTimelineService(InventoryRepository repository) { this.repository = repository; }

    /**
     * 保存一次入库快照并更新兼容数量缓存；重复键由外层事务及唯一约束共同保护。
     * @param id 库存ID，正数
     * @param quantity 已换算的非负基础数量
     * @param received 实际入库日，不允许未来
     * @param key 入库唯一请求键，非空
     * @throws IllegalArgumentException 入库早于人工锚点或库存未知时抛出，外层事务回滚
     */
    public void receive(long id, BigDecimal quantity, LocalDate received, String key) {
        var item = repository.find(id);
        LocalDate anchor = date(item.get("anchor_at"));
        if (anchor != null && item.get("anchor_quantity") == null) { throw new IllegalArgumentException("库存未知，请先确认当前数量"); }
        if (received.isAfter(today()) || (anchor != null && received.isBefore(anchor))) {
            throw new IllegalArgumentException("入库日期早于最近库存确认日或在未来，请核对当前数量");
        }
        repository.update("INSERT INTO inventory_receipts(inventory_item_id,received_date,quantity,request_key,created_at) VALUES(?,?,?,?,?)",
                id, received.toString(), text(quantity), key, now());
        cache(id);
    }

    /**
     * 建立人工实测锚点并结束旧计算周期；购买关联保留，防止同一明细再次累计。
     * @param id 库存ID
     * @param quantity 当前实际基础数量，可空表示未知
     */
    public void confirm(long id, BigDecimal quantity) {
        repository.update("UPDATE inventory_items SET anchor_quantity=?,anchor_at=?,history_rebuild_required=0 WHERE id=?", text(quantity), now(), id);
        repository.update("DELETE FROM inventory_receipts WHERE inventory_item_id=?", id);
        repository.quantity(id, text(quantity), now());
    }

    /** @param id 库存ID；清空本周期输入和锚点，允许由外层重新关联历史购买，保留原人工参数 */
    public void reset(long id) {
        repository.update("DELETE FROM inventory_receipts WHERE inventory_item_id=?", id);
        repository.update("DELETE FROM inventory_consumption_rates WHERE inventory_item_id=?", id);
        repository.update("UPDATE inventory_items SET anchor_quantity=NULL,anchor_at=NULL,history_rebuild_required=0 WHERE id=?", id);
        repository.quantity(id, "0", now());
    }

    /**
     * 保存有效速度变化；首次取得速度可回推历史，之后的变化只从今日生效。
     * @param id 库存ID
     * @param rate 日均基础数量，可空表示失去可靠估算
     */
    public void rememberRate(long id, BigDecimal rate) {
        List<RatePeriod> current = rates(id);
        List<RatePeriod> projected = withRate(current, rate);
        if (projected.equals(current)) { return; }
        RatePeriod changed = projected.get(projected.size() - 1);
        repository.update("""
                INSERT INTO inventory_consumption_rates(inventory_item_id,effective_date,daily_rate) VALUES(?,?,?)
                ON CONFLICT(inventory_item_id,effective_date) DO UPDATE SET daily_rate=excluded.daily_rate
                """, id, changed.date().toString(), text(changed.dailyRate()));
        cache(id);
    }

    /** @param item 库存快照 @return 只读动态预测；查询不每日扣写库存 */
    public Projection project(Map<String, Object> item) {
        long id = ((Number) item.get("id")).longValue();
        return InventoryPolicy.project(decimal(item.get("anchor_quantity")), date(item.get("anchor_at")), receipts(id), rates(id), today());
    }

    /**
     * 在内存中追加勾选入库并模拟当前消耗速度，无数据库副作用。
     * @param item 已有库存，可空代表首次创建
     * @param additions 已筛选且换算完成的本次新增明细
     * @param rate 当前采用的可靠速度，可空
     * @return 同步后预计库存，包含已有入库及人工基准
     */
    public Projection preview(Map<String, Object> item, List<Purchase> additions, BigDecimal rate) {
        Long id = item == null ? null : ((Number) item.get("id")).longValue();
        List<Purchase> combined = id == null ? new ArrayList<>() : receipts(id);
        combined.addAll(additions);
        return InventoryPolicy.project(item == null ? null : decimal(item.get("anchor_quantity")),
                item == null ? null : date(item.get("anchor_at")), combined, withRate(id == null ? List.of() : rates(id), rate), today());
    }

    /** @param id 库存ID，仅在入库或速度变化时更新兼容缓存，不作为历史重放的数据来源 */
    private void cache(long id) { repository.quantity(id, text(project(repository.find(id)).quantity()), now()); }

    /** @param id 库存ID @return 已保存的标准化入库快照，可变集合供预览合并 */
    private List<Purchase> receipts(long id) {
        return new ArrayList<>(repository.receipts(id).stream().map(row -> new Purchase(date(row.get("received_date")), decimal(row.get("quantity")))).toList());
    }

    /** @param id 库存ID @return 按生效日期升序排列的速度分段 */
    private List<RatePeriod> rates(long id) {
        return repository.consumptionRates(id).stream().map(row -> new RatePeriod(date(row.get("effective_date")), decimal(row.get("daily_rate")))).toList();
    }

    /** @param current 现有分段 @param rate 本次有效速度，可空 @return 内存分段；首次无速度不伪造零消耗 */
    private List<RatePeriod> withRate(List<RatePeriod> current, BigDecimal rate) {
        List<RatePeriod> result = new ArrayList<>(current);
        if (result.isEmpty()) {
            if (rate != null && rate.signum() > 0) { result.add(new RatePeriod(INITIAL_RATE_DATE, rate)); }
        } else if (!Objects.equals(text(result.get(result.size() - 1).dailyRate()), text(rate))) {
            if (result.get(result.size() - 1).date().equals(today())) { result.remove(result.size() - 1); }
            result.add(new RatePeriod(today(), rate));
        }
        return result;
    }

    /** @return 上海今日 */
    private LocalDate today() { return LocalDate.now(ZONE); }
    /** @return 上海当前时间，用于人工确认与兼容缓存 */
    private String now() { return OffsetDateTime.now(ZONE).toString(); }
    /** @param value 数据库十进制文本，可空 @return 精确数量或null */
    private BigDecimal decimal(Object value) { return value == null ? null : new BigDecimal(value.toString()); }
    /** @param value 日期或时间，可空 @return 上海自然日或null */
    private LocalDate date(Object value) { return value == null ? null : LocalDate.parse(value.toString().substring(0, 10)); }
    /** @param value 精确数量，可空 @return 数据库十进制文本或null */
    private String text(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
}
