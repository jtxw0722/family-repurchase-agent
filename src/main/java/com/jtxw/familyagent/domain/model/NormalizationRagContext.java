package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 00:27:12
 * @Description: 商品归一化 LLM Advisor 的轻量 RAG 上下文。
 */
public record NormalizationRagContext(
        /**
         * SQLite 归一化规则库中相关规则摘要。
         */
        List<String> ruleSummaries,
        /**
         * 标准品类说明，帮助 LLM 区分消耗品、耐用品和非复购品。
         */
        List<String> categoryHints
) {
}
