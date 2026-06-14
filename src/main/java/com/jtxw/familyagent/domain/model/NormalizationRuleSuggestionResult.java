package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 规则维护建议任务执行结果，汇总 LLM 建议、应用统计和任务级警告
 *
 * @param suggestions LLM 规则维护建议列表，包含每条建议的 applied、skipped 和 warnings
 * @param warnings    任务级警告列表，允许为空列表
 */
public record NormalizationRuleSuggestionResult(List<NormalizationRuleSuggestion> suggestions,
                                                List<String> warnings) {
}
