package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/05/13/16:08
 * @Description: Price decision policy unit tests
 */
class PriceDecisionPolicyTest {
    @Test
    void shouldReturnNormalPriceWithEvidence() {
        PriceDecisionPolicy policy = new PriceDecisionPolicy();
        PriceDecisionResult result = policy.decide("cat litter", "cat litter", 89, 12, "kg", List.of(
                record(1L, "2026-02-10", "cat litter 10kg", 68, 10, 6.8),
                record(2L, "2026-03-12", "mixed cat litter 5kg", 40.75, 5, 8.15),
                record(3L, "2026-05-12", "cat litter 8kg", 64, 8, 8.0)
        ));

        assertThat(result.decision()).isEqualTo("normal_price");
        assertThat(result.current().formula()).isEqualTo("89 / 12 = 7.4167");
        assertThat(result.baseline().sampleSize()).isEqualTo(3);
        assertThat(result.baseline().unit()).isEqualTo("kg");
        assertThat(result.baseline().dateRange().from()).isEqualTo("2026-02-10");
        assertThat(result.evidence().source()).isEqualTo("local_purchase_history");
        assertThat(result.evidence().sourceRecords())
                .extracting(PriceDecisionResult.SourceRecord::role)
                .contains("historical_min", "median_sample", "latest");
    }

    @Test
    void shouldReturnInsufficientData() {
        PriceDecisionPolicy policy = new PriceDecisionPolicy();
        PriceDecisionResult result = policy.decide("cat litter", "cat litter", 89, 12, "kg", List.of());

        assertThat(result.decision()).isEqualTo("insufficient_data");
        assertThat(result.getDecision().confidence()).isEqualTo("low");
        assertThat(result.baseline().unit()).isEqualTo("kg");
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void shouldWarnWhenAverageIsMuchHigherThanMedian() {
        PriceDecisionPolicy policy = new PriceDecisionPolicy();
        PriceDecisionResult result = policy.decide("cat litter", "cat litter", 89, 12, "kg", List.of(
                record(1L, "2026-02-10", "cat litter 10kg", 68, 10, 6.8),
                record(2L, "2026-03-12", "mixed cat litter 5kg", 79.5, 5, 15.9),
                record(3L, "2026-05-12", "outlier cat litter 1kg", 160, 1, 160)
        ));

        assertThat(result.warnings()).isNotEmpty();
        assertThat(result.evidence().outliers())
                .extracting(PriceDecisionResult.SourceRecord::role)
                .contains("high_outlier");
    }

    @Test
    void shouldExcludeHistoryRecordsWithDifferentUnit() {
        PriceDecisionPolicy policy = new PriceDecisionPolicy();
        PriceDecisionResult result = policy.decide("cat litter", "cat litter", 89, 12, "kg", List.of(
                record(1L, "2026-02-10", "cat litter 10kg", 68, 10, 6.8, "kg"),
                record(2L, "2026-03-12", "mixed cat litter 5kg", 79.5, 5, 15.9, "kg"),
                record(3L, "2026-04-10", "cat litter 500g", 20, 500, 0.04, "g"),
                record(4L, "2026-05-12", "cat litter 8kg", 128, 8, 16, "kg")
        ));

        assertThat(result.baseline().unit()).isEqualTo("kg");
        assertThat(result.evidence().excludedRecordCount()).isEqualTo(1);
        assertThat(result.evidence().excludedReasons()).anyMatch(reason -> reason.contains("单位不一致"));
        assertThat(result.evidence().sourceRecords())
                .filteredOn(record -> List.of("historical_min", "median_sample", "latest").contains(record.role()))
                .extracting(PriceDecisionResult.SourceRecord::unitPriceUnit)
                .containsOnly("kg");
        assertThat(result.evidence().sourceRecords())
                .filteredOn(record -> List.of("historical_min", "median_sample", "latest").contains(record.role()))
                .extracting(PriceDecisionResult.SourceRecord::originalUnit)
                .containsOnly("kg");
    }

    private PurchaseRecord record(Long id,
                                  String orderTime,
                                  String productName,
                                  double totalAmount,
                                  double quantity,
                                  double unitPrice) {
        return record(id, orderTime, productName, totalAmount, quantity, unitPrice, "kg");
    }

    private PurchaseRecord record(Long id,
                                  String orderTime,
                                  String productName,
                                  double totalAmount,
                                  double quantity,
                                  double unitPrice,
                                  String unit) {
        return new PurchaseRecord(id, 1L, orderTime, "manual", "JTXW", productName, "cat litter",
                "", "pet supplies", "cat litter", quantity, unit, totalAmount, totalAmount,
                totalAmount, 0D, "paid_amount", unitPrice, "CNY", "include",
                false, "unique", "test.csv", "2026-05-11T00:00:00");
    }
}
