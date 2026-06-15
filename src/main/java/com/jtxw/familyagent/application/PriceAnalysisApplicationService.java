package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.query.ComparePriceQuery;
import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.LearningProductNameNormalizer;
import com.jtxw.familyagent.domain.policy.PriceDecisionPolicy;
import com.jtxw.familyagent.domain.policy.ProductNameNormalizationResult;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 09:30:00
 * @Description: 价格分析应用服务，编排商品归一化、历史价格基准线查询和当前价格比较用例。
 */
@Service
public class PriceAnalysisApplicationService {
    private final DatabaseInitializer databaseInitializer;
    private final LearningProductNameNormalizer productNameNormalizer;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final PriceDecisionPolicy priceDecisionPolicy;

    public PriceAnalysisApplicationService(DatabaseInitializer databaseInitializer,
                                           LearningProductNameNormalizer productNameNormalizer,
                                           PurchaseRecordRepository purchaseRecordRepository,
                                           PriceDecisionPolicy priceDecisionPolicy) {
        this.databaseInitializer = databaseInitializer;
        this.productNameNormalizer = productNameNormalizer;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.priceDecisionPolicy = priceDecisionPolicy;
    }

    private String resolveBaselineUnit(String requestedUnit, String standardUnit) {
        if (requestedUnit != null && !requestedUnit.isBlank()) {
            return requestedUnit.trim();
        }
        if (standardUnit != null && !standardUnit.isBlank()) {
            return standardUnit.trim();
        }
        throw new IllegalArgumentException("无法确定商品标准单位，请传入 unit，或在商品规则中配置 standardUnit");
    }

    /**
     * 比较当前商品价格与历史有效价格样本。
     *
     * <p>该方法先归一化商品名称，再查询正式统计口径内的历史单价，
     * 最后使用确定性价格规则生成判断结果。</p>
     *
     * @param query 价格比较查询
     * @return 价格判断结果
     */
    public PriceDecisionResult comparePrice(ComparePriceQuery query) {
        if (query == null) {
            throw new IllegalArgumentException("价格分析请求不能为空");
        }
        databaseInitializer.initialize();
        ProductNameNormalizationResult normalization = productNameNormalizer.normalize(query.productName(), "");
        String normalizedName = normalization.normalizedName();
        List<PurchaseRecord> history = purchaseRecordRepository.listPriceHistoryRecords(normalizedName);
        if (query.baselineOnly()) {
            String baselineUnit = resolveBaselineUnit(null, normalization.targetUnit());
            return priceDecisionPolicy.baseline(query.productName(), normalizedName, baselineUnit, history);
        }
        return priceDecisionPolicy.decide(query.productName(), normalizedName,
                query.price(), query.quantity(), query.unit(), history);
    }
}
