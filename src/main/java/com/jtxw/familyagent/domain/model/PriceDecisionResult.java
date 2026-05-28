package com.jtxw.familyagent.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/00:36
 * @Description: 价格判断结果对象，承载当前价格、历史统计、决策说明和证据链。
 */
@Schema(description = "价格判断结果")
public class PriceDecisionResult {
    /**
     * 原始商品名称
     */
    @Schema(description = "原始商品名称", example = "名创优品猫砂")
    private final String productName;
    /**
     * 归一化后的商品名称
     */
    @Schema(description = "归一化后的商品名称，用于匹配本地历史价格样本", example = "猫砂")
    private final String normalizedName;
    /**
     * 当前价格计算依据
     */
    @Schema(description = "当前价格计算依据")
    private final Current current;
    /**
     * 历史价格统计基准
     */
    @Schema(description = "历史价格统计基准")
    private final Baseline baseline;
    /**
     * 价格判断结论
     */
    @Schema(description = "价格判断结论")
    private final Decision decision;
    /**
     * 判断证据来源和代表性历史记录
     */
    @Schema(description = "判断证据来源和代表性历史记录")
    private final Evidence evidence;
    /**
     * 风险提示
     */
    @Schema(description = "风险提示")
    private final List<String> warnings;

    public PriceDecisionResult(String productName,
                               String normalizedName,
                               Current current,
                               Baseline baseline,
                               Decision decision,
                               Evidence evidence,
                               List<String> warnings) {
        this.productName = productName;
        this.normalizedName = normalizedName;
        this.current = current;
        this.baseline = baseline;
        this.decision = decision;
        this.evidence = evidence;
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public String productName() {
        return productName;
    }

    public String normalizedName() {
        return normalizedName;
    }

    public Current current() {
        return current;
    }

    public Baseline baseline() {
        return baseline;
    }

    public Evidence evidence() {
        return evidence;
    }

    public List<String> warnings() {
        return warnings;
    }

    public double currentUnitPrice() {
        return current.unitPrice();
    }

    public String unit() {
        return current.unit();
    }

    public Double historicalMin() {
        return baseline.historicalMin();
    }

    public Double historicalMedian() {
        return baseline.historicalMedian();
    }

    public Double historicalAverage() {
        return baseline.historicalAverage();
    }

    public int sampleSize() {
        return baseline.sampleSize();
    }

    public String decision() {
        return decision.code();
    }

    public String decisionText() {
        return decision.text();
    }

    public String reason() {
        return decision.reason();
    }

    public String getProductName() {
        return productName;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public Current getCurrent() {
        return current;
    }

    public Baseline getBaseline() {
        return baseline;
    }

    public Decision getDecision() {
        return decision;
    }

    public Evidence getEvidence() {
        return evidence;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    @Schema(description = "当前价格计算依据")
    public static class Current {
        private final double price;
        private final double quantity;
        private final String unit;
        private final double unitPrice;
        private final String formula;

        public Current(double price, double quantity, String unit, double unitPrice, String formula) {
            this.price = price;
            this.quantity = quantity;
            this.unit = unit;
            this.unitPrice = unitPrice;
            this.formula = formula;
        }

        public double price() {
            return price;
        }

        public double quantity() {
            return quantity;
        }

        public String unit() {
            return unit;
        }

        public double unitPrice() {
            return unitPrice;
        }

        public String formula() {
            return formula;
        }

        public double getPrice() {
            return price;
        }

        public double getQuantity() {
            return quantity;
        }

        public String getUnit() {
            return unit;
        }

        public double getUnitPrice() {
            return unitPrice;
        }

        public String getFormula() {
            return formula;
        }
    }

    @Schema(description = "历史价格统计基准")
    public static class Baseline {
        private final int sampleSize;
        private final String unit;
        private final Double historicalMin;
        private final Double historicalMedian;
        private final Double historicalAverage;
        private final DateRange dateRange;

        public Baseline(int sampleSize,
                        String unit,
                        Double historicalMin,
                        Double historicalMedian,
                        Double historicalAverage,
                        DateRange dateRange) {
            this.sampleSize = sampleSize;
            this.unit = unit;
            this.historicalMin = historicalMin;
            this.historicalMedian = historicalMedian;
            this.historicalAverage = historicalAverage;
            this.dateRange = dateRange;
        }

        public int sampleSize() {
            return sampleSize;
        }

        public String unit() {
            return unit;
        }

        public Double historicalMin() {
            return historicalMin;
        }

        public Double historicalMedian() {
            return historicalMedian;
        }

        public Double historicalAverage() {
            return historicalAverage;
        }

        public DateRange dateRange() {
            return dateRange;
        }

        public int getSampleSize() {
            return sampleSize;
        }

        public String getUnit() {
            return unit;
        }

        public Double getHistoricalMin() {
            return historicalMin;
        }

        public Double getHistoricalMedian() {
            return historicalMedian;
        }

        public Double getHistoricalAverage() {
            return historicalAverage;
        }

        public DateRange getDateRange() {
            return dateRange;
        }
    }

    @Schema(description = "历史样本日期范围")
    public static class DateRange {
        private final String from;
        private final String to;

        public DateRange(String from, String to) {
            this.from = from;
            this.to = to;
        }

        public String from() {
            return from;
        }

        public String to() {
            return to;
        }

        public String getFrom() {
            return from;
        }

        public String getTo() {
            return to;
        }
    }

    @Schema(description = "价格判断结论")
    public static class Decision {
        private final String code;
        private final String text;
        private final String reason;
        private final String confidence;

        public Decision(String code, String text, String reason, String confidence) {
            this.code = code;
            this.text = text;
            this.reason = reason;
            this.confidence = confidence;
        }

        public String code() {
            return code;
        }

        public String text() {
            return text;
        }

        public String reason() {
            return reason;
        }

        public String confidence() {
            return confidence;
        }

        public String getCode() {
            return code;
        }

        public String getText() {
            return text;
        }

        public String getReason() {
            return reason;
        }

        public String getConfidence() {
            return confidence;
        }
    }

    @Schema(description = "判断证据来源和代表性历史记录")
    public static class Evidence {
        private final String source;
        private final List<SourceRecord> sourceRecords;
        private final int excludedRecordCount;
        private final List<String> excludedReasons;
        private final List<SourceRecord> outliers;

        public Evidence(String source,
                        List<SourceRecord> sourceRecords,
                        int excludedRecordCount,
                        List<String> excludedReasons,
                        List<SourceRecord> outliers) {
            this.source = source;
            this.sourceRecords = sourceRecords == null ? List.of() : List.copyOf(sourceRecords);
            this.excludedRecordCount = excludedRecordCount;
            this.excludedReasons = excludedReasons == null ? List.of() : List.copyOf(excludedReasons);
            this.outliers = outliers == null ? List.of() : List.copyOf(outliers);
        }

        public String source() {
            return source;
        }

        public List<SourceRecord> sourceRecords() {
            return sourceRecords;
        }

        public int excludedRecordCount() {
            return excludedRecordCount;
        }

        public List<String> excludedReasons() {
            return excludedReasons;
        }

        public List<SourceRecord> outliers() {
            return outliers;
        }

        public String getSource() {
            return source;
        }

        public List<SourceRecord> getSourceRecords() {
            return sourceRecords;
        }

        public int getExcludedRecordCount() {
            return excludedRecordCount;
        }

        public List<String> getExcludedReasons() {
            return excludedReasons;
        }

        public List<SourceRecord> getOutliers() {
            return outliers;
        }
    }

    @Schema(description = "代表性历史记录")
    public static class SourceRecord {
        private final Long recordId;
        private final String role;
        private final String purchaseDate;
        private final String productName;
        private final Double price;
        private final Double quantity;
        private final String unit;
        private final Double unitPrice;
        private final String unitPriceUnit;
        private final Double originalQuantity;
        private final String originalUnit;

        public SourceRecord(Long recordId,
                            String role,
                            String purchaseDate,
                            String productName,
                            Double price,
                            Double quantity,
                            String unit,
                            Double unitPrice,
                            String unitPriceUnit,
                            Double originalQuantity,
                            String originalUnit) {
            this.recordId = recordId;
            this.role = role;
            this.purchaseDate = purchaseDate;
            this.productName = productName;
            this.price = price;
            this.quantity = quantity;
            this.unit = unit;
            this.unitPrice = unitPrice;
            this.unitPriceUnit = unitPriceUnit;
            this.originalQuantity = originalQuantity;
            this.originalUnit = originalUnit;
        }

        public Long recordId() {
            return recordId;
        }

        public String role() {
            return role;
        }

        public String purchaseDate() {
            return purchaseDate;
        }

        public String productName() {
            return productName;
        }

        public Double price() {
            return price;
        }

        public Double quantity() {
            return quantity;
        }

        public String unit() {
            return unit;
        }

        public Double unitPrice() {
            return unitPrice;
        }

        public String unitPriceUnit() {
            return unitPriceUnit;
        }

        public Double originalQuantity() {
            return originalQuantity;
        }

        public String originalUnit() {
            return originalUnit;
        }

        public Long getRecordId() {
            return recordId;
        }

        public String getRole() {
            return role;
        }

        public String getPurchaseDate() {
            return purchaseDate;
        }

        public String getProductName() {
            return productName;
        }

        public Double getPrice() {
            return price;
        }

        public Double getQuantity() {
            return quantity;
        }

        public String getUnit() {
            return unit;
        }

        public Double getUnitPrice() {
            return unitPrice;
        }

        public String getUnitPriceUnit() {
            return unitPriceUnit;
        }

        public Double getOriginalQuantity() {
            return originalQuantity;
        }

        public String getOriginalUnit() {
            return originalUnit;
        }
    }
}
