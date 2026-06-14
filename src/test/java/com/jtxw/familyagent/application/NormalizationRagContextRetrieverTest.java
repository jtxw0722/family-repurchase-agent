package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.NormalizationRagContext;
import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.ProductTitleCleaner;
import com.jtxw.familyagent.domain.policy.UnitFamily;
import com.jtxw.familyagent.infrastructure.persistence.ProductAliasRepository;
import com.jtxw.familyagent.infrastructure.persistence.ProductNegativeAliasRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 17:15:00
 * @Description: 商品归一化 RAG 上下文检索测试，验证规则摘要与 ProductRuleMatcher 使用一致的 include/exclude 语义
 */
class NormalizationRagContextRetrieverTest {
    @Test
    void shouldNotSummarizeRuleWhenExcludeKeywordMatchesCurrentRule() {
        ProductAliasRepository productAliasRepository = mock(ProductAliasRepository.class);
        ProductNegativeAliasRepository productNegativeAliasRepository = mock(ProductNegativeAliasRepository.class);
        when(productAliasRepository.listSimilar(anyString(), anyInt())).thenReturn(List.of());
        when(productNegativeAliasRepository.listSimilar(anyString(), anyInt())).thenReturn(List.of());
        NormalizationRagContextRetriever retriever = new NormalizationRagContextRetriever(
                new ProductTitleCleaner(),
                productAliasRepository,
                productNegativeAliasRepository,
                () -> List.of(new ProductRule("cat_litter", "猫砂", "宠物用品", 100,
                        List.of("猫砂"), List.of("猫砂盆"), "kg", UnitFamily.WEIGHT))
        );

        NormalizationRagContext context = retriever.retrieve("猫砂盆大号防外溅", "", "", "");

        assertThat(context.ruleSummaries()).isEmpty();
    }
}
