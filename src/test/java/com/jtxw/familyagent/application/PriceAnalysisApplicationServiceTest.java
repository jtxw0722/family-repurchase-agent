package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.query.ComparePriceQuery;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.LearningProductNameNormalizer;
import com.jtxw.familyagent.domain.policy.PriceDecisionPolicy;
import com.jtxw.familyagent.domain.policy.PriceDecisionThresholds;
import com.jtxw.familyagent.domain.policy.ProductNameNormalizationResult;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 09:30:00
 * @Description: 价格分析应用服务测试，验证 compare-price 合并后的 baseline-only 与 compare 两种用例编排。
 */
class PriceAnalysisApplicationServiceTest {
    @Test
    void comparePriceShouldReturnBaselineOnlyWhenCurrentSampleIsOmitted() {
        PriceAnalysisApplicationService service = serviceWithHistory(List.of(
                record(1L, "2026-04-01", "纸巾 100抽*10包", 20D, 1000D, 0.02D, "抽"),
                record(2L, "2026-05-01", "纸巾 120抽*10包", 24D, 1200D, 0.02D, "抽")
        ));

        PriceDecisionResult result = service.comparePrice(new ComparePriceQuery("纸巾", null, null, null));

        assertThat(result.mode()).isEqualTo(PriceDecisionResult.MODE_BASELINE_ONLY);
        assertThat(result.current()).isNull();
        assertThat(result.getDecision()).isNull();
        assertThat(result.baseline().unit()).isEqualTo("抽");
        assertThat(result.baseline().sampleSize()).isEqualTo(2);
        assertThat(result.evidence().source()).isEqualTo("local_purchase_history");
    }

    @Test
    void comparePriceShouldReturnCompareModeWhenCurrentSampleIsProvided() {
        PriceAnalysisApplicationService service = serviceWithHistory(List.of(
                record(1L, "2026-04-01", "纸巾 100抽*10包", 20D, 1000D, 0.02D, "抽"),
                record(2L, "2026-05-01", "纸巾 120抽*10包", 24D, 1200D, 0.02D, "抽"),
                record(3L, "2026-06-01", "纸巾 130抽*10包", 26D, 1300D, 0.02D, "抽")
        ));

        PriceDecisionResult result = service.comparePrice(new ComparePriceQuery("纸巾", 39.9D, 3120D, "抽"));

        assertThat(result.mode()).isEqualTo(PriceDecisionResult.MODE_COMPARE);
        assertThat(result.current()).isNotNull();
        assertThat(result.getDecision()).isNotNull();
        assertThat(result.baseline().sampleSize()).isEqualTo(3);
        assertThat(result.evidence().source()).isEqualTo("local_purchase_history");
    }

    @Test
    void comparePriceQueryShouldRejectPartialPriceArguments() {
        assertThatThrownBy(() -> new ComparePriceQuery("纸巾", 39.9D, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("price、quantity、unit 必须同时提供，或同时省略。");
    }

    @Test
    void comparePriceShouldReturnWarningsWhenSamplesAreInsufficient() {
        PriceAnalysisApplicationService service = serviceWithHistory(List.of());

        PriceDecisionResult baselineOnly = service.comparePrice(new ComparePriceQuery("纸巾", null, null, null));
        PriceDecisionResult compare = service.comparePrice(new ComparePriceQuery("纸巾", 39.9D, 3120D, "抽"));

        assertThat(baselineOnly.mode()).isEqualTo(PriceDecisionResult.MODE_BASELINE_ONLY);
        assertThat(baselineOnly.warnings()).isNotEmpty();
        assertThat(compare.mode()).isEqualTo(PriceDecisionResult.MODE_COMPARE);
        assertThat(compare.warnings()).isNotEmpty();
    }

    private PriceAnalysisApplicationService serviceWithHistory(List<PurchaseRecord> history) {
        LearningProductNameNormalizer normalizer = mock(LearningProductNameNormalizer.class);
        when(normalizer.normalize("纸巾", ""))
                .thenReturn(new ProductNameNormalizationResult("纸巾", "抽", 1D, "tissue", false));

        PurchaseRecordRepository repository = mock(PurchaseRecordRepository.class);
        when(repository.listPriceHistoryRecords("纸巾")).thenReturn(history);

        return new PriceAnalysisApplicationService(
                mock(DatabaseInitializer.class),
                normalizer,
                repository,
                new PriceDecisionPolicy(new PriceDecisionThresholds(0.92D, 1.12D))
        );
    }

    private PurchaseRecord record(Long id,
                                  String orderTime,
                                  String productName,
                                  Double totalAmount,
                                  Double quantity,
                                  Double unitPrice,
                                  String unit) {
        return new PurchaseRecord(id, 1L, orderTime, "manual", "jtxw", productName, "纸巾",
                "", "日用品", "纸巾", quantity, unit, totalAmount, totalAmount,
                totalAmount, 0D, "paid_amount", unitPrice, "CNY", "include",
                false, "unique", "test.csv", "2026-06-15T09:30:00");
    }
}
