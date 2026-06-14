package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.domain.model.NormalizationRuleSuggestion;
import com.jtxw.familyagent.domain.policy.UnitFamily;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 规则维护建议本地校验器，负责校验 LLM create_rule 和 add_keyword 建议是否可安全应用到规则库
 */
@Service
public class NormalizationRuleSuggestionValidator {
    /**
     * create_rule 最低置信度，低于该值不允许自动应用。
     */
    private static final double CREATE_RULE_MIN_CONFIDENCE = 0.8D;
    /**
     * add_keyword 最低置信度，低于该值不允许自动应用。
     */
    private static final double ADD_KEYWORD_MIN_CONFIDENCE = 0.75D;
    /**
     * 规则编码格式，只允许小写字母、数字和下划线。
     */
    private static final Pattern RULE_CODE_PATTERN = Pattern.compile("^[a-z0-9_]+$");
    /**
     * 重量单位族允许的标准单位。
     */
    private static final Set<String> WEIGHT_UNITS = Set.of("kg", "g");
    /**
     * 体积单位族允许的标准单位。
     */
    private static final Set<String> VOLUME_UNITS = Set.of("L", "ml");
    /**
     * 抽数单位族允许的标准单位。
     */
    private static final Set<String> DRAW_COUNT_UNITS = Set.of("抽");
    /**
     * 数量单位族允许的标准单位。
     */
    private static final Set<String> COUNT_UNITS = Set.of("颗", "片", "条", "件");
    /**
     * keyword 安全校验器，负责拦截危险关键词。
     */
    private final NormalizationKeywordSafetyValidator keywordSafetyValidator;

    /**
     * 创建规则维护建议本地校验器。
     *
     * @param keywordSafetyValidator keyword 安全校验器，不能为空
     */
    public NormalizationRuleSuggestionValidator(NormalizationKeywordSafetyValidator keywordSafetyValidator) {
        this.keywordSafetyValidator = keywordSafetyValidator;
    }

    /**
     * 校验单条规则维护建议。
     *
     * @param suggestion   LLM 输出建议
     * @param libraryItems 当前规则库条目，用于重复和冲突判断
     * @return 校验警告列表；为空表示建议可应用
     */
    public List<String> validate(NormalizationRuleSuggestion suggestion, List<NormalizationLibraryItem> libraryItems) {
        if (suggestion == null) {
            return List.of("建议不能为空");
        }
        String operation = safeText(suggestion.operation()).toLowerCase(Locale.ROOT);
        return switch (operation) {
            case "create_rule" -> validateCreateRule(suggestion, libraryItems);
            case "add_keyword" -> validateAddKeyword(suggestion, libraryItems);
            default -> List.of("不支持的规则维护建议操作：" + suggestion.operation());
        };
    }

    private List<String> validateCreateRule(NormalizationRuleSuggestion suggestion,
                                            List<NormalizationLibraryItem> libraryItems) {
        List<String> warnings = new ArrayList<>();
        String ruleCode = safeText(suggestion.ruleCode());
        if (ruleCode.isBlank()) {
            warnings.add("ruleCode 不能为空");
        } else if (!RULE_CODE_PATTERN.matcher(ruleCode).matches()) {
            warnings.add("ruleCode 只允许小写字母、数字和下划线");
        }
        if (safeText(suggestion.normalizedName()).isBlank()) {
            warnings.add("normalizedName 不能为空");
        }
        if (safeText(suggestion.standardUnit()).isBlank()) {
            warnings.add("standardUnit 不能为空");
        }
        if (safeText(suggestion.unitFamily()).isBlank()) {
            warnings.add("unitFamily 不能为空");
        } else if (!unitCompatible(suggestion.standardUnit(), suggestion.unitFamily())) {
            warnings.add("standardUnit 与 unitFamily 不兼容");
        }
        List<String> safeKeywords = safeKeywords(suggestion.keywords(), warnings);
        if (safeKeywords.isEmpty()) {
            warnings.add("keywords 至少需要一个安全 include keyword");
        }
        // create_rule 的排除关键词也会写入规则库，必须与 include keyword 使用同一套安全校验。
        safeKeywords(suggestion.excludeKeywords(), warnings);
        if (existsRuleCode(ruleCode, libraryItems)) {
            warnings.add("ruleCode 已存在：" + ruleCode);
        }
        if (existsNormalizedName(suggestion.normalizedName(), libraryItems)) {
            warnings.add("normalizedName 已存在：" + suggestion.normalizedName());
        }
        if (confidence(suggestion) < CREATE_RULE_MIN_CONFIDENCE) {
            warnings.add("create_rule 置信度低于 0.8");
        }
        return warnings;
    }

    private List<String> validateAddKeyword(NormalizationRuleSuggestion suggestion,
                                            List<NormalizationLibraryItem> libraryItems) {
        List<String> warnings = new ArrayList<>();
        NormalizationLibraryItem rule = findRule(suggestion.ruleCode(), libraryItems);
        if (rule == null) {
            warnings.add("ruleCode 不存在：" + suggestion.ruleCode());
        }
        String matchType = safeText(suggestion.matchType()).toLowerCase(Locale.ROOT);
        if (!"include".equals(matchType) && !"exclude".equals(matchType)) {
            warnings.add("matchType 只允许 include 或 exclude");
        }
        warnings.addAll(keywordSafetyValidator.validate(suggestion.keyword()));
        if (rule != null && !safeText(suggestion.keyword()).isBlank()) {
            String keyword = suggestion.keyword().trim();
            if ("include".equals(matchType) && rule.excludeKeywords().contains(keyword)) {
                warnings.add("同一 rule 下同一 keyword 不能同时作为 include 和 exclude");
            }
            if ("exclude".equals(matchType) && rule.keywords().contains(keyword)) {
                warnings.add("同一 rule 下同一 keyword 不能同时作为 include 和 exclude");
            }
        }
        if (confidence(suggestion) < ADD_KEYWORD_MIN_CONFIDENCE) {
            warnings.add("add_keyword 置信度低于 0.75");
        }
        return warnings;
    }

    private List<String> safeKeywords(List<String> keywords, List<String> warnings) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        List<String> safeKeywords = new ArrayList<>();
        for (String keyword : keywords) {
            List<String> keywordWarnings = keywordSafetyValidator.validate(keyword);
            if (keywordWarnings.isEmpty()) {
                safeKeywords.add(keyword.trim());
            } else {
                warnings.addAll(keywordWarnings);
            }
        }
        return safeKeywords;
    }

    private boolean unitCompatible(String standardUnit, String unitFamilyText) {
        String unit = safeText(standardUnit);
        String familyText = safeText(unitFamilyText).toUpperCase(Locale.ROOT);
        try {
            UnitFamily unitFamily = UnitFamily.valueOf(familyText);
            return switch (unitFamily) {
                case WEIGHT -> WEIGHT_UNITS.contains(unit.toLowerCase(Locale.ROOT));
                case VOLUME -> VOLUME_UNITS.contains("l".equalsIgnoreCase(unit) ? "L" : unit.toLowerCase(Locale.ROOT));
                case DRAW_COUNT -> DRAW_COUNT_UNITS.contains(unit);
                case COUNT -> COUNT_UNITS.contains(unit);
                case UNKNOWN -> false;
            };
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean existsRuleCode(String ruleCode, List<NormalizationLibraryItem> libraryItems) {
        return libraryItems.stream().anyMatch(item -> safeText(item.ruleCode()).equals(ruleCode));
    }

    private boolean existsNormalizedName(String normalizedName, List<NormalizationLibraryItem> libraryItems) {
        return libraryItems.stream().anyMatch(item -> safeText(item.normalizedName()).equals(safeText(normalizedName)));
    }

    private NormalizationLibraryItem findRule(String ruleCode, List<NormalizationLibraryItem> libraryItems) {
        return libraryItems.stream()
                .filter(item -> safeText(item.ruleCode()).equals(safeText(ruleCode)))
                .findFirst()
                .orElse(null);
    }

    private double confidence(NormalizationRuleSuggestion suggestion) {
        return suggestion.confidence() == null ? 0D : suggestion.confidence();
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}
