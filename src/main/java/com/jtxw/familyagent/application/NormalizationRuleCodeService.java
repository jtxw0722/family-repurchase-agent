package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.infrastructure.translation.OfflineRuleCodeTranslator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/11/16 19:42:51
 * @Description: 规则编码建议应用服务，协调第三方词库离线英译、编码格式校验和重复检查，不执行规则写入
 */
@Service
public class NormalizationRuleCodeService {
    /** 待翻译名称最多允许 120 个字符，限制过长的业务输入。 */
    private static final int MAX_NAME_LENGTH = 120;
    /** 建议编码最多允许 96 个字符，便于人工核对和后续引用。 */
    private static final int MAX_CODE_LENGTH = 96;
    /** 英文名称允许直接转换的字符，不将中文或拼音冒充英译结果。 */
    private static final Pattern ENGLISH_NAME_PATTERN = Pattern.compile("[A-Za-z0-9 _-]+");
    /** 建议编码使用小写英文和数字，以单个下划线分词且必须以字母开头。 */
    private static final Pattern RULE_CODE_PATTERN = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)*");
    /** 无法形成合法编码时的简洁提示，不回传内部异常。 */
    private static final String TRANSLATION_FAILURE_MESSAGE = "未能生成有效英文编码，请手动填写。";
    /** 规则库查询应用服务，用于复用已有编码并检查所有状态的重复编码。 */
    private final NormalizationLibraryService libraryService;
    /** 第三方词库离线翻译适配器，不依赖模型配置或网络。 */
    private final OfflineRuleCodeTranslator translator;

    /**
     * 创建只读的规则编码建议服务。
     *
     * @param libraryService 规则库查询服务
     * @param translator 第三方词库离线翻译适配器
     */
    public NormalizationRuleCodeService(NormalizationLibraryService libraryService,
                                        OfflineRuleCodeTranslator translator) {
        this.libraryService = libraryService;
        this.translator = translator;
    }

    /**
     * 根据归一化名称离线建议英文编码，不发起网络请求，也不会创建或更新规则。
     *
     * @param normalizedName 商品归一化名称，去空白后必须为 1 到 120 个字符
     * @return 建议编码及其是否已存在；重复时交由用户调整，不自动添加编号
     * @throws IllegalArgumentException 名称为空、过长、完整词条未收录或释义不明确时抛出
     * @throws IllegalStateException 生成的编码不符合格式要求时抛出简洁提示
     */
    public RuleCodeSuggestion suggest(String normalizedName) {
        String name = normalizedName == null ? "" : normalizedName.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("请填写 1 到 120 个字符的归一化名称。");
        }
        List<NormalizationLibraryItem> libraryItems = libraryService.listLibraryItems();
        // 已有名称使用原有英文编码，避免同一个名称在不同翻译结果之间来回变化。
        for (NormalizationLibraryItem item : libraryItems) {
            if (name.equals(item.normalizedName())) {
                return new RuleCodeSuggestion(item.ruleCode(), true);
            }
        }
        String ruleCode = ENGLISH_NAME_PATTERN.matcher(name).matches()
                ? normalizeCode(name) : normalizeCode(translator.translate(name));
        validateCode(ruleCode);
        boolean exists = libraryItems.stream().anyMatch(item -> ruleCode.equals(item.ruleCode()));
        return new RuleCodeSuggestion(ruleCode, exists);
    }

    /** 将英文中的空格和连字符统一为下划线，不删除中文或无关说明文字。 */
    private String normalizeCode(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "_");
    }

    /** 拒绝中文、解释文本、空编码和超长词库译文，避免把无效建议提交到规则库。 */
    private void validateCode(String ruleCode) {
        if (ruleCode.length() > MAX_CODE_LENGTH || !RULE_CODE_PATTERN.matcher(ruleCode).matches()) {
            throw new IllegalStateException(TRANSLATION_FAILURE_MESSAGE);
        }
    }

    /**
     * @Author: jtxw
     * @Date: 2026/11/16 19:42:51
     * @Description: 规则编码建议结果，供表单展示英文编码及重复状态，不代表已保存规则
     * @param ruleCode 英文规则编码，不允许为空
     * @param exists 编码是否已存在，包含已禁用规则；为 true 时应提示修改或选择更新
     */
    public record RuleCodeSuggestion(String ruleCode, boolean exists) {
    }
}
