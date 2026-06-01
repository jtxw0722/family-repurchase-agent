package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.PriceBaselineResult;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.PriceDecisionPolicy;
import com.jtxw.familyagent.domain.policy.ProductNormalizationResult;
import com.jtxw.familyagent.domain.policy.ProductNormalizer;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/12/10:24
 * @Description: 价格分析应用服务，编排商品归一化、历史查询和价格决策。
 */
@Service
public class PriceAnalysisApplicationService {
    private final DatabaseInitializer databaseInitializer;
    private final ProductNormalizer productNormalizer;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final PriceDecisionPolicy priceDecisionPolicy;

    public PriceAnalysisApplicationService(DatabaseInitializer databaseInitializer,
                                           ProductNormalizer productNormalizer,
                                           PurchaseRecordRepository purchaseRecordRepository,
                                           PriceDecisionPolicy priceDecisionPolicy) {
        this.databaseInitializer = databaseInitializer;
        this.productNormalizer = productNormalizer;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.priceDecisionPolicy = priceDecisionPolicy;
    }

    public PriceBaselineResult getPriceBaseline(String productName, String unit) {
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName must be a non-empty string");
        }
        ProductNormalizationResult normalization = productNormalizer.normalizeProduct(productName);
        String baselineUnit = resolveBaselineUnit(unit, normalization.standardUnit());
        List<PurchaseRecord> history = purchaseRecordRepository.listPriceHistoryRecords(normalization.normalizedName());
        return priceDecisionPolicy.baseline(productName, normalization.normalizedName(), baselineUnit, history);
    }

    private String resolveBaselineUnit(String requestedUnit, String standardUnit) {
        if (requestedUnit != null && !requestedUnit.isBlank()) {
            return requestedUnit.trim();
        }
        if (standardUnit != null && !standardUnit.isBlank()) {
            return standardUnit.trim();
        }
        throw new IllegalArgumentException("unit must be provided when product standard unit is unknown");
    }

    /**
     * 比较当前商品价格与历史有效价格样本。
     *
     * <p>该方法先归一化商品名称，再查询正式统计口径内的历史单价，
     * 最后使用确定性价格规则生成判断结果。</p>
     *
     * @param productName 原始商品名称
     * @param price       当前总价
     * @param quantity    当前商品数量
     * @param unit        数量单位
     * @return 价格判断结果
     */
    public PriceDecisionResult comparePrice(String productName, double price, double quantity, String unit) {
        databaseInitializer.initialize();
        String normalizedName = productNormalizer.normalize(productName);
        List<PurchaseRecord> history = purchaseRecordRepository.listPriceHistoryRecords(normalizedName);
        return priceDecisionPolicy.decide(productName, normalizedName, price, quantity, unit, history);
    }
}
