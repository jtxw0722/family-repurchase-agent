package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestionCandidate;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestionResult;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 规则维护建议 Advisor 接口，负责根据候选商品和现有规则库返回结构化规则维护建议，不直接写数据库
 */
public interface NormalizationRuleSuggestionAdvisor {

    /**
     * 分析规则库缺口并返回结构化维护建议。
     *
     * @param candidates   候选商品样本，只包含允许发送给 LLM 的脱敏字段
     * @param libraryItems 当前规则库摘要，包含规则编码、标准名、单位和关键词
     * @return 结构化规则维护建议结果
     */
    NormalizationRuleSuggestionResult advise(List<NormalizationRuleSuggestionCandidate> candidates,
                                             List<NormalizationLibraryItem> libraryItems);
}
