package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.PriceOverviewItem;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationRuleRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 19:39:30
 * @Description: 价格看板应用服务，协调规则商品与家庭历史单价的批量查询
 */
@Service
public class PriceOverviewApplicationService {
    /** 数据库初始化组件，确保读取看板前的规则表和购买记录表可用，非空。 */
    private final DatabaseInitializer databaseInitializer;

    /** 归一化规则仓储，用于取得对应可比价格摘要，非空。 */
    private final NormalizationRuleRepository normalizationRuleRepository;

    /**
     * 注入价格看板查询所需组件。
     *
     * @param databaseInitializer 数据库初始化组件
     * @param normalizationRuleRepository 归一化规则和价格摘要查询仓储
     */
    public PriceOverviewApplicationService(DatabaseInitializer databaseInitializer,
                                           NormalizationRuleRepository normalizationRuleRepository) {
        this.databaseInitializer = databaseInitializer;
        this.normalizationRuleRepository = normalizationRuleRepository;
    }

    /**
     * 查询规则库商品对应的全家庭价格摘要。
     *
     * @return 按品类和规则优先级排列的商品，保留暂无样本的商品
     */
    public List<PriceOverviewItem> listPrices() {
        databaseInitializer.initialize();
        return normalizationRuleRepository.listPriceOverview();
    }
}
