package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/11:07
 * @Description: 价格决策规则，根据历史单价样本判断当前价格水平。
 */
@Component
public class PriceDecisionPolicy {
    /**
     * 根据当前价格和历史价格记录判断价格状态。
     *
     * <p>该方法只基于结构化历史数据和确定性规则进行判断，不依赖 LLM 生成价格结论。
     * 决策理由会优先引用当前单价、历史最低价、历史中位数和样本数量。</p>
     *
     * @param productName    原始商品名称
     * @param normalizedName 归一化商品名称
     * @param price          当前总价
     * @param quantity       当前商品数量
     * @param unit           数量单位
     * @param history        历史有效价格记录
     * @return 价格判断结果
     */
    public PriceDecisionResult decide(String productName,
                                      String normalizedName,
                                      double price,
                                      double quantity,
                                      String unit,
                                      List<PurchaseRecord> history) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于 0");
        }

        String baselineUnit = normalizeUnit(unit);
        double currentUnitPrice = price / quantity;
        PriceDecisionResult.Current current = new PriceDecisionResult.Current(
                price, quantity, baselineUnit, currentUnitPrice, formatFormula(price, quantity, currentUnitPrice)
        );

        List<PurchaseRecord> candidateRecords = history == null ? List.of() : history.stream()
                .filter(record -> record.unitPrice() != null)
                .toList();
        List<PurchaseRecord> records = candidateRecords.stream()
                .filter(record -> sameUnit(record.unit(), baselineUnit))
                .toList();
        int excludedRecordCount = candidateRecords.size() - records.size();
        List<String> excludedReasons = buildExcludedReasons(excludedRecordCount, baselineUnit);
        if (records.isEmpty()) {
            return noHistoryResult(productName, normalizedName, current, excludedRecordCount, excludedReasons);
        }

        List<PurchaseRecord> sortedByPrice = records.stream()
                .sorted(Comparator.comparing(PurchaseRecord::unitPrice))
                .toList();
        PurchaseRecord minRecord = sortedByPrice.get(0);
        PurchaseRecord medianRecord = sortedByPrice.get((sortedByPrice.size() - 1) / 2);
        PurchaseRecord latestRecord = records.stream()
                .max(Comparator.comparing(record -> safeText(record.orderTime())))
                .orElse(medianRecord);

        double min = minRecord.unitPrice();
        double median = median(sortedByPrice);
        double average = records.stream().mapToDouble(PurchaseRecord::unitPrice).average().orElse(0D);
        PriceDecisionResult.Baseline baseline = new PriceDecisionResult.Baseline(
                records.size(), baselineUnit, min, median, average, dateRange(records)
        );

        List<String> warnings = buildWarnings(records.size(), average, median, excludedRecordCount, baselineUnit);
        PriceDecisionResult.Decision decision = buildDecision(currentUnitPrice, current.unit(), baseline, warnings);
        PriceDecisionResult.Evidence evidence = new PriceDecisionResult.Evidence(
                "local_purchase_history",
                sourceRecords(minRecord, medianRecord, latestRecord, baselineUnit),
                excludedRecordCount,
                excludedReasons,
                outlierRecords(records, median, average, baselineUnit)
        );
        return new PriceDecisionResult(productName, normalizedName, current, baseline, decision, evidence, warnings);
    }

    private PriceDecisionResult noHistoryResult(String productName,
                                                String normalizedName,
                                                PriceDecisionResult.Current current,
                                                int excludedRecordCount,
                                                List<String> excludedReasons) {
        PriceDecisionResult.Baseline baseline = new PriceDecisionResult.Baseline(0, current.unit(), null, null, null, null);
        PriceDecisionResult.Decision decision = new PriceDecisionResult.Decision(
                "insufficient_data",
                "数据不足",
                "当前单价 " + formatPrice(current.unitPrice()) + " 元/" + current.unit()
                        + "，暂无可用历史记录，无法判断是否值得买。",
                "low"
        );
        PriceDecisionResult.Evidence evidence = new PriceDecisionResult.Evidence(
                "local_purchase_history", List.of(), excludedRecordCount, excludedReasons, List.of()
        );
        return new PriceDecisionResult(productName, normalizedName, current, baseline, decision, evidence,
                List.of("历史记录不足，无法形成可靠价格判断。"));
    }

    private PriceDecisionResult.Decision buildDecision(double currentUnitPrice,
                                                       String unit,
                                                       PriceDecisionResult.Baseline baseline,
                                                       List<String> warnings) {
        String confidence = baseline.sampleSize() < 3 ? "low" : "medium";
        String code;
        String text;
        if (currentUnitPrice <= baseline.historicalMin()) {
            code = "good_price";
            text = "好价";
        } else if (currentUnitPrice <= baseline.historicalMedian() * 0.92) {
            code = "good_price";
            text = "好价";
        } else if (currentUnitPrice >= baseline.historicalMedian() * 1.12) {
            code = "expensive";
            text = "偏贵";
        } else {
            code = "normal_price";
            text = "正常价格";
        }
        return new PriceDecisionResult.Decision(code, text, buildReason(currentUnitPrice, baseline.unit(), baseline, code, warnings), confidence);
    }

    private String buildReason(double currentUnitPrice,
                               String unit,
                               PriceDecisionResult.Baseline baseline,
                               String decisionCode,
                               List<String> warnings) {
        String relation = switch (decisionCode) {
            case "good_price" -> "低于历史最低价或明显低于历史中位数";
            case "expensive" -> "明显高于历史中位数";
            default -> "接近历史中位数";
        };
        String unitSuffix = unit == null || unit.isBlank() ? "" : "/" + unit;
        String reason = "当前单价 " + formatPrice(currentUnitPrice) + " 元" + unitSuffix + "，历史最低单价 "
                + formatPrice(baseline.historicalMin()) + " 元" + unitSuffix + "，历史中位数 "
                + formatPrice(baseline.historicalMedian()) + " 元" + unitSuffix + "，参与统计样本 "
                + baseline.sampleSize() + " 条，本次判断为" + relation + "。";
        if (!warnings.isEmpty()) {
            reason = reason + " " + warnings.get(0);
        }
        return reason;
    }

    private List<String> buildWarnings(int sampleSize, double average, double median, int excludedRecordCount, String baselineUnit) {
        List<String> warnings = new ArrayList<>();
        if (sampleSize < 3) {
            warnings.add("历史记录不足 3 条，判断置信度较低。");
        }
        if (median > 0 && average > median * 2) {
            warnings.add("历史平均值明显高于中位数，可能存在异常值，建议优先参考历史中位数和历史最低价。");
        }
        if (excludedRecordCount > 0) {
            warnings.add("存在 " + excludedRecordCount + " 条历史记录单位不是 " + baselineUnit + "，已排除，不参与本次价格判断。");
        }
        return warnings;
    }

    private List<String> buildExcludedReasons(int excludedRecordCount, String baselineUnit) {
        if (excludedRecordCount <= 0) {
            return List.of();
        }
        return List.of("单位不一致：存在 " + excludedRecordCount + " 条历史记录不是 " + baselineUnit + "，未参与本次价格判断。");
    }

    private PriceDecisionResult.DateRange dateRange(List<PurchaseRecord> records) {
        List<String> dates = records.stream()
                .map(record -> purchaseDate(record.orderTime()))
                .filter(date -> date != null && !date.isBlank())
                .sorted()
                .toList();
        if (dates.isEmpty()) {
            return null;
        }
        return new PriceDecisionResult.DateRange(dates.get(0), dates.get(dates.size() - 1));
    }

    private List<PriceDecisionResult.SourceRecord> sourceRecords(PurchaseRecord minRecord,
                                                                 PurchaseRecord medianRecord,
                                                                 PurchaseRecord latestRecord,
                                                                 String baselineUnit) {
        Map<String, PriceDecisionResult.SourceRecord> records = new LinkedHashMap<>();
        records.put("historical_min", sourceRecord(minRecord, "historical_min", baselineUnit));
        records.put("median_sample", sourceRecord(medianRecord, "median_sample", baselineUnit));
        records.put("latest", sourceRecord(latestRecord, "latest", baselineUnit));
        return List.copyOf(records.values());
    }

    private List<PriceDecisionResult.SourceRecord> outlierRecords(List<PurchaseRecord> records,
                                                                  double median,
                                                                  double average,
                                                                  String baselineUnit) {
        if (median <= 0 || average <= median * 2) {
            return List.of();
        }
        return records.stream()
                .filter(record -> record.unitPrice() > median * 2)
                .sorted(Comparator.comparing(PurchaseRecord::unitPrice).reversed())
                .map(record -> sourceRecord(record, "high_outlier", baselineUnit))
                .toList();
    }

    private PriceDecisionResult.SourceRecord sourceRecord(PurchaseRecord record, String role, String baselineUnit) {
        return new PriceDecisionResult.SourceRecord(
                record.id(),
                role,
                purchaseDate(record.orderTime()),
                record.productName(),
                record.totalAmount(),
                record.quantity(),
                baselineUnit,
                record.unitPrice(),
                baselineUnit,
                record.quantity(),
                record.unit()
        );
    }

    private boolean sameUnit(String historyUnit, String baselineUnit) {
        return normalizeUnit(historyUnit).equalsIgnoreCase(normalizeUnit(baselineUnit));
    }

    private String normalizeUnit(String unit) {
        return unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
    }

    private double median(List<PurchaseRecord> sortedByPrice) {
        int n = sortedByPrice.size();
        if (n % 2 == 1) {
            return sortedByPrice.get(n / 2).unitPrice();
        }
        return (sortedByPrice.get(n / 2 - 1).unitPrice() + sortedByPrice.get(n / 2).unitPrice()) / 2.0;
    }

    private String purchaseDate(String orderTime) {
        if (orderTime == null || orderTime.isBlank()) {
            return null;
        }
        return orderTime.length() >= 10 ? orderTime.substring(0, 10) : orderTime;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private String formatFormula(double price, double quantity, double unitPrice) {
        return formatNumber(price) + " / " + formatNumber(quantity) + " = " + formatNumber(unitPrice);
    }

    private String formatPrice(double value) {
        return String.format("%.2f", value);
    }

    private String formatNumber(double value) {
        return String.format("%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
