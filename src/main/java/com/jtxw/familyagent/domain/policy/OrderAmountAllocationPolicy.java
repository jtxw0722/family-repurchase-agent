package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.RawPurchaseRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 17:19:22
 * @Description: 订单金额分摊领域策略，负责将订单级实付金额和运费按商品金额比例分摊到商品行。
 */
@Component
public class OrderAmountAllocationPolicy {
    /**
     * 金额来源：订单级实付金额和运费已按商品金额比例分摊到当前商品行。
     */
    public static final String SOURCE_ALLOCATED_ORDER_AMOUNT = "allocated_order_amount";
    /**
     * 金额来源：订单级金额无法安全分摊，当前记录保留原始金额并进入人工复核。
     */
    public static final String SOURCE_ORDER_AMOUNT_ALLOCATION_FALLBACK = "order_amount_allocation_fallback";
    /**
     * 复核原因码：同一订单内没有可用于分摊的有效商品金额。
     */
    public static final String REASON_ORDER_AMOUNT_ALLOCATION = "ORDER_AMOUNT_ALLOCATION_REVIEW";
    /**
     * 弱分组缺省前缀，用于避免无订单分组键的测试或历史构造数据被误合并。
     */
    private static final String MISSING_GROUP_PREFIX = "__missing_order_group__";
    /**
     * 金额计算精度，单位为元，保留到分。
     */
    private static final int MONEY_SCALE = 2;
    /**
     * 金额比较使用的 0 元常量。
     */
    private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO;
    /**
     * 新会员 0.01 元商品金额阈值，单位为元，命中时不参与订单金额分摊基数。
     */
    private static final BigDecimal NEW_MEMBER_AMOUNT_LIMIT = new BigDecimal("0.01");
    /**
     * 非复购或非正常商品关键词，命中时不参与订单金额分摊基数。
     */
    private static final List<String> INEFFECTIVE_LINE_KEYWORDS = List.of(
            "赠品", "定金", "尾款", "补差价", "差价", "邮费补拍", "邮费", "虚拟权益",
            "礼品卡", "卡密", "充值", "会员权益", "话费", "流量"
    );

    /**
     * 按订单分组分摊订单级实付金额和运费。
     *
     * <p>分摊只改写金额字段，不改变商品名称、规格、归一化或去重流程；若有效商品金额合计小于等于 0，
     * 保留原始金额并标记金额复核，避免静默写入明显错误金额。</p>
     *
     * @param rawRecords 原始订单记录列表，不允许为 null
     * @return 分摊后的原始订单记录列表，顺序与输入保持一致
     */
    public List<RawPurchaseRecord> allocate(List<RawPurchaseRecord> rawRecords) {
        Map<String, List<IndexedRecord>> recordsByOrderGroup = groupByOrder(rawRecords);
        RawPurchaseRecord[] allocatedRecords = new RawPurchaseRecord[rawRecords.size()];
        for (List<IndexedRecord> orderGroup : recordsByOrderGroup.values()) {
            List<RawPurchaseRecord> groupRecords = orderGroup.stream().map(IndexedRecord::record).toList();
            List<RawPurchaseRecord> allocatedGroup = allocateGroup(groupRecords);
            for (int index = 0; index < orderGroup.size(); index++) {
                allocatedRecords[orderGroup.get(index).index()] = allocatedGroup.get(index);
            }
        }
        return List.of(allocatedRecords);
    }

    /**
     * 按订单分组键聚合原始记录。
     *
     * @param rawRecords 原始订单记录列表
     * @return 按订单分组键聚合后的记录集合
     */
    private Map<String, List<IndexedRecord>> groupByOrder(List<RawPurchaseRecord> rawRecords) {
        Map<String, List<IndexedRecord>> recordsByOrderGroup = new LinkedHashMap<>();
        for (int index = 0; index < rawRecords.size(); index++) {
            RawPurchaseRecord record = rawRecords.get(index);
            String orderGroupKey = record.orderGroupKey();
            if (orderGroupKey == null || orderGroupKey.isBlank()) {
                orderGroupKey = MISSING_GROUP_PREFIX + index;
            }
            recordsByOrderGroup.computeIfAbsent(orderGroupKey, ignored -> new ArrayList<>())
                    .add(new IndexedRecord(index, record));
        }
        return recordsByOrderGroup;
    }

    /**
     * 对同一订单组内的商品行执行金额分摊。
     *
     * @param orderGroup 同一订单组内的原始记录
     * @return 分摊后的原始记录
     */
    private List<RawPurchaseRecord> allocateGroup(List<RawPurchaseRecord> orderGroup) {
        if (!shouldAllocate(orderGroup)) {
            return orderGroup;
        }
        List<RawPurchaseRecord> effectiveRecords = orderGroup.stream()
                .filter(this::isEffectiveLine)
                .toList();
        BigDecimal effectiveAmountSum = effectiveRecords.stream()
                .map(record -> money(record.productAmount()))
                .reduce(ZERO_AMOUNT, BigDecimal::add);
        if (effectiveAmountSum.compareTo(ZERO_AMOUNT) <= 0) {
            return markFallbackReview(orderGroup);
        }

        BigDecimal orderPaidAmount = money(firstAmount(orderGroup, AmountField.PAID_AMOUNT));
        BigDecimal orderShippingFee = money(firstAmount(orderGroup, AmountField.SHIPPING_FEE));
        BigDecimal allocatedPaidSum = ZERO_AMOUNT;
        BigDecimal allocatedShippingSum = ZERO_AMOUNT;
        List<RawPurchaseRecord> allocatedRecords = new ArrayList<>();
        int effectiveIndex = 0;
        for (RawPurchaseRecord record : orderGroup) {
            if (!isEffectiveLine(record)) {
                allocatedRecords.add(record.withAmountReview(SOURCE_ORDER_AMOUNT_ALLOCATION_FALLBACK,
                        REASON_ORDER_AMOUNT_ALLOCATION,
                        "订单级金额分摊时该商品行被识别为赠品、定金、邮费补拍或虚拟权益等非有效商品，已保留原始金额等待人工复核。"));
                continue;
            }
            boolean lastEffectiveRecord = effectiveIndex == effectiveRecords.size() - 1;
            BigDecimal productAmount = money(record.productAmount());
            BigDecimal allocatedPaid = allocateMoney(orderPaidAmount, productAmount, effectiveAmountSum,
                    allocatedPaidSum, lastEffectiveRecord);
            BigDecimal allocatedShipping = allocateMoney(orderShippingFee, productAmount, effectiveAmountSum,
                    allocatedShippingSum, lastEffectiveRecord);
            allocatedPaidSum = allocatedPaidSum.add(allocatedPaid);
            allocatedShippingSum = allocatedShippingSum.add(allocatedShipping);
            BigDecimal totalAmount = record.paidAmountIncludesShipping()
                    ? allocatedPaid
                    : allocatedPaid.add(allocatedShipping).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            allocatedRecords.add(record.withAllocatedAmount(totalAmount.doubleValue(), allocatedPaid.doubleValue(),
                    allocatedShipping.doubleValue(), SOURCE_ALLOCATED_ORDER_AMOUNT));
            effectiveIndex++;
        }
        return allocatedRecords;
    }

    /**
     * 判断当前订单组是否需要执行订单级金额分摊。
     *
     * @param orderGroup 同一订单组内的原始记录
     * @return 需要分摊时返回 true
     */
    private boolean shouldAllocate(List<RawPurchaseRecord> orderGroup) {
        if (orderGroup.size() > 1) {
            return hasReusedOrderLevelAmount(orderGroup, AmountField.PAID_AMOUNT)
                    || hasReusedOrderLevelAmount(orderGroup, AmountField.SHIPPING_FEE)
                    || hasReusedOrderLevelAmount(orderGroup, AmountField.TOTAL_AMOUNT);
        }
        RawPurchaseRecord record = orderGroup.get(0);
        BigDecimal totalAmount = money(record.totalAmount());
        BigDecimal paidWithShipping = money(record.paidAmount()).add(money(record.shippingFee()));
        return totalAmount.compareTo(paidWithShipping) != 0 && money(record.productAmount()).compareTo(ZERO_AMOUNT) > 0;
    }

    /**
     * 判断同一订单组内是否存在订单级金额被复用到多条商品行的特征。
     *
     * <p>已经是行级金额的标准模板通常每行 paid/shipping/total 与本行商品金额口径一致；
     * 只有当相同金额出现在多条商品行且明显大于本行商品金额加运费时，才视为订单级金额复用。</p>
     *
     * @param orderGroup  同一订单组内的原始记录
     * @param amountField 待检查金额字段
     * @return 存在订单级金额复用特征时返回 true
     */
    private boolean hasReusedOrderLevelAmount(List<RawPurchaseRecord> orderGroup, AmountField amountField) {
        Map<BigDecimal, Long> amountCounts = new LinkedHashMap<>();
        for (RawPurchaseRecord record : orderGroup) {
            Double amount = amountValue(record, amountField);
            if (amount == null) {
                continue;
            }
            BigDecimal normalizedAmount = money(amount);
            if (normalizedAmount.compareTo(ZERO_AMOUNT) <= 0) {
                continue;
            }
            amountCounts.merge(normalizedAmount, 1L, Long::sum);
        }
        return orderGroup.stream()
                .filter(record -> amountValue(record, amountField) != null)
                .anyMatch(record -> {
                    BigDecimal amount = money(amountValue(record, amountField));
                    Long repeatedCount = amountCounts.get(amount);
                    return repeatedCount != null && repeatedCount > 1 && isOrderLevelAmount(record, amount, amountField);
                });
    }

    /**
     * 判断重复金额是否明显超过当前商品行金额口径。
     *
     * @param record      原始订单记录
     * @param amount      待判断金额
     * @param amountField 金额字段
     * @return 金额明显像订单级金额时返回 true
     */
    private boolean isOrderLevelAmount(RawPurchaseRecord record, BigDecimal amount, AmountField amountField) {
        BigDecimal productAmount = money(record.productAmount());
        BigDecimal shippingFee = money(record.shippingFee());
        BigDecimal lineUpperBound = amountField == AmountField.SHIPPING_FEE
                ? shippingFee
                : productAmount.add(shippingFee);
        return productAmount.compareTo(ZERO_AMOUNT) > 0 && amount.compareTo(lineUpperBound) > 0;
    }

    /**
     * 判断商品行是否可参与订单级金额分摊基数。
     *
     * @param record 原始订单记录
     * @return 可参与分摊时返回 true
     */
    private boolean isEffectiveLine(RawPurchaseRecord record) {
        BigDecimal productAmount = money(record.productAmount());
        if (productAmount.compareTo(ZERO_AMOUNT) <= 0) {
            return false;
        }
        String lineText = combinedLineText(record).toLowerCase(Locale.ROOT);
        if (productAmount.compareTo(NEW_MEMBER_AMOUNT_LIMIT) <= 0
                && (lineText.contains("新会员") || lineText.contains("新会礼"))) {
            return false;
        }
        return INEFFECTIVE_LINE_KEYWORDS.stream().noneMatch(lineText::contains);
    }

    /**
     * 将无法分摊的订单组整体标记为金额复核。
     *
     * @param orderGroup 同一订单组内的原始记录
     * @return 带复核标记的原始记录
     */
    private List<RawPurchaseRecord> markFallbackReview(List<RawPurchaseRecord> orderGroup) {
        return orderGroup.stream()
                .map(record -> record.withAmountReview(SOURCE_ORDER_AMOUNT_ALLOCATION_FALLBACK,
                        REASON_ORDER_AMOUNT_ALLOCATION,
                        "订单级金额分摊失败：同一订单内有效商品金额合计小于等于 0，已保留原始金额并等待人工复核。"))
                .toList();
    }

    /**
     * 按商品金额占比分摊金额，最后一条有效商品行吸收舍入差额。
     *
     * @param orderAmount          订单级金额
     * @param productAmount        当前商品行金额
     * @param effectiveAmountSum   有效商品金额合计
     * @param allocatedAmountSum   当前订单组此前已分摊金额合计
     * @param lastEffectiveRecord  是否为最后一条有效商品行
     * @return 当前商品行分摊金额，单位为元
     */
    private BigDecimal allocateMoney(BigDecimal orderAmount,
                                     BigDecimal productAmount,
                                     BigDecimal effectiveAmountSum,
                                     BigDecimal allocatedAmountSum,
                                     boolean lastEffectiveRecord) {
        if (lastEffectiveRecord) {
            return orderAmount.subtract(allocatedAmountSum).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return orderAmount.multiply(productAmount)
                .divide(effectiveAmountSum, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 读取订单组内第一条非空金额字段。
     *
     * @param orderGroup  同一订单组内的原始记录
     * @param amountField 目标金额字段
     * @return 第一条非空金额；全部为空时返回 null
     */
    private Double firstAmount(List<RawPurchaseRecord> orderGroup, AmountField amountField) {
        for (RawPurchaseRecord record : orderGroup) {
            Double amount = amountValue(record, amountField);
            if (amount != null) {
                return amount;
            }
        }
        return null;
    }

    /**
     * 从原始记录读取指定金额字段。
     *
     * @param record      原始订单记录
     * @param amountField 金额字段
     * @return 字段金额；字段为空时返回 null
     */
    private Double amountValue(RawPurchaseRecord record, AmountField amountField) {
        return switch (amountField) {
            case PAID_AMOUNT -> record.paidAmount();
            case SHIPPING_FEE -> record.shippingFee();
            case TOTAL_AMOUNT -> record.totalAmount();
        };
    }

    /**
     * 将 Double 金额转换为 BigDecimal，空值按 0 处理。
     *
     * @param amount Double 金额
     * @return BigDecimal 金额
     */
    private BigDecimal money(Double amount) {
        return amount == null ? ZERO_AMOUNT : BigDecimal.valueOf(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 拼接可用于识别非有效商品行的文本。
     *
     * @param record 原始订单记录
     * @return 商品名称、SKU 和分类拼接文本
     */
    private String combinedLineText(RawPurchaseRecord record) {
        return safeText(record.productName()) + " " + safeText(record.sku()) + " "
                + safeText(record.category()) + " " + safeText(record.subCategory());
    }

    /**
     * 将空文本转换为空字符串。
     *
     * @param value 原始文本
     * @return 非空文本
     */
    private String safeText(String value) {
        return Objects.toString(value, "");
    }

    /**
     * 原始记录及其输入顺序。
     *
     * @param index  输入列表下标
     * @param record 原始订单记录
     */
    private record IndexedRecord(int index, RawPurchaseRecord record) {
    }

    /**
     * 可从订单组中读取的订单级金额字段。
     */
    private enum AmountField {
        /**
         * 订单级实付金额。
         */
        PAID_AMOUNT,
        /**
         * 订单级运费。
         */
        SHIPPING_FEE,
        /**
         * 当前统计总金额。
         */
        TOTAL_AMOUNT
    }
}
