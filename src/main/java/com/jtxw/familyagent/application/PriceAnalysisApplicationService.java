package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.policy.PriceDecisionPolicy;
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

    public PriceDecisionResult comparePrice(String productName, double price, double quantity, String unit) {
        databaseInitializer.initialize();
        String normalizedName = productNormalizer.normalize(productName);
        List<Double> history = purchaseRecordRepository.listUnitPrices(normalizedName);
        return priceDecisionPolicy.decide(productName, normalizedName, price, quantity, unit, history);
    }
}
