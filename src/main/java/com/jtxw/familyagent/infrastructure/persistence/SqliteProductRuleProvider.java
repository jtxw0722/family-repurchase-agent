package com.jtxw.familyagent.infrastructure.persistence;

import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.ProductRuleProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 16:50:03
 * @Description: SQLite 商品规则 Provider，负责把数据库归一化规则提供给领域匹配器和 RAG 检索服务
 */
@Component
public class SqliteProductRuleProvider implements ProductRuleProvider {
    /**
     * 归一化规则仓储，负责读取 normalization_rules 和 normalization_rule_keywords。
     */
    private final NormalizationRuleRepository normalizationRuleRepository;

    public SqliteProductRuleProvider(NormalizationRuleRepository normalizationRuleRepository) {
        this.normalizationRuleRepository = normalizationRuleRepository;
    }

    @Override
    public List<ProductRule> listEnabledRules() {
        return normalizationRuleRepository.listEnabledProductRules();
    }
}
