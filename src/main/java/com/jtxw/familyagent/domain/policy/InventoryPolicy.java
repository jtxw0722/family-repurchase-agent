package com.jtxw.familyagent.domain.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/18 22:20:17
 * @Description: 库存领域策略，负责精确单位换算、购买周期中位数和自然日库存预测，不依赖数据库或价格计算
 */
public final class InventoryPolicy {
    /** 日均数量计算精度，保留十二位小数。 */
    public static final int SCALE = 12;
    /** 仅接受单个明确净含量，可选每包装单位；组合规格交由用户补充。 */
    private static final Pattern SPEC = Pattern.compile("(?i)^\\s*(\\d+(?:\\.\\d+)?)\\s*(kg|g|公斤|千克|克|L|ml|升|毫升)\\s*(?:[/／]\\s*(袋|包|瓶|盒|件))?\\s*$");
    /** 物理计量单位到基础单位的倍率，不允许跨维度换算。 */
    private static final Map<String, BigDecimal> FACTORS = Map.ofEntries(
            Map.entry("kg", new BigDecimal("1000")), Map.entry("千克", new BigDecimal("1000")),
            Map.entry("公斤", new BigDecimal("1000")), Map.entry("g", BigDecimal.ONE), Map.entry("克", BigDecimal.ONE),
            Map.entry("l", new BigDecimal("1000")), Map.entry("升", new BigDecimal("1000")),
            Map.entry("ml", BigDecimal.ONE), Map.entry("毫升", BigDecimal.ONE));

    /** 禁止实例化纯领域策略。 */
    private InventoryPolicy() { }

    /**
     * 解析物理单位对应的兼容计算单位；计数单位原样保留，不使用固定白名单或推断包装重量。
     * 调用方必须从已归一化规则取得目标计数单位，只有相同计数单位才可相加。
     * @param unit 用户或购买明细单位，不允许为空
     * @return g、ml 或原计数单位
     */
    public static String baseUnit(String unit) {
        if (unit == null || unit.isBlank()) { throw new IllegalArgumentException("请填写库存单位"); }
        String value = unit.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "kg", "千克", "公斤", "g", "克" -> "g";
            case "l", "升", "ml", "毫升" -> "ml";
            default -> unit.trim();
        };
    }

    /**
     * 根据归一化规则单位族校验标准单位，用于业务展示及目标单位选择，无写入副作用。
     * @param standardUnit 已保存的规则标准单位，非空；计数单位由现有规则管理负责校验
     * @param unitFamily 已保存单位族文本，非空，支持weight、volume、count、draw_count
     * @return 对应商品的标准单位，例如kg、g、L、ml、抽、粒、条或cm*撕
     * @throws IllegalArgumentException 规则单位与单位族不匹配或单位族不支持时抛出
     */
    public static String standardUnit(String standardUnit, String unitFamily) {
        String physicalBase = baseUnit(standardUnit);
        if (unitFamily == null || unitFamily.isBlank()) {
            throw new IllegalArgumentException("商品规则缺少单位族，请先确认规则");
        }
        UnitFamily family;
        try {
            family = UnitFamily.valueOf(unitFamily.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("商品规则单位族不支持，请先确认规则", exception);
        }
        return switch (family) {
            case WEIGHT -> {
                if (!"g".equals(physicalBase)) { throw new IllegalArgumentException("标准单位与重量单位族不兼容"); }
                yield FACTORS.get(standardUnit.trim().toLowerCase(Locale.ROOT)).compareTo(BigDecimal.ONE) == 0 ? "g" : "kg";
            }
            case VOLUME -> {
                if (!"ml".equals(physicalBase)) { throw new IllegalArgumentException("标准单位与体积单位族不兼容"); }
                yield FACTORS.get(standardUnit.trim().toLowerCase(Locale.ROOT)).compareTo(BigDecimal.ONE) == 0 ? "ml" : "L";
            }
            case DRAW_COUNT -> {
                if (!"抽".equals(standardUnit.trim())) { throw new IllegalArgumentException("标准单位与抽数单位族不兼容"); }
                yield standardUnit.trim();
            }
            case COUNT -> {
                if (Set.of("g", "ml").contains(physicalBase)) { throw new IllegalArgumentException("标准单位与计数单位族不兼容"); }
                yield standardUnit.trim();
            }
            case UNKNOWN -> throw new IllegalArgumentException("商品规则单位族未知，请先确认规则");
        };
    }

    /**
     * 转为目标基础单位，已是重量或体积的购买数量不重复乘 SKU。
     * @param quantity 非负数量
     * @param unit 数量单位
     * @param target 目标单位，可为规则标准单位或已有库存的兼容计算单位
     * @param specification 单包装明确规格，可空；复杂规格拒绝猜测
     * @return 精确基础数量
     */
    public static BigDecimal convert(BigDecimal quantity, String unit, String target, String specification) {
        if (quantity == null || quantity.signum() < 0) { throw new IllegalArgumentException("数量必须为非负数"); }
        String source = baseUnit(unit);
        String targetBase = baseUnit(target);
        if (source.equals(targetBase)) {
            // 物理单位按同一维度的倍率精确换算；自然计数只允许原单位恒等换算。
            BigDecimal sourceFactor = FACTORS.getOrDefault(unit.trim().toLowerCase(Locale.ROOT), BigDecimal.ONE);
            BigDecimal targetFactor = FACTORS.getOrDefault(target.trim().toLowerCase(Locale.ROOT), BigDecimal.ONE);
            return quantity.multiply(sourceFactor).divide(targetFactor);
        }
        // 只有包装计数且净含量明确时才允许转换；不同物理维度永不混用。
        if (!Set.of("g", "ml").contains(source) && specification != null) {
            Matcher matcher = SPEC.matcher(specification);
            if (matcher.matches() && (matcher.group(3) == null || matcher.group(3).equals(source))) {
                BigDecimal net = new BigDecimal(matcher.group(1));
                if (net.signum() > 0 && baseUnit(matcher.group(2)).equals(targetBase)) {
                    return quantity.multiply(convert(net, matcher.group(2), target, null));
                }
            }
        }
        throw new IllegalArgumentException("单位不兼容，需要补充明确的单包装规格");
    }

    /**
     * 将用户原始数量和使用天数折为日均基础数量，无副作用。
     * @param quantity 正数参考数量
     * @param unit 参考单位
     * @param days 正数使用天数
     * @param target 库存基础单位
     * @return 日均消耗
     */
    public static BigDecimal manualRate(BigDecimal quantity, String unit, BigDecimal days, String target) {
        if (quantity == null || quantity.signum() <= 0 || days == null || days.signum() <= 0) {
            throw new IllegalArgumentException("通常购买数量和使用天数必须大于零");
        }
        return convert(quantity, unit, target, null).divide(days, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 同日明细先合并，再计算相邻日期前次购买量/自然日间隔的中位数。
     * @param purchases 已完成状态、数量、日期及单位过滤的购买明细
     * @param lowSampleThreshold 少样本周期阈值，默认由应用配置为3
     * @return 独立保留的自动估算，最后一次购买不产生周期
     */
    public static Estimate estimate(List<Purchase> purchases, int lowSampleThreshold) {
        SortedMap<LocalDate, BigDecimal> daily = new TreeMap<>();
        purchases.forEach(p -> daily.merge(p.date(), p.quantity(), BigDecimal::add));
        List<BigDecimal> rates = new ArrayList<>();
        LocalDate previous = null;
        for (LocalDate day : daily.keySet()) {
            if (previous != null) {
                rates.add(daily.get(previous).divide(BigDecimal.valueOf(ChronoUnit.DAYS.between(previous, day)), SCALE, RoundingMode.HALF_UP));
            }
            previous = day;
        }
        rates.sort(Comparator.naturalOrder());
        int size = rates.size();
        BigDecimal median = size == 0 ? null : rates.get(size / 2);
        if (size > 0 && size % 2 == 0) { median = median.add(rates.get(size / 2 - 1)).divide(new BigDecimal("2"), SCALE, RoundingMode.HALF_UP); }
        return new Estimate(median, size, purchases.size(), size == 0 ? "数据不足" : size < lowSampleThreshold ? "少样本" : "历史估算");
    }

    /**
     * 按上海自然日估算当前剩余量，不写数据库，可靠速度缺失时不扣减。
     * @param quantity 记录库存，null表示未知
     * @param recorded 记录日
     * @param today 上海当前日
     * @param rate 有效日均速度，可空
     * @return 非负剩余量或null
     */
    public static BigDecimal remaining(BigDecimal quantity, LocalDate recorded, LocalDate today, BigDecimal rate) {
        if (quantity == null || rate == null || rate.signum() <= 0) { return quantity; }
        long elapsed = Math.max(0, ChronoUnit.DAYS.between(recorded, today));
        return quantity.subtract(rate.multiply(BigDecimal.valueOf(elapsed))).max(BigDecimal.ZERO);
    }

    /**
     * 按商品时间线重放入库及分段消耗；同日先应用速率再合并入库，断货期间不累计负库存。
     * @param anchorQuantity 人工确认数量，可空；有锚点但数量为空表示库存未知
     * @param anchorDate 人工确认日，可空；无锚点时从第一笔入库的零库存开始
     * @param receipts 本计算周期的入库快照，数量非负且单位一致
     * @param rates 日均消耗分段，日期及单位一致，空速度表示该段不能可靠扣减
     * @param today 上海当前自然日，不允许为空
     * @return 当前预计数量、待估算标记、耗尽日及未扣减的入库合计；无数据库副作用
     */
    public static Projection project(BigDecimal anchorQuantity, LocalDate anchorDate, List<Purchase> receipts,
            List<RatePeriod> rates, LocalDate today) {
        if (anchorDate != null && anchorQuantity == null) {
            return new Projection(null, false, null, null);
        }
        SortedMap<LocalDate, BigDecimal> received = new TreeMap<>();
        for (Purchase receipt : receipts) {
            if (!receipt.date().isAfter(today) && (anchorDate == null || !receipt.date().isBefore(anchorDate))) {
                received.merge(receipt.date(), receipt.quantity(), BigDecimal::add);
            }
        }
        TreeMap<LocalDate, BigDecimal> periods = new TreeMap<>();
        rates.forEach(period -> periods.put(period.date(), period.dailyRate()));
        LocalDate start = anchorDate != null ? anchorDate : received.isEmpty() ? today : received.firstKey();
        TreeSet<LocalDate> boundaries = new TreeSet<>(received.keySet());
        periods.keySet().stream().filter(day -> !day.isBefore(start) && !day.isAfter(today)).forEach(boundaries::add);
        boundaries.add(today);
        BigDecimal quantity = anchorDate == null ? BigDecimal.ZERO : anchorQuantity;
        BigDecimal total = quantity;
        LocalDate previous = start;
        LocalDate depleted = quantity.signum() == 0 ? start : null;
        boolean pending = false;
        for (LocalDate boundary : boundaries) {
            if (boundary.isBefore(start)) { continue; }
            var period = periods.floorEntry(previous);
            BigDecimal rate = period == null ? null : period.getValue();
            long days = ChronoUnit.DAYS.between(previous, boundary);
            if (days > 0 && quantity.signum() > 0) {
                if (rate == null || rate.signum() <= 0) {
                    // 未知消耗不能冒充零消耗；首次取得有效速度后可从保留的入库日期重新推算。
                    pending = true;
                } else {
                    BigDecimal consumed = rate.multiply(BigDecimal.valueOf(days));
                    if (consumed.compareTo(quantity) >= 0) { depleted = previous.plusDays(completeDays(quantity, rate).longValueExact()); }
                    quantity = quantity.subtract(consumed).max(BigDecimal.ZERO);
                }
            }
            BigDecimal added = received.getOrDefault(boundary, BigDecimal.ZERO);
            quantity = quantity.add(added);
            total = total.add(added);
            if (added.signum() > 0) { depleted = null; }
            previous = boundary;
        }
        var currentPeriod = periods.floorEntry(today);
        BigDecimal currentRate = currentPeriod == null ? null : currentPeriod.getValue();
        if (pending) { return new Projection(null, true, null, total); }
        if (currentRate == null || currentRate.signum() <= 0) { depleted = null; }
        else if (quantity.signum() > 0) { depleted = today.plusDays(completeDays(quantity, currentRate).longValueExact()); }
        return new Projection(quantity, false, depleted, total);
    }

    /**
     * 计算完整可用自然日，先将天数保留八位再向下取整，避免日均速率十二位舍入使整日少算一天。
     * @param quantity 非负基础数量
     * @param rate 正数日均基础数量
     * @return 非负完整可用天数
     */
    public static BigDecimal completeDays(BigDecimal quantity, BigDecimal rate) {
        return quantity.divide(rate, 8, RoundingMode.HALF_UP).setScale(0, RoundingMode.FLOOR);
    }

    /**
     * @Author: jtxw
     * @Date: 2026/11/18 22:20:17
     * @Description: 库存领域购买样本，不携带价格或订单敏感字段
     * @param date 有效购买自然日，非空
     * @param quantity 已统一的正数基础数量，非空
     */
    public record Purchase(LocalDate date, BigDecimal quantity) { }

    /**
     * @Author: jtxw
     * @Date: 2026/11/18 22:20:17
     * @Description: 库存领域消耗分段，日期起包含当日，空速度表示该段缺乏可靠估算
     * @param date 分段生效自然日，非空
     * @param dailyRate 日均基础数量，可空，非空时必须为正
     */
    public record RatePeriod(LocalDate date, BigDecimal dailyRate) { }

    /**
     * @Author: jtxw
     * @Date: 2026/11/18 22:20:17
     * @Description: 库存领域时间线预测结果，区分未知库存和历史消耗待估算
     * @param quantity 当前预计基础数量，可空；pending为true时为空
     * @param pending 是否存在无法可靠扣减的历史区间，默认false
     * @param depletionDate 预计耗尽自然日，可空
     * @param receivedQuantity 本周期锚点数量与入库数量合计，可空，不代表当前剩余
     */
    public record Projection(BigDecimal quantity, boolean pending, LocalDate depletionDate, BigDecimal receivedQuantity) { }

    /**
     * @Author: jtxw
     * @Date: 2026/11/18 22:20:17
     * @Description: 自动消耗估算值，明确区分购买明细数与周期数
     * @param rate 自动日均基础数量，无样本时null
     * @param sampleCount 有效周期数，非负
     * @param purchaseCount 有效购买明细数，同日明细独立计数
     * @param label 样本可靠性中文说明
     */
    public record Estimate(BigDecimal rate, int sampleCount, int purchaseCount, String label) { }
}
