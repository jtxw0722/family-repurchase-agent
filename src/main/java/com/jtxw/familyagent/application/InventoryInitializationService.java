package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.policy.InventoryPolicy;
import com.jtxw.familyagent.infrastructure.persistence.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 22:20:17
 * @Description: 库存初始化应用服务，协调历史购买区间预览和用户明确勾选后的幂等同步，不改变购买与价格规则
 */
@Service
public class InventoryInitializationService {
    /** 初始化日期及幂等记录统一使用的上海时区，非空。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 用户请求键长度上限，单位字符，固定为120。 */
    private static final int MAX_REQUEST_KEY_LENGTH = 120;
    /** 初始化幂等键命名空间，避免与自动购买明细键混用。 */
    private static final String REQUEST_PREFIX = "initialization:";
    /** 购买记录明确非重复的既有状态，其他状态不进入同步候选列表。 */
    private static final String UNIQUE_DEDUPE_STATUS = "unique";
    /** 库存仓储，非空，预览时只读购买与库存，确认时写入幂等请求记录。 */
    private final InventoryRepository repository;
    /** 原库存应用服务，非空，复用标准单位转换、唯一库存档案及购买明细去重。 */
    private final InventoryApplicationService inventory;

    /**
     * 创建初始化应用服务，无读写副作用。
     * @param repository 库存仓储，非空
     * @param inventory 原库存入库用例，非空
     */
    public InventoryInitializationService(InventoryRepository repository, InventoryApplicationService inventory) {
        this.repository = repository;
        this.inventory = inventory;
    }

    /**
     * 查询历史购买候选，不创建库存、不刷新预测、不建立明细关联。
     * @param startDate 起始日，可空，默认结束日往前一个日历年
     * @param endDate 结束日，可空，默认上海今日，包含当天
     * @return 仅包含已唯一归一化且明确非重复的明细；不兼容或已同步明细带禁止选择原因
     * @throws IllegalArgumentException 起止日倒置或结束日在未来时抛出
     */
    @Transactional(readOnly = true)
    public Preview preview(LocalDate startDate, LocalDate endDate) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate end = endDate == null ? today : endDate;
        LocalDate start = startDate == null ? end.minusYears(1) : startDate;
        if (start.isAfter(end) || end.isAfter(today)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期，结束日期不能晚于上海今日");
        }
        Map<Long, List<Map<String, Object>>> purchaseMatches = new LinkedHashMap<>();
        for (var purchase : repository.initializationPurchases(start.toString(), end.toString())) {
            long purchaseId = ((Number) purchase.get("id")).longValue();
            purchaseMatches.computeIfAbsent(purchaseId, key -> new ArrayList<>()).add(purchase);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (var matches : purchaseMatches.values()) {
            if (isVisiblePurchase(matches)) {
                candidates.add(candidate(matches.get(0), true));
            }
        }
        return new Preview(start, end, candidates);
    }

    /**
     * 判断明细是否应展示于购买记录同步列表，不修改购买记录或归一化规则。
     * 旧数据可能将原商品标题透传到normalized_name，不能仅凭名称非空认定已归一化。
     *
     * @param matches 同一购买明细匹配的启用规则结果，非空集合；多条表示归一化冲突
     * @return 仅当名称非空、命中唯一启用规则且既有去重状态明确为非重复时返回true
     */
    private boolean isVisiblePurchase(List<Map<String, Object>> matches) {
        if (matches.size() != 1) {
            return false;
        }
        var purchase = matches.get(0);
        String normalizedName = (String) purchase.get("normalized_name");
        return normalizedName != null && !normalizedName.isBlank()
                && purchase.get("normalized_product_id") != null
                && Integer.valueOf(0).equals(purchase.get("is_duplicate"))
                && UNIQUE_DEDUPE_STATUS.equals(purchase.get("dedupe_status"));
    }

    /**
     * 仅同步用户明确提交的购买明细，整批事务写入；重复请求及已同步明细均不重复加量。
     * @param command 起止日、勾选ID和幂等请求键，非空；日期沿用预览区间
     * @return 本次增加数量与已同步跳过数量，不返回金额信息
     * @throws IllegalArgumentException 选择为空、键冲突、明细越界或状态/规格已失效时抛出并回滚整批
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult confirm(ConfirmCommand command) {
        if (command == null || command.requestKey() == null || command.requestKey().isBlank()
                || command.requestKey().length() > MAX_REQUEST_KEY_LENGTH) {
            throw new IllegalArgumentException("请提供不超过120字符的初始化请求键");
        }
        if (command.purchaseRecordIds() == null || command.purchaseRecordIds().isEmpty()
                || command.purchaseRecordIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("请至少勾选一条有效购买记录");
        }
        if (command.startDate() == null || command.endDate() == null) {
            throw new IllegalArgumentException("请提交预览时的开始日期和结束日期");
        }
        // 去重并排序使同一组选择使用稳定指纹，不受勾选顺序影响。
        var selectedIds = new TreeSet<>(command.purchaseRecordIds());
        String requestKey = REQUEST_PREFIX + command.requestKey();
        String fingerprint = command.startDate() + ":" + command.endDate() + ":" + selectedIds;
        int inserted = repository.update("INSERT OR IGNORE INTO inventory_requests(request_key,fingerprint,created_at) VALUES(?,?,?)",
                requestKey, fingerprint, OffsetDateTime.now(ZONE).toString());
        var previous = repository.request(requestKey).get(0);
        if (!fingerprint.equals(previous.get("fingerprint"))) {
            throw new IllegalArgumentException("初始化请求键已被其他参数使用");
        }
        // 占用SQLite写锁后才校验当前明细，避免预览后变更或同时入库导致重复累加。
        if (inserted == 0) {
            return new SyncResult(0, selectedIds.size());
        }
        Map<Long, Candidate> currentCandidates = new LinkedHashMap<>();
        for (var candidate : preview(command.startDate(), command.endDate()).records()) {
            currentCandidates.put(candidate.purchaseRecordId(), candidate);
        }
        int synchronizedCount = 0;
        int alreadySyncedCount = 0;
        for (long purchaseId : selectedIds) {
            Candidate candidate = currentCandidates.get(purchaseId);
            if (candidate == null) {
                throw new IllegalArgumentException("勾选明细不在当前日期范围或已删除，请重新查询");
            }
            if (candidate.linked()) {
                alreadySyncedCount++;
                continue;
            }
            if (!candidate.selectable()) {
                throw new IllegalArgumentException("明细" + purchaseId + "无法同步：" + candidate.reason());
            }
            inventory.receivePurchase(purchaseId);
            synchronizedCount++;
        }
        return new SyncResult(synchronizedCount, alreadySyncedCount);
    }

    /**
     * 仅在内存中按商品模拟勾选后的剩余库存，不消耗请求键或修改任何库存数据。
     * @param command 日期区间和勾选ID，请求键不需要；空选择返回空商品集合
     * @return 按商品标准单位展示的购买入库量和截至今日预计库存
     * @throws IllegalArgumentException 选择失效、日期范围无效或单位不兼容时抛出
     */
    @Transactional(readOnly = true)
    public Map<String, Object> estimate(ConfirmCommand command) {
        if (command == null || command.startDate() == null || command.endDate() == null || command.purchaseRecordIds() == null) {
            throw new IllegalArgumentException("请提交日期范围及勾选记录");
        }
        if (command.purchaseRecordIds().stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("请选择有效购买记录");
        }
        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        preview(command.startDate(), command.endDate()).records().forEach(row -> candidates.put(row.purchaseRecordId(), row));
        Map<Long, List<InventoryPolicy.Purchase>> grouped = new LinkedHashMap<>();
        for (Long id : new TreeSet<>(command.purchaseRecordIds())) {
            Candidate row = candidates.get(id);
            if (row == null) { throw new IllegalArgumentException("勾选明细已失效，请重新查询"); }
            if (row.linked()) { continue; }
            if (!row.selectable()) { throw new IllegalArgumentException(row.reason()); }
            grouped.computeIfAbsent(row.normalizedProductId(), key -> new ArrayList<>())
                    .add(new InventoryPolicy.Purchase(LocalDate.parse(row.purchaseDate().substring(0, 10)), row.inventoryQuantity()));
        }
        return Map.of("products", grouped.entrySet().stream().map(entry -> inventory.previewReceipts(entry.getKey(), entry.getValue())).toList());
    }

    /**
     * 转换预览明细并保守判断是否可同步，不推测包装规格，不修改库存。
     * @param purchase 原表只读明细及匹配规则、库存信息，非空
     * @param uniqueMatch 是否只匹配一个归一化规则
     * @return 可选择明细，或包含具体禁止原因的明细
     */
    private Candidate candidate(Map<String, Object> purchase, boolean uniqueMatch) {
        BigDecimal quantity = null;
        BigDecimal inventoryQuantity = null;
        BigDecimal displayInventoryQuantity = null;
        String baseUnit = null;
        String displayUnit = null;
        String reason = "";
        Long productId = !uniqueMatch || purchase.get("normalized_product_id") == null ? null
                : ((Number) purchase.get("normalized_product_id")).longValue();
        boolean linked = purchase.get("linked_inventory_id") != null;
        try {
            if (purchase.get("quantity") != null) {
                quantity = new BigDecimal(purchase.get("quantity").toString());
            }
            if (!uniqueMatch || productId == null) {
                throw new IllegalArgumentException("商品尚未唯一归一化，请先确认规则");
            }
            if (!"include".equals(purchase.get("decision")) || !UNIQUE_DEDUPE_STATUS.equals(purchase.get("dedupe_status"))
                    || !Integer.valueOf(0).equals(purchase.get("is_duplicate"))) {
                throw new IllegalArgumentException("现有购买规则已排除或判为重复");
            }
            try {
                LocalDate.parse(purchase.get("order_time").toString().substring(0, 10));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException("购买日期无效", exception);
            }
            if (quantity == null || quantity.signum() <= 0) {
                throw new IllegalArgumentException("购买数量必须大于零");
            }
            displayUnit = InventoryPolicy.standardUnit((String) purchase.get("standard_unit"), (String) purchase.get("unit_family"));
            baseUnit = purchase.get("base_unit") == null
                    ? InventoryPolicy.baseUnit(displayUnit) : (String) purchase.get("base_unit");
            inventoryQuantity = InventoryPolicy.convert(quantity, (String) purchase.get("unit"), baseUnit, (String) purchase.get("sku"));
            displayInventoryQuantity = InventoryPolicy.convert(inventoryQuantity, baseUnit, displayUnit, null);
            if (purchase.get("anchor_at") != null && purchase.get("anchor_quantity") == null) {
                throw new IllegalArgumentException("当前库存未知，请先确认现有数量");
            }
            if (purchase.get("anchor_at") != null && !LocalDate.parse(purchase.get("order_time").toString().substring(0, 10))
                    .isAfter(LocalDate.parse(purchase.get("anchor_at").toString().substring(0, 10)))) {
                throw new IllegalArgumentException("该日期已由最近确认的当前库存覆盖；如需重建，请先重置库存");
            }
        } catch (IllegalArgumentException exception) {
            reason = exception.getMessage();
        }
        if (linked) {
            reason = "已同步库存，不会重复入库";
        }
        return new Candidate(((Number) purchase.get("id")).longValue(), (String) purchase.get("order_time"),
                (String) purchase.get("category"), (String) purchase.get("normalized_name"), productId,
                (String) purchase.get("product_name"), quantity, (String) purchase.get("unit"), (String) purchase.get("sku"),
                inventoryQuantity, baseUnit, reason.isEmpty(), reason, linked, displayInventoryQuantity, displayUnit);
    }

    /**
     * @Author: jtxw
     * @Date: 2026/11/18 22:20:17
     * @Description: 库存初始化预览响应，明确默认生效日期与待确认候选，不承载写入状态
     * @param startDate 实际查询开始自然日，非空，包含当天
     * @param endDate 实际查询结束自然日，非空，包含当天
     * @param records 区间内已唯一归一化且非重复的候选明细，非空，可为空集合；前端默认全部未勾选
     */
    public record Preview(LocalDate startDate, LocalDate endDate, List<Candidate> records) { }

    /**
     * @Author: jtxw
     * @Date: 2026/11/18 22:20:17
     * @Description: 历史购买初始化候选，展示数量换算及禁止原因，避免将不可靠明细直接累加
     * @param purchaseRecordId 购买明细ID，正整数
     * @param purchaseDate 原购买日期文本，允许无效但会标记不可选
     * @param category 商品品类，非空，缺失时未分类
     * @param normalizedName 已归一化名称，返回候选中非空
     * @param normalizedProductId 唯一启用归一化规则ID，返回候选中非空
     * @param productName 原商品名称，非空
     * @param quantity 原购买数量，可空；不参与价格计算
     * @param unit 原数量单位，可空
     * @param specification 原SKU或单包装规格，可空
     * @param inventoryQuantity 可换算的入库数量，可空，单位由baseUnit说明
     * @param baseUnit 库存基础单位，可空，复用既有档案或规则标准单位
     * @param selectable 是否允许勾选，仅有效且未同步明细为true
     * @param reason 禁止选择的中文原因，可选时为空字符串
     * @param linked 是否已关联入库，为true时永不重复增加数量
     * @param displayInventoryQuantity 按规则标准单位换算的入库数量，不兼容时为空
     * @param displayUnit 商品规则对应的标准展示单位，规则无效时为空
     */
    public record Candidate(long purchaseRecordId, String purchaseDate, String category, String normalizedName,
            Long normalizedProductId, String productName, BigDecimal quantity, String unit, String specification,
            BigDecimal inventoryQuantity, String baseUnit, boolean selectable, String reason, boolean linked,
            BigDecimal displayInventoryQuantity, String displayUnit) { }

    /**
     * @Author: jtxw
     * @Date: 2026/11/18 22:20:17
     * @Description: 初始化确认命令，只承载用户明确选择的明细ID和预览区间，不信任前端提供的数量
     * @param requestKey 用户请求键，非空且不超过120字符
     * @param startDate 预览开始日，非空
     * @param endDate 预览结束日，非空
     * @param purchaseRecordIds 明确勾选的正整数ID，非空且至少一条，重复ID合并
     */
    public record ConfirmCommand(String requestKey, LocalDate startDate, LocalDate endDate, List<Long> purchaseRecordIds) { }

    /**
     * @Author: jtxw
     * @Date: 2026/11/18 22:20:17
     * @Description: 初始化同步结果，区分本次新增入库与幂等跳过的明细数量
     * @param synchronizedCount 本次实际同步购买明细数，非负
     * @param alreadySyncedCount 已同步或重复请求跳过的明细数，非负
     */
    public record SyncResult(int synchronizedCount, int alreadySyncedCount) { }
}
