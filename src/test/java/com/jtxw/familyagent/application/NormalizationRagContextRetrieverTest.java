package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.NormalizationRagContext;
import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.UnitFamily;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 17:15:00
 * @Description: 商品归一化 RAG 上下文检索测试，验证规则摘要与 ProductRuleMatcher 使用一致的 include/exclude 语义
 */
class NormalizationRagContextRetrieverTest {
    @Test
    void shouldNotSummarizeRuleWhenExcludeKeywordMatchesCurrentRule() {
        NormalizationRagContextRetriever retriever = new NormalizationRagContextRetriever(
                () -> List.of(new ProductRule("cat_litter", "猫砂", "宠物用品", 100,
                        List.of("猫砂"), List.of("猫砂盆"), "kg", UnitFamily.WEIGHT))
        );

        NormalizationRagContext context = retriever.retrieve("猫砂盆大号防外溅", "", "", "");

        assertThat(context.ruleSummaries()).isEmpty();
    }
}
