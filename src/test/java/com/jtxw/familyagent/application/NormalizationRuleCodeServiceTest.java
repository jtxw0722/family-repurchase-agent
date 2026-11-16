package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.infrastructure.translation.OfflineRuleCodeTranslator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * @Author: jtxw
 * @Date: 2026/11/16 19:42:51
 * @Description: 离线编码建议测试，使用真实 Maven 依赖词库验证中文英译、已有编码复用及缺词边界，不访问模型
 */
class NormalizationRuleCodeServiceTest {
    /** 真实第三方词库适配器，所有用例共享不可变词库，避免重复加载。 */
    private static final OfflineRuleCodeTranslator TRANSLATOR = new OfflineRuleCodeTranslator();
    /** 只读规则库替身，不创建真实规则或修改历史数据。 */
    private final NormalizationLibraryService library = mock(NormalizationLibraryService.class);
    /** 被测应用服务，构造依赖不包含模型客户端或模型配置。 */
    private final NormalizationRuleCodeService service = new NormalizationRuleCodeService(library, TRANSLATOR);

    @ParameterizedTest
    @CsvSource({"橄榄油,olive_oil", "花生油,peanut_oil", "卫生巾,sanitary_napkin", "大米,rice", "洗发水,shampoo"})
    void shouldTranslateUsingRealDependencyDictionary(String name, String expectedCode) {
        when(library.listLibraryItems()).thenReturn(List.of());
        assertThat(service.suggest(" " + name + " "))
                .isEqualTo(new NormalizationRuleCodeService.RuleCodeSuggestion(expectedCode, false));
        verify(library).listLibraryItems();
        verifyNoMoreInteractions(library);
    }

    @Test
    void shouldReuseEvenDisabledExistingRuleBeforeDictionaryLookup() {
        when(library.listLibraryItems()).thenReturn(List.of(rule("cooking_olive", "橄榄油", false)));
        assertThat(service.suggest("橄榄油"))
                .isEqualTo(new NormalizationRuleCodeService.RuleCodeSuggestion("cooking_olive", true));
    }

    @Test
    void shouldReuseExistingNameNotIncludedInDictionary() {
        when(library.listLibraryItems()).thenReturn(List.of(rule("cat_food", "猫粮", true)));
        assertThat(service.suggest("猫粮").ruleCode()).isEqualTo("cat_food");
    }

    @Test
    void shouldReportCollisionWithoutChangingCodeOrWritingRules() {
        when(library.listLibraryItems()).thenReturn(List.of(rule("olive_oil", "食用橄榄油", false)));
        assertThat(service.suggest("橄榄油").exists()).isTrue();
        verify(library).listLibraryItems();
        verifyNoMoreInteractions(library);
    }

    @Test
    void shouldConvertEnglishNameWithoutDictionaryLookup() {
        when(library.listLibraryItems()).thenReturn(List.of());
        assertThat(service.suggest("Dry-Cell Battery").ruleCode()).isEqualTo("dry_cell_battery");
    }

    @ParameterizedTest
    @ValueSource(strings = {"未知商品词库测试", "猫粮", "有机橄榄油", "樱花沐浴露"})
    void shouldRequireManualCodeForMissingFullPhraseInsteadOfJoiningCharacterTranslations(String name) {
        when(library.listLibraryItems()).thenReturn(List.of());
        assertThatThrownBy(() -> service.suggest(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("离线词库未收录该完整名称或译义不明确，请手动填写英文编码。");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldRejectEmptyNameBeforeQueryingLibrary(String name) {
        assertThatThrownBy(() -> service.suggest(name)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(library);
    }

    @Test
    void shouldRejectOverlongNameBeforeQueryingLibrary() {
        assertThatThrownBy(() -> service.suggest("米".repeat(121))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(library);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "a.b", "a"})
    void shouldRejectInvalidOrOverlongDictionaryOutput(String output) {
        when(library.listLibraryItems()).thenReturn(List.of());
        OfflineRuleCodeTranslator invalidTranslator = mock(OfflineRuleCodeTranslator.class);
        String translated = "a".equals(output) ? output.repeat(97) : output;
        when(invalidTranslator.translate("测试名称")).thenReturn(translated);
        NormalizationRuleCodeService invalidService = new NormalizationRuleCodeService(library, invalidTranslator);
        assertThatThrownBy(() -> invalidService.suggest("测试名称"))
                .isInstanceOf(IllegalStateException.class).hasMessage("未能生成有效英文编码，请手动填写。");
    }

    /** 构造无历史样本的规则，仅用于验证复用和重复检测。 */
    private NormalizationLibraryItem rule(String code, String name, boolean enabled) {
        return new NormalizationLibraryItem(code, name, "测试品类", "kg", "weight",
                List.of(), List.of(), 0, 80, enabled, "manual");
    }
}
