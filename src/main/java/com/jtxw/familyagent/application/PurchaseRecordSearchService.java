package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.query.SearchPurchaseRecordsQuery;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/12 13:09:27
 * @Description: 原始购买记录检索应用服务，负责清洗查询参数并返回只读历史订单样本。
 */
@Service
public class PurchaseRecordSearchService {
    /**
     * 默认返回条数，limit 未传时使用，避免工具一次返回过多原始订单样本。
     */
    private static final int DEFAULT_LIMIT = 20;
    /**
     * 最大返回条数，limit 超过该值时截断，防止 LLM 工具调用读取过多本地数据。
     */
    private static final int MAX_LIMIT = 50;
    /**
     * 全家庭样本查询范围标识，表示未限制 owner。
     */
    private static final String SCOPE_FAMILY = "FAMILY";
    /**
     * 指定归属人样本查询范围标识，表示只查询某个 owner。
     */
    private static final String SCOPE_OWNER = "OWNER";

    private final PurchaseRecordRepository purchaseRecordRepository;

    /**
     * 创建原始购买记录检索应用服务。
     *
     * @param purchaseRecordRepository 购买记录仓储，用于执行只读关键词检索
     */
    public PurchaseRecordSearchService(PurchaseRecordRepository purchaseRecordRepository) {
        this.purchaseRecordRepository = purchaseRecordRepository;
    }

    /**
     * 按关键词检索原始购买记录样本。
     *
     * <p>该方法只返回 purchase_records 中的原始订单样本，不生成价格基线，
     * 不参与 compare_price 的正式价格判断。</p>
     *
     * @param query 原始购买记录检索查询，keyword 必填，owner 可选
     * @return 原始购买记录检索结果，无匹配时 records 为空数组
     */
    public SearchPurchaseRecordsResult search(SearchPurchaseRecordsQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("检索请求不能为空");
        }
        String keyword = normalizeKeyword(query.keyword());
        String owner = normalizeOwner(query.owner());
        int limit = normalizeLimit(query.limit());
        DateRange dateRange = normalizeDateRange(query.fromDate(), query.toDate());

        int matchedCount = purchaseRecordRepository.countByKeyword(
                keyword,
                owner,
                dateRange.fromOrderTime(),
                dateRange.toOrderTime()
        );
        List<SearchPurchaseRecordsResult.Item> records = purchaseRecordRepository.listByKeyword(
                        keyword,
                        owner,
                        dateRange.fromOrderTime(),
                        dateRange.toOrderTime(),
                        limit
                )
                .stream()
                .map(this::toItem)
                .toList();

        return new SearchPurchaseRecordsResult(
                keyword,
                owner == null ? SCOPE_FAMILY : SCOPE_OWNER,
                owner,
                matchedCount,
                records.size(),
                records,
                warnings(owner)
        );
    }

    /**
     * 清洗检索关键词，避免空关键词导致全表扫描。
     *
     * @param keyword 原始关键词
     * @return trim 后的关键词
     */
    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("keyword 不能为空");
        }
        return keyword.trim();
    }

    /**
     * 清洗 owner 条件，空字符串按全家庭查询处理。
     *
     * @param owner 原始 owner
     * @return trim 后的 owner；为空时返回 null
     */
    private String normalizeOwner(String owner) {
        if (owner == null || owner.trim().isEmpty()) {
            return null;
        }
        return owner.trim();
    }

    /**
     * 计算实际返回条数限制。
     *
     * @param limit 请求中的 limit，允许为空
     * @return 应用于 SQL LIMIT 的安全条数
     */
    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 将可选日期转换为订单时间边界。
     *
     * @param fromDate 开始日期，格式 yyyy-MM-dd，允许为空
     * @param toDate   结束日期，格式 yyyy-MM-dd，允许为空
     * @return 订单时间边界，未传日期时对应边界为空
     */
    private DateRange normalizeDateRange(String fromDate, String toDate) {
        LocalDate from = parseDate(fromDate, "fromDate");
        LocalDate to = parseDate(toDate, "toDate");
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("fromDate 不能晚于 toDate");
        }
        String fromOrderTime = from == null ? null : from + " 00:00:00";
        String toOrderTime = to == null ? null : to + " 23:59:59";
        return new DateRange(fromOrderTime, toOrderTime);
    }

    /**
     * 解析可选日期字段。
     *
     * @param value     原始日期文本
     * @param fieldName 字段名，用于错误提示
     * @return 日期值；未传或空字符串时返回 null
     */
    private LocalDate parseDate(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(fieldName + " 格式错误，请使用 yyyy-MM-dd。");
        }
    }

    /**
     * 将购买记录实体转换为工具响应明细。
     *
     * @param record 购买记录实体
     * @return 原始购买记录检索明细
     */
    private SearchPurchaseRecordsResult.Item toItem(PurchaseRecord record) {
        return new SearchPurchaseRecordsResult.Item(
                record.id(),
                record.orderTime(),
                record.platform(),
                record.owner(),
                record.productName(),
                record.sku(),
                record.category(),
                record.subCategory(),
                record.quantity(),
                record.unit(),
                record.totalAmount(),
                record.currency(),
                record.normalizedName(),
                record.unitPrice()
        );
    }

    /**
     * 生成 LLM 使用风险提示，明确该结果不是价格基线。
     *
     * @param owner 清洗后的 owner；为空表示全家庭查询
     * @return 风险提示列表
     */
    private List<String> warnings(String owner) {
        String scopeWarning = owner == null
                ? "当前查询范围为全家庭原始记录，可能包含不同家庭成员的购买记录。"
                : "当前查询范围为指定订单归属人的原始记录，owner 仅是检索过滤条件，仍可能包含不同规格、单位或组合装。";
        return List.of(
                "该结果来自原始订单记录检索，不代表已完成商品归一化。",
                scopeWarning,
                "不同规格、单位、赠品、组合装可能混在一起，不能直接等同于价格基线。"
        );
    }

    /**
     * @Author: jtxw
     * @Date: 2026/06/12 13:09:27
     * @Description: 原始购买记录检索的订单时间边界，供仓储层参数化查询使用。
     *
     * @param fromOrderTime 订单时间下界，格式 yyyy-MM-dd HH:mm:ss，允许为空
     * @param toOrderTime   订单时间上界，格式 yyyy-MM-dd HH:mm:ss，允许为空
     */
    private record DateRange(String fromOrderTime, String toOrderTime) {
    }
}
