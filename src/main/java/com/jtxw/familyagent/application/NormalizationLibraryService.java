package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.AddNormalizationRuleKeywordCommand;
import com.jtxw.familyagent.application.command.CreateNormalizationRuleCommand;
import com.jtxw.familyagent.application.command.DisableNormalizationRuleKeywordCommand;
import com.jtxw.familyagent.application.command.NormalizationLibraryOperationCommand;
import com.jtxw.familyagent.application.command.UpdateNormalizationRuleCommand;
import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.domain.model.NormalizationLibraryOperationResult;
import com.jtxw.familyagent.domain.model.NormalizationRuleMutationResult;
import com.jtxw.familyagent.domain.policy.UnitFamily;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.NormalizationRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 16:50:03
 * @Description: 归一化名称库应用服务，负责编排规则库查询、统一写操作和动态历史样本统计
 */
@Service
public class NormalizationLibraryService {
    /**
     * 统一入口新增规则动作。
     */
    private static final String ACTION_CREATE_RULE = "create_rule";
    /**
     * 统一入口更新规则动作。
     */
    private static final String ACTION_UPDATE_RULE = "update_rule";
    /**
     * 统一入口禁用规则动作。
     */
    private static final String ACTION_DISABLE_RULE = "disable_rule";
    /**
     * 统一入口新增关键词动作。
     */
    private static final String ACTION_ADD_KEYWORD = "add_keyword";
    /**
     * 统一入口禁用关键词动作。
     */
    private static final String ACTION_DISABLE_KEYWORD = "disable_keyword";
    /**
     * 手动维护规则的 source 标记。
     */
    private static final String SOURCE_MANUAL = "manual";
    /**
     * 规则和关键词未显式传入优先级时使用的默认值。
     */
    private static final int DEFAULT_PRIORITY = 100;
    /**
     * include 关键词类型，命中后当前规则可作为候选。
     */
    private static final String MATCH_TYPE_INCLUDE = "include";
    /**
     * exclude 关键词类型，命中后排除当前规则。
     */
    private static final String MATCH_TYPE_EXCLUDE = "exclude";
    /**
     * 规则编码格式，只允许小写字母、数字和下划线，避免外部引用不稳定。
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
     * 数据库初始化组件，确保查询名称库前本地 SQLite 表结构和默认规则已存在。
     */
    private final DatabaseInitializer databaseInitializer;
    /**
     * 归一化规则仓储，负责读取规则、关键词和样本统计。
     */
    private final NormalizationRuleRepository normalizationRuleRepository;

    public NormalizationLibraryService(DatabaseInitializer databaseInitializer,
                                       NormalizationRuleRepository normalizationRuleRepository) {
        this.databaseInitializer = databaseInitializer;
        this.normalizationRuleRepository = normalizationRuleRepository;
    }

    /**
     * 查询归一化名称库。
     *
     * @return 名称库条目列表，包含规则信息、正负关键词和动态样本数量
     */
    public List<NormalizationLibraryItem> listLibraryItems() {
        databaseInitializer.initialize();
        return normalizationRuleRepository.listLibraryItems();
    }

    /**
     * 执行归一化规则库统一写操作。
     *
     * <p>REST 层只暴露一个 POST 入口，应用层根据 action 分派到内部规则或关键词维护方法。</p>
     *
     * @param command 统一写操作命令，不能为空
     * @return 统一写操作响应结果
     */
    @Transactional
    public NormalizationLibraryOperationResult operate(NormalizationLibraryOperationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("归一化规则库操作请求不能为空");
        }
        String action = requiredText(command.action(), "action 不能为空").toLowerCase(Locale.ROOT);
        return switch (action) {
            case ACTION_CREATE_RULE -> operateCreateRule(action, command);
            case ACTION_UPDATE_RULE -> operateUpdateRule(action, command);
            case ACTION_DISABLE_RULE -> toOperationResult(action, disableRule(command.ruleCode()));
            case ACTION_ADD_KEYWORD -> operateAddKeyword(action, command);
            case ACTION_DISABLE_KEYWORD -> toOperationResult(action, disableKeyword(
                    new DisableNormalizationRuleKeywordCommand(command.ruleCode(), command.keyword(), command.matchType())));
            default -> throw new IllegalArgumentException("不支持的归一化规则库操作：" + command.action());
        };
    }

    /**
     * 新增归一化规则及初始关键词。
     *
     * @param command 新增规则命令
     * @return 规则新增结果
     */
    @Transactional
    public NormalizationRuleMutationResult createRule(CreateNormalizationRuleCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("新增归一化规则请求不能为空");
        }
        databaseInitializer.initialize();
        String ruleCode = normalizeRuleCode(command.ruleCode());
        String normalizedName = requiredText(command.normalizedName(), "normalizedName 不能为空");
        String category = optionalText(command.category());
        UnitSpec unitSpec = normalizeUnitSpec(command.standardUnit(), command.unitFamily());
        List<String> keywords = normalizeKeywords(command.keywords());
        List<String> excludeKeywords = normalizeKeywords(command.excludeKeywords());
        validateKeywordListsNotConflict(keywords, excludeKeywords);
        if (normalizationRuleRepository.existsRuleCode(ruleCode)) {
            throw new IllegalArgumentException("归一化规则编码已存在：" + ruleCode);
        }
        if (normalizationRuleRepository.existsNormalizedName(normalizedName, null)) {
            throw new IllegalArgumentException("归一化商品名称已存在：" + normalizedName);
        }

        long ruleId = normalizationRuleRepository.createRule(ruleCode, normalizedName, category,
                unitSpec.standardUnit(), unitSpec.databaseUnitFamily(), command.priority(), SOURCE_MANUAL);
        insertKeywords(ruleId, keywords, MATCH_TYPE_INCLUDE);
        insertKeywords(ruleId, excludeKeywords, MATCH_TYPE_EXCLUDE);
        return NormalizationRuleMutationResult.rule(ruleCode, normalizedName, "created", "归一化规则已新增");
    }

    /**
     * 更新归一化规则主表基础信息。
     *
     * @param command 更新规则命令
     * @return 规则更新结果
     */
    @Transactional
    public NormalizationRuleMutationResult updateRule(UpdateNormalizationRuleCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("更新归一化规则请求不能为空");
        }
        databaseInitializer.initialize();
        String ruleCode = normalizeRuleCode(command.ruleCode());
        NormalizationRuleRepository.NormalizationRuleRow rule = requireRule(ruleCode);
        String normalizedName = command.normalizedName() == null
                ? rule.normalizedName()
                : requiredText(command.normalizedName(), "normalizedName 不能为空");
        String category = command.category() == null ? optionalText(rule.category()) : optionalText(command.category());
        String standardUnit = command.standardUnit() == null ? rule.standardUnit() : command.standardUnit();
        String unitFamily = command.unitFamily() == null ? rule.unitFamily() : command.unitFamily();
        UnitSpec unitSpec = normalizeUnitSpec(standardUnit, unitFamily);
        int priority = command.priority() == null ? rule.priority() : command.priority();
        boolean enabled = command.enabled() == null ? rule.enabled() : command.enabled();
        if (normalizationRuleRepository.existsNormalizedName(normalizedName, rule.id())) {
            throw new IllegalArgumentException("归一化商品名称已存在：" + normalizedName);
        }

        normalizationRuleRepository.updateRule(rule.id(), normalizedName, category, unitSpec.standardUnit(),
                unitSpec.databaseUnitFamily(), priority, enabled);
        return NormalizationRuleMutationResult.rule(ruleCode, normalizedName, "updated", "归一化规则已更新");
    }

    /**
     * 新增或恢复启用归一化规则关键词。
     *
     * @param command 新增关键词命令
     * @return 关键词维护结果
     */
    @Transactional
    public NormalizationRuleMutationResult addKeyword(AddNormalizationRuleKeywordCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("新增归一化关键词请求不能为空");
        }
        databaseInitializer.initialize();
        String ruleCode = normalizeRuleCode(command.ruleCode());
        NormalizationRuleRepository.NormalizationRuleRow rule = requireRule(ruleCode);
        String keyword = requiredText(command.keyword(), "keyword 不能为空");
        String matchType = normalizeMatchType(command.matchType());
        validateKeywordConflict(rule.id(), keyword, matchType);

        return normalizationRuleRepository.findKeyword(rule.id(), keyword, matchType)
                .map(existingKeyword -> {
                    if (existingKeyword.enabled()) {
                        return NormalizationRuleMutationResult.keyword(ruleCode, keyword, matchType,
                                "keyword_exists", "归一化关键词已存在");
                    }
                    normalizationRuleRepository.enableKeyword(existingKeyword.id(), command.priority(), SOURCE_MANUAL);
                    return NormalizationRuleMutationResult.keyword(ruleCode, keyword, matchType,
                            "keyword_added", "归一化关键词已新增");
                })
                .orElseGet(() -> {
                    normalizationRuleRepository.insertKeyword(rule.id(), keyword, matchType,
                            command.priority(), SOURCE_MANUAL);
                    return NormalizationRuleMutationResult.keyword(ruleCode, keyword, matchType,
                            "keyword_added", "归一化关键词已新增");
                });
    }

    /**
     * 软禁用归一化规则关键词。
     *
     * @param command 禁用关键词命令
     * @return 关键词禁用结果
     */
    @Transactional
    public NormalizationRuleMutationResult disableKeyword(DisableNormalizationRuleKeywordCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("禁用归一化关键词请求不能为空");
        }
        databaseInitializer.initialize();
        String ruleCode = normalizeRuleCode(command.ruleCode());
        NormalizationRuleRepository.NormalizationRuleRow rule = requireRule(ruleCode);
        String keyword = requiredText(command.keyword(), "keyword 不能为空");
        String matchType = normalizeMatchType(command.matchType());
        NormalizationRuleRepository.NormalizationRuleKeywordRow keywordRow = normalizationRuleRepository
                .findKeyword(rule.id(), keyword, matchType)
                .orElseThrow(() -> new IllegalArgumentException("归一化关键词不存在：" + keyword));
        if (keywordRow.enabled()) {
            normalizationRuleRepository.disableKeyword(keywordRow.id());
        }
        return NormalizationRuleMutationResult.keyword(ruleCode, keyword, matchType,
                "keyword_disabled", "归一化关键词已禁用");
    }

    /**
     * 软禁用归一化规则。
     *
     * @param ruleCode 规则业务编码
     * @return 规则禁用结果
     */
    @Transactional
    public NormalizationRuleMutationResult disableRule(String ruleCode) {
        databaseInitializer.initialize();
        String normalizedRuleCode = normalizeRuleCode(ruleCode);
        NormalizationRuleRepository.NormalizationRuleRow rule = requireRule(normalizedRuleCode);
        if (rule.enabled()) {
            normalizationRuleRepository.disableRule(rule.id());
        }
        return NormalizationRuleMutationResult.rule(normalizedRuleCode, rule.normalizedName(),
                "rule_disabled", "归一化规则已禁用");
    }

    private NormalizationLibraryOperationResult operateCreateRule(String action,
                                                                  NormalizationLibraryOperationCommand command) {
        NormalizationRuleMutationResult result = createRule(new CreateNormalizationRuleCommand(
                command.ruleCode(),
                command.normalizedName(),
                command.category(),
                command.standardUnit(),
                command.unitFamily(),
                priorityOrDefault(command.priority()),
                command.keywords(),
                command.excludeKeywords()
        ));
        return toOperationResult(action, result);
    }

    private NormalizationLibraryOperationResult operateUpdateRule(String action,
                                                                  NormalizationLibraryOperationCommand command) {
        NormalizationRuleMutationResult result = updateRule(new UpdateNormalizationRuleCommand(
                command.ruleCode(),
                requiredText(command.normalizedName(), "normalizedName 不能为空"),
                command.category(),
                requiredText(command.standardUnit(), "standardUnit 不能为空"),
                requiredText(command.unitFamily(), "unitFamily 不能为空"),
                command.priority(),
                command.enabled()
        ));
        return toOperationResult(action, result);
    }

    private NormalizationLibraryOperationResult operateAddKeyword(String action,
                                                                  NormalizationLibraryOperationCommand command) {
        NormalizationRuleMutationResult result = addKeyword(new AddNormalizationRuleKeywordCommand(
                command.ruleCode(),
                command.keyword(),
                command.matchType(),
                priorityOrDefault(command.priority())
        ));
        return toOperationResult(action, result);
    }

    private NormalizationLibraryOperationResult toOperationResult(String action,
                                                                 NormalizationRuleMutationResult result) {
        return NormalizationLibraryOperationResult.success(action, result.message(), result.ruleCode(),
                result.normalizedName(), affectedRows(result));
    }

    private int affectedRows(NormalizationRuleMutationResult result) {
        return "keyword_exists".equals(result.action()) ? 0 : 1;
    }

    private int priorityOrDefault(Integer priority) {
        return priority == null ? DEFAULT_PRIORITY : priority;
    }

    private void insertKeywords(long ruleId, List<String> keywords, String matchType) {
        for (String keyword : keywords) {
            normalizationRuleRepository.insertKeyword(ruleId, keyword, matchType, 100, SOURCE_MANUAL);
        }
    }

    private NormalizationRuleRepository.NormalizationRuleRow requireRule(String ruleCode) {
        return normalizationRuleRepository.findRuleByCode(ruleCode)
                .orElseThrow(() -> new IllegalArgumentException("归一化规则不存在：" + ruleCode));
    }

    private String normalizeRuleCode(String ruleCode) {
        String value = requiredText(ruleCode, "ruleCode 不能为空");
        if (!RULE_CODE_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("ruleCode 只能包含小写字母、数字和下划线：" + value);
        }
        return value;
    }

    private String normalizeMatchType(String matchType) {
        String value = requiredText(matchType, "matchType 不能为空").toLowerCase(Locale.ROOT);
        if (!MATCH_TYPE_INCLUDE.equals(value) && !MATCH_TYPE_EXCLUDE.equals(value)) {
            throw new IllegalArgumentException("matchType 只允许 include 或 exclude：" + matchType);
        }
        return value;
    }

    private UnitSpec normalizeUnitSpec(String standardUnit, String unitFamilyText) {
        String unit = requiredText(standardUnit, "standardUnit 不能为空");
        UnitFamily unitFamily = parseUnitFamily(unitFamilyText);
        String canonicalUnit = canonicalStandardUnit(unit, unitFamily);
        return new UnitSpec(canonicalUnit, unitFamily.name().toLowerCase(Locale.ROOT));
    }

    private UnitFamily parseUnitFamily(String unitFamilyText) {
        String value = requiredText(unitFamilyText, "unitFamily 不能为空").toUpperCase(Locale.ROOT);
        try {
            UnitFamily unitFamily = UnitFamily.valueOf(value);
            if (unitFamily == UnitFamily.UNKNOWN) {
                throw new IllegalArgumentException("unitFamily 不支持 UNKNOWN");
            }
            return unitFamily;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 unitFamily：" + unitFamilyText, e);
        }
    }

    private String canonicalStandardUnit(String standardUnit, UnitFamily unitFamily) {
        return switch (unitFamily) {
            case WEIGHT -> {
                String unit = standardUnit.toLowerCase(Locale.ROOT);
                if (WEIGHT_UNITS.contains(unit)) {
                    yield unit;
                }
                throw incompatibleUnit(standardUnit, unitFamily);
            }
            case VOLUME -> {
                String unit = "l".equalsIgnoreCase(standardUnit) ? "L" : standardUnit.toLowerCase(Locale.ROOT);
                if (VOLUME_UNITS.contains(unit)) {
                    yield unit;
                }
                throw incompatibleUnit(standardUnit, unitFamily);
            }
            case DRAW_COUNT -> {
                if (DRAW_COUNT_UNITS.contains(standardUnit)) {
                    yield standardUnit;
                }
                throw incompatibleUnit(standardUnit, unitFamily);
            }
            case COUNT -> {
                if (COUNT_UNITS.contains(standardUnit)) {
                    yield standardUnit;
                }
                throw incompatibleUnit(standardUnit, unitFamily);
            }
            case UNKNOWN -> throw incompatibleUnit(standardUnit, unitFamily);
        };
    }

    private IllegalArgumentException incompatibleUnit(String standardUnit, UnitFamily unitFamily) {
        return new IllegalArgumentException("standardUnit 与 unitFamily 不兼容：" + standardUnit + " / " + unitFamily);
    }

    private List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        Set<String> uniqueKeywords = new LinkedHashSet<>();
        for (String keyword : keywords) {
            uniqueKeywords.add(requiredText(keyword, "keyword 不能为空"));
        }
        return new ArrayList<>(uniqueKeywords);
    }

    private void validateKeywordListsNotConflict(List<String> keywords, List<String> excludeKeywords) {
        for (String keyword : keywords) {
            if (excludeKeywords.contains(keyword)) {
                throw new IllegalArgumentException("同一规则下 keyword 不能同时作为 include 和 exclude：" + keyword);
            }
        }
    }

    private void validateKeywordConflict(long ruleId, String keyword, String matchType) {
        if (normalizationRuleRepository.existsEnabledKeywordWithOtherMatchType(ruleId, keyword, matchType)) {
            throw new IllegalArgumentException("同一规则下 keyword 不能同时作为 include 和 exclude：" + keyword);
        }
    }

    private String requiredText(String value, String message) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String optionalText(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 已校验并归一化后的单位配置。
     *
     * @param standardUnit       标准统计单位
     * @param databaseUnitFamily 数据库存储的单位族文本
     */
    private record UnitSpec(String standardUnit, String databaseUnitFamily) {
    }
}
