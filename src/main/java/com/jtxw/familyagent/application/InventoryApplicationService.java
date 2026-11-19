package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.ReplenishmentOverviewItem;
import com.jtxw.familyagent.domain.policy.InventoryPolicy;
import com.jtxw.familyagent.domain.policy.InventoryPolicy.Purchase;
import com.jtxw.familyagent.infrastructure.persistence.InventoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 19:39:30
 * @Description: 独立库存应用服务，协调事务、幂等入库与重置、自动估算缓存、人工参数及查询时补货提醒
 */
@Service
@Transactional(rollbackFor = Exception.class)
public class InventoryApplicationService {
    /** 上海自然日时区，所有库存基准和提醒使用同一时区。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 幂等请求键长度上限，单位字符，固定120。 */
    private static final int MAX_REQUEST_KEY_LENGTH = 120;
    /** 提前和延后提醒天数上限，单位自然日，固定3650。 */
    private static final int MAX_REMINDER_DAYS = 3650;
    /** 即将提醒窗口，单位自然日，固定3天。 */
    private static final int UPCOMING_REMINDER_DAYS = 3;
    /** 提醒日期已到的补货状态。 */
    private static final String REPLENISH_NOW = "立即补货";
    /** 提醒日期在未来三天内的补货状态。 */
    private static final String REPLENISH_SOON = "即将进入补货周期";
    /** 预计耗尽日期已过的补货状态。 */
    private static final String DEPLETION_OVERDUE = "已超过预计耗尽日期";
    /** 数量确认为零且缺少有效预测时的补货状态。 */
    private static final String OUT_OF_STOCK = "库存为零，请补货";
    /** 最近入库备注长度上限，单位字符，固定500。 */
    private static final int MAX_RECEIPT_NOTE_LENGTH = 500;
    /** 库存仓储，不允许为空。 */
    private final InventoryRepository repository;
    /** 时间线应用服务，非空，与本服务共享事务，不参与购买或价格分析。 */
    private final InventoryTimelineService timeline;
    /** 少样本周期阈值，至少1，默认3。 */
    private final int lowSampleThreshold;

    /** @param repository 库存仓储 @param timeline 时间线应用服务 @param threshold 少样本周期阈值 */
    public InventoryApplicationService(InventoryRepository repository,
            InventoryTimelineService timeline,
            @Value("${family-agent.inventory.low-sample-threshold:3}") int threshold) {
        this.repository = repository;
        this.timeline = timeline;
        this.lowSampleThreshold = Math.max(1, threshold);
    }

    /** @return 已有启用商品目录，只读，不修改规则 */
    public List<Map<String, Object>> products() { return repository.products(); }

    /** @return 动态更新估算后的库存列表，提醒只计算不插入任务 */
    public List<Map<String, Object>> list() { return repository.ids().stream().map(this::detail).toList(); }

    /**
     * 只读计算首页补货看板。自动速度和时间线变化只在内存中模拟，避免打开首页写入库存缓存。
     *
     * @return 需要补货的商品，紧急商品优先，再按提醒日期排序；无提醒时返回空列表
     */
    @Transactional(readOnly = true)
    public List<ReplenishmentOverviewItem> replenishmentOverview() {
        return repository.ids().stream()
                .map(this::replenishmentItem)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ReplenishmentOverviewItem::immediate).reversed()
                        .thenComparing(ReplenishmentOverviewItem::reminderDate,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ReplenishmentOverviewItem::normalizedName))
                .toList();
    }

    /**
     * 按现有库存视图判定单个商品是否进入首页的补货分组。
     *
     * @param id 库存档案 ID
     * @return 即时或临近提醒；未到窗口、已延后或资料不足时返回 null
     */
    private ReplenishmentOverviewItem replenishmentItem(long id) {
        Map<String, Object> item = repository.find(id);
        BigDecimal currentRate = "MANUAL".equals(item.get("effective_source")) ? rate(item)
                : automaticEstimate(number(item, "normalized_product_id"), (String) item.get("base_unit"),
                        new ArrayList<>()).rate();
        var result = view(item, timeline.preview(item, List.of(), currentRate), currentRate);
        String status = (String) result.get("status");
        return switch (status) {
            case REPLENISH_NOW, REPLENISH_SOON, DEPLETION_OVERDUE, OUT_OF_STOCK ->
                    new ReplenishmentOverviewItem((String) result.get("normalized_name"),
                            !REPLENISH_SOON.equals(status), status, (BigDecimal) result.get("remaining_days"),
                            (LocalDate) result.get("depletion_date"), (LocalDate) result.get("reminder_date"));
            default -> null;
        };
    }

    /**
     * 只读模拟一个商品勾选历史后的今日库存，不创建档案、不更新速度缓存或建立关联。
     * @param productId 已唯一归一化商品ID
     * @param additions 本次未同步的有效入库快照，数量采用已有库存或规则对应计算单位
     * @return 商品标准单位的入库合计、同步后库存及待估算原因
     */
    public Map<String, Object> previewReceipts(long productId, List<Purchase> additions) {
        var product = repository.product(productId);
        Long id = repository.findProductInventory(productId);
        var item = id == null ? null : repository.find(id);
        String displayUnit = InventoryPolicy.standardUnit((String) product.get("standard_unit"), (String) product.get("unit_family"));
        String unit = item == null ? InventoryPolicy.baseUnit(displayUnit) : (String) item.get("base_unit");
        BigDecimal effectiveRate = item != null && "MANUAL".equals(item.get("effective_source"))
                ? decimal(item, "manual_daily_rate") : automaticEstimate(productId, unit, new ArrayList<>()).rate();
        var projection = timeline.preview(item, additions, effectiveRate);
        BigDecimal purchased = additions.stream().map(Purchase::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("normalizedProductId", productId);
        result.put("normalizedName", product.get("normalized_name"));
        result.put("unit", displayUnit);
        result.put("purchaseQuantity", InventoryPolicy.convert(purchased, unit, displayUnit, null));
        result.put("estimatedQuantity", projection.quantity() == null ? null : InventoryPolicy.convert(projection.quantity(), unit, displayUnit, null));
        result.put("pending", projection.pending());
        result.put("reason", projection.pending() ? "消耗待设置，暂无法估算当前库存" : projection.quantity() == null ? "库存未知，请先确认当前数量" : "按已同步及本次勾选记录的入库日期估算");
        return result;
    }

    /** @param id 库存ID @return 详情；查询时刷新自动缓存，人工参数不被覆盖 */
    public Map<String, Object> detail(long id) {
        refresh(id);
        Map<String, Object> item = repository.find(id);
        return view(item, timeline.project(item), rate(item));
    }

    /**
     * 执行事务性库存命令，重复键与相同参数返回当前详情，参数不一致拒绝。
     * @param id 库存档案ID
     * @param operation set、add、reset、settings或snooze；reset归零并释放当前商品的购买入库关联
     * @param command 已由用户确认的输入
     * @return 库存详情，所有写入均不触碰购买记录和价格
     */
    public Map<String, Object> execute(long id, String operation, InventoryCommand command) {
        if (command.requestKey() == null || command.requestKey().isBlank() || command.requestKey().startsWith("purchase:") || command.requestKey().length() > MAX_REQUEST_KEY_LENGTH) {
            throw new IllegalArgumentException("请提供不超过120字符的幂等请求键");
        }
        String fingerprint = id + ":" + operation + ":" + command;
        var previous = repository.request(command.requestKey());
        if (!previous.isEmpty()) {
            if (!fingerprint.equals(previous.get(0).get("fingerprint"))) { throw new IllegalArgumentException("幂等键已被其他参数使用"); }
            return detail(((Number) previous.get(0).get("inventory_item_id")).longValue());
        }
        // 先写幂等占位以获取SQLite写锁，失败时整个事务回滚，不遗留占位。
        repository.update("INSERT INTO inventory_requests(request_key,fingerprint,created_at) VALUES(?,?,?)", command.requestKey(), fingerprint, now());
        {
            refresh(id);
            var item = repository.find(id);
            switch (operation) {
                case "set", "add" -> changeQuantity(id, item, operation, command);
                case "reset" -> resetInventory(id);
                case "settings" -> settings(id, item, command);
                case "snooze" -> {
                    if (command.snoozeDays() == null || command.snoozeDays() <= 0 || command.snoozeDays() > MAX_REMINDER_DAYS) { throw new IllegalArgumentException("延后天数应为1到3650"); }
                    repository.update("UPDATE replenishment_settings SET snoozed_until=?,updated_at=? WHERE inventory_item_id=?", today().plusDays(command.snoozeDays()).toString(), now(), id);
                }
                default -> throw new IllegalArgumentException("未知库存操作");
            }
        }
        repository.update("UPDATE inventory_requests SET inventory_item_id=? WHERE request_key=?", id, command.requestKey());
        return detail(id);
    }

    /**
     * 独立手动入库，按商品查找或创建档案；重复请求不重复累加。
     * @param command 入库数量、标准商品和可选信息，不含购买关联
     * @return 增加后的库存详情；单位冲突或未知库存拒绝，事务整体回滚
     */
    public Map<String, Object> receive(InventoryReceiptCommand command) {
        if (command.requestKey() == null || command.requestKey().isBlank() || command.requestKey().startsWith("purchase:") || command.requestKey().length() > MAX_REQUEST_KEY_LENGTH) {
            throw new IllegalArgumentException("请提供不超过120字符的幂等请求键");
        }
        String fingerprint = "receipt:" + command;
        var previous = repository.request(command.requestKey());
        if (!previous.isEmpty()) {
            if (!fingerprint.equals(previous.get(0).get("fingerprint"))) { throw new IllegalArgumentException("幂等键已被其他参数使用"); }
            return detail(((Number) previous.get(0).get("inventory_item_id")).longValue());
        }
        repository.update("INSERT INTO inventory_requests(request_key,fingerprint,created_at) VALUES(?,?,?)", command.requestKey(), fingerprint, now());
        if (command.normalizedProductId() == null) { throw new IllegalArgumentException("请选择归一化商品"); }
        LocalDate received = command.receiptDate() == null ? today() : command.receiptDate();
        if (received.isAfter(today()) || (command.note() != null && command.note().length() > MAX_RECEIPT_NOTE_LENGTH)) {
            throw new IllegalArgumentException("入库日期不能在未来，备注最多500字符");
        }
        long id = addReceipt(command.normalizedProductId(), command.quantity(), command.unit(), command.specification(), received, command.requestKey());
        repository.update("UPDATE inventory_items SET last_received_date=?,last_receipt_note=? WHERE id=?", received.toString(), command.note(), id);
        repository.update("UPDATE inventory_requests SET inventory_item_id=? WHERE request_key=?", id, command.requestKey());
        return detail(id);
    }

    /**
     * 将记录样本产生的真实购买明细自动入库，仅供应用编排调用，不暴露历史关联API。
     * @param purchaseId 已保存的有效购买明细ID
     * @throws IllegalArgumentException 规格无法换算、库存未知或明细状态无效时抛出，外层购买事务回滚
     */
    public void receivePurchase(long purchaseId) {
        if (repository.linkedInventory(purchaseId) != null) { return; }
        var purchase = repository.receiptPurchase(purchaseId);
        String reason = exclusion(purchase);
        if (!reason.isEmpty()) { throw new IllegalArgumentException(reason); }
        // 以购买明细ID占用写锁；失败事务会同时撤销关联与库存变更。
        repository.update("INSERT OR IGNORE INTO inventory_requests(request_key,fingerprint,created_at) VALUES(?,?,?)", "purchase:" + purchaseId, "purchase:" + purchaseId, now());
        if (repository.linkedInventory(purchaseId) != null) { return; }
        long id = addReceipt(number(purchase, "normalized_product_id"), decimal(purchase, "quantity"),
                (String) purchase.get("unit"), (String) purchase.get("sku"), date(purchase.get("order_time")), "purchase:" + purchaseId);
        BigDecimal quantity = InventoryPolicy.convert(decimal(purchase, "quantity"), (String) purchase.get("unit"),
                (String) repository.find(id).get("base_unit"), (String) purchase.get("sku"));
        repository.update("INSERT INTO inventory_purchase_links(purchase_record_id,inventory_item_id,added_quantity,created_at) VALUES(?,?,?,?)", purchaseId, id, text(quantity), now());
        repository.update("UPDATE inventory_requests SET inventory_item_id=? WHERE request_key=?", id, "purchase:" + purchaseId);
        // 历史同步不一定随后查询详情，事务内即补齐首次有效速度及当前兼容缓存。
        refresh(id);
    }

    /**
     * 以同一计量维度向唯一商品库存增加数量，新建时采用归一化标准单位。
     * @param productId 归一化规则ID
     * @param quantity 入库非负数量
     * @param unit 数量单位
     * @param specification 明确单包装规格，可空
     * @param received 实际购买或入库日，非空
     * @param key 唯一入库请求键，非空
     * @return 库存ID；不兼容单位拒绝，不改已有基准单位
     */
    private long addReceipt(long productId, BigDecimal quantity, String unit, String specification, LocalDate received, String key) {
        var product = repository.product(productId);
        String standardUnit = InventoryPolicy.standardUnit((String) product.get("standard_unit"), (String) product.get("unit_family"));
        Long id = repository.findProductInventory(productId);
        String target = id == null ? InventoryPolicy.baseUnit(standardUnit)
                : (String) repository.find(id).get("base_unit");
        BigDecimal added = InventoryPolicy.convert(quantity, unit, target, specification);
        if (id == null) { id = repository.create(productId, "0", target, now()); }
        timeline.receive(id, added, received, key);
        return id;
    }

    /** 数量变更先消耗旧基准；手动数量维护不建立购买关联。 */
    private void changeQuantity(long id, Map<String, Object> item, String operation, InventoryCommand command) {
        String unit = (String) item.get("base_unit");
        BigDecimal added = command.quantity() == null && operation.equals("set") ? null
                : InventoryPolicy.convert(command.quantity(), command.unit(), unit, null);
        if (!operation.equals("set")) {
            if (added == null || added.signum() <= 0) { throw new IllegalArgumentException("增加数量必须大于零"); }
            timeline.receive(id, added, today(), command.requestKey());
        } else {
            // 只有人工确认当前数量才建立可信锚点；历史入库不能覆盖该锚点。
            timeline.confirm(id, added);
        }
    }

    /**
     * 将当前商品库存归零并释放购买入库关联，使用户能重新勾选该商品的历史购买。
     * 外层execute事务同时保护数量、关联与请求占位；保留旧请求以防延迟重放再次清空库存。
     *
     * @param id 已存在的库存档案ID，只影响该档案，不修改购买记录、价格、单位及消耗参数
     * @throws org.springframework.dao.DataAccessException 写入失败时抛出，外层事务回滚全部重置操作
     */
    private void resetInventory(long id) {
        timeline.reset(id);
        repository.update("DELETE FROM inventory_purchase_links WHERE inventory_item_id=?", id);
    }

    /** 保留自动与人工参数，速度变化通过分段记录从今日生效；首次有效速度可回推待估算历史。 */
    private void settings(long id, Map<String, Object> item, InventoryCommand command) {
        if (!Set.of("AUTO", "MANUAL").contains(command.source() == null ? "" : command.source()) || command.leadDays() == null || command.leadDays() < 0 || command.leadDays() > MAX_REMINDER_DAYS) {
            throw new IllegalArgumentException("请选择AUTO或MANUAL，提前天数应为0到3650");
        }
        BigDecimal rate = command.source().equals("MANUAL") ? InventoryPolicy.manualRate(command.referenceQuantity(), command.referenceUnit(), command.referenceDays(), (String) item.get("base_unit")) : null;
        if (rate != null) {
            repository.update("UPDATE replenishment_settings SET manual_reference_quantity=?,manual_reference_unit=?,manual_reference_days=?,manual_daily_rate=? WHERE inventory_item_id=?",
                    text(command.referenceQuantity()), command.referenceUnit(), text(command.referenceDays()), text(rate), id);
        }
        repository.update("UPDATE replenishment_settings SET effective_source=?,reminder_lead_days=?,updated_at=? WHERE inventory_item_id=?", command.source(), command.leadDays(), now(), id);
        timeline.rememberRate(id, rate(repository.find(id)));
    }

    /** 重算自动估算并保存有效速度分段；首次有效速度回推历史，后续变化从今日生效，人工模式仍更新自动缓存。 */
    private void refresh(long id) {
        var item = repository.find(id);
        List<String> warnings = new ArrayList<>();
        var estimate = automaticEstimate(number(item, "normalized_product_id"), (String) item.get("base_unit"), warnings);
        repository.update("""
                INSERT INTO replenishment_predictions(inventory_item_id,auto_daily_rate,auto_sample_count,purchase_record_count,sample_label,warnings,calculated_at)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(inventory_item_id) DO UPDATE SET auto_daily_rate=excluded.auto_daily_rate,
                auto_sample_count=excluded.auto_sample_count,purchase_record_count=excluded.purchase_record_count,
                sample_label=excluded.sample_label,warnings=excluded.warnings,calculated_at=excluded.calculated_at
                """, id, text(estimate.rate()), estimate.sampleCount(), estimate.purchaseCount(), estimate.label(), String.join("；", warnings), now());
        timeline.rememberRate(id, "MANUAL".equals(item.get("effective_source")) ? decimal(item, "manual_daily_rate") : estimate.rate());
    }

    /** @param productId 商品ID @param unit 计算单位 @param warnings 接收排除原因 @return 只读自动速度估算，不把购买历史自动入库 */
    private InventoryPolicy.Estimate automaticEstimate(long productId, String unit, List<String> warnings) {
        List<Purchase> valid = new ArrayList<>();
        for (var purchase : repository.purchases(productId)) {
            String reason = exclusion(purchase);
            try {
                if (!reason.isEmpty()) { throw new IllegalArgumentException(reason); }
                BigDecimal quantity = InventoryPolicy.convert(decimal(purchase, "quantity"), (String) purchase.get("unit"), unit, (String) purchase.get("sku"));
                valid.add(new Purchase(date(purchase.get("order_time")), quantity));
            } catch (IllegalArgumentException e) {
                warnings.add("明细" + purchase.get("id") + ":" + e.getMessage());
            }
        }
        return InventoryPolicy.estimate(valid, lowSampleThreshold);
    }

    /**
     * 构建详情与首页共用的动态库存视图，按完整天数和提醒日期判定状态。
     *
     * @param item 已保存的库存与配置快照
     * @param projection 当前时间线投影
     * @param rate 当前生效的消耗速度，单位为基础单位/天，未知时为空
     * @return 附有余量和提醒状态的只读展示数据
     */
    private Map<String, Object> view(Map<String, Object> item, InventoryPolicy.Projection projection, BigDecimal rate) {
        Map<String, Object> result = new LinkedHashMap<>(item);
        BigDecimal quantity = projection.quantity();
        result.put("estimated_quantity", quantity);
        result.put("estimate_pending", projection.pending());
        result.put("received_quantity", projection.receivedQuantity());
        result.put("history_rebuild_required", number(item, "history_rebuild_required") != 0);
        result.put("effective_daily_rate", rate);
        result.put("remaining_days", null);
        result.put("depletion_date", null);
        result.put("reminder_date", null);
        result.put("status", projection.pending() ? "消耗待设置，暂无法估算当前库存" : quantity == null ? "库存未知" : "暂无有效消耗速度");
        if (quantity != null && rate != null && rate.signum() > 0) {
            LocalDate depletion = projection.depletionDate();
            LocalDate reminder = depletion.minusDays(number(item, "reminder_lead_days"));
            result.put("remaining_days", InventoryPolicy.completeDays(quantity, rate));
            result.put("depletion_date", depletion);
            result.put("reminder_date", reminder);
            result.put("status", depletion.isBefore(today()) ? DEPLETION_OVERDUE : !reminder.isAfter(today()) ? REPLENISH_NOW : !reminder.isAfter(today().plusDays(UPCOMING_REMINDER_DAYS)) ? REPLENISH_SOON : "暂时不需要");
            Object snoozed = item.get("snoozed_until");
            if (snoozed != null && date(snoozed).isAfter(today())) { result.put("status", "已延后提醒"); }
        } else if (quantity != null && quantity.signum() == 0) { result.put("status", OUT_OF_STOCK); }
        appendStandardDisplay(result, item);
        return result;
    }

    /**
     * 将库存和全部日均速度一并换算为商品规则标准单位，不重写已有数量、速率或购买关联。
     * 单位不兼容时返回提示并停止该商品的日期预测，不向外抛出业务单位异常。
     * @param result 已完成预测的响应，非空，保留原字段供兼容客户端使用
     * @param item 含规则单位族和原始库存单位的快照，非空
     */
    private void appendStandardDisplay(Map<String, Object> result, Map<String, Object> item) {
        result.put("unit_compatible", false);
        result.put("unit_warning", "");
        result.put("display_unit", item.get("base_unit"));
        for (String key : List.of("current_quantity", "estimated_quantity", "received_quantity", "auto_daily_rate", "manual_daily_rate", "effective_daily_rate")) {
            result.put("display_" + key, null);
        }
        try {
            String unit = InventoryPolicy.standardUnit((String) item.get("standard_unit"), (String) item.get("unit_family"));
            // 一次计算转换倍率，库存数量和各种消耗速度使用完全相同的BigDecimal倍率。
            BigDecimal factor = InventoryPolicy.convert(BigDecimal.ONE, (String) item.get("base_unit"), unit, null);
            for (String key : List.of("current_quantity", "estimated_quantity", "received_quantity", "auto_daily_rate", "manual_daily_rate", "effective_daily_rate")) {
                BigDecimal value = decimal(result, key);
                result.put("display_" + key, value == null ? null : value.multiply(factor));
            }
            result.put("display_unit", unit);
            result.put("unit_compatible", true);
        } catch (IllegalArgumentException exception) {
            // 历史包装库存缺少净含量时保留原单位和数量，不能贴上标准单位标签。
            result.put("display_current_quantity", decimal(item, "current_quantity"));
            result.put("display_estimated_quantity", result.get("estimated_quantity"));
            result.put("unit_warning", "库存单位与商品规则不兼容：" + exception.getMessage());
            result.put("remaining_days", null);
            result.put("depletion_date", null);
            result.put("reminder_date", null);
            result.put("status", "单位不兼容，请确认商品规格");
        }
    }

    /** 沿用现有有效记录状态，不从金额或备注猜测退款；拒绝未来、无效日期及非正数量。 */
    private String exclusion(Map<String, Object> purchase) {
        if (!"include".equals(purchase.get("decision")) || number(purchase, "is_duplicate") != 0 || !"unique".equals(purchase.get("dedupe_status"))) { return "现有规则排除或重复记录（退款状态未独立建模）"; }
        try {
            if (date(purchase.get("order_time")).isAfter(today())) { return "购买日期在未来"; }
            if (decimal(purchase, "quantity") == null || decimal(purchase, "quantity").signum() <= 0) { return "购买数量无效"; }
        } catch (RuntimeException e) { return "日期或数量无效"; }
        return "";
    }

    /** @return 上海当前自然日 */
    private LocalDate today() { return LocalDate.now(ZONE); }
    /** @return 含时区偏移的当前时间文本 */
    private String now() { return ZonedDateTime.now(ZONE).toOffsetDateTime().toString(); }
    /** @return 日期文本的上海自然日部分，无效时抛参数异常 */
    private LocalDate date(Object value) {
        try { return LocalDate.parse(value.toString().substring(0, 10)); }
        catch (RuntimeException e) { throw new IllegalArgumentException("日期无效", e); }
    }
    /** @return 数据库十进制文本的精确值，空值保留未知语义 */
    private BigDecimal decimal(Map<String, Object> item, String key) {
        Object value = item.get(key); return value == null ? null : new BigDecimal(value.toString());
    }
    /** @return 数据库整数，不存在时为0 */
    private long number(Map<String, Object> item, String key) {
        Object value = item.get(key); return value == null ? 0 : ((Number) value).longValue();
    }
    /** @return 无科学计数的十进制文本或null */
    private String text(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    /** @return 当前商品选中的速度，不回退到另一个来源 */
    private BigDecimal rate(Map<String, Object> item) { return decimal(item, "MANUAL".equals(item.get("effective_source")) ? "manual_daily_rate" : "auto_daily_rate"); }
}
