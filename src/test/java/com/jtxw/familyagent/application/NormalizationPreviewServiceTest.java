package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.ParsedNormalizationPreview;
import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.ProductRuleMatcher;
import com.jtxw.familyagent.domain.policy.ProductRuleProvider;
import com.jtxw.familyagent.domain.policy.UnitFamily;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 22:25:00
 * @Description: OCR 候选归一化预览服务测试，覆盖多字段规则命中、未命中提示和敏感原文隔离
 */
class NormalizationPreviewServiceTest {
    /** 包含咖啡关键词的测试归一化规则。 */
    private static final ProductRule COFFEE_RULE = new ProductRule(
            "instant_coffee", "即饮咖啡", 100,
            List.of("咖啡"), List.of("咖啡机"), "L", UnitFamily.VOLUME);
    /** 使用内存规则提供接口的待测试预览服务。 */
    private final NormalizationPreviewService service = previewService(COFFEE_RULE);

    @Test
    void shouldReturnMatchedPreviewWhenRuleMatchesProductName() {
        ParsedNormalizationPreview preview = service.enrich(candidate(
                "巢咖啡即饮咖啡丝滑拿铁268m..", "丝滑拿铁", "测试旗舰店")).normalization();

        assertMatchedCoffeePreview(preview);
    }

    @Test
    void shouldReturnMatchedPreviewWhenRuleMatchesSku() {
        ParsedNormalizationPreview preview = service.enrich(candidate(
                "合成饮品", "丝滑咖啡268ml", "测试旗舰店")).normalization();

        assertMatchedCoffeePreview(preview);
    }

    @Test
    void shouldReturnMatchedPreviewWhenRuleMatchesShopName() {
        ParsedNormalizationPreview preview = service.enrich(candidate(
                "合成饮品", "丝滑拿铁268ml", "测试咖啡旗舰店")).normalization();

        assertMatchedCoffeePreview(preview);
    }

    @Test
    void shouldReturnUnmatchedPreviewWhenNoRuleMatches() {
        ParsedNormalizationPreview preview = service.enrich(candidate(
                "合成洗护用品", "清香型500ml", "测试旗舰店")).normalization();

        assertThat(preview.matched()).isFalse();
        assertThat(preview.ruleCode()).isNull();
        assertThat(preview.matchedText()).isNull();
        assertThat(preview.warning()).contains("未命中归一化规则");
    }

    @Test
    void shouldNotUseSourceTextAsMatchedText() {
        ParsedPurchaseCandidate enrichedCandidate = service.enrich(new ParsedPurchaseCandidate(
                "巢咖啡即饮咖啡", "丝滑拿铁268ml", 26.14D, 268D, "ml",
                "tmall", "jtxw", "2026-05-22", "测试咖啡旗舰店", "测试候选",
                "订单号：TEST-ORDER-NO 地址：测试地址 快递单号：TEST-EXPRESS-NO",
                0.9D, List.of()));

        assertThat(enrichedCandidate.normalization().matchedText())
                .isEqualTo("巢咖啡即饮咖啡 丝滑拿铁268ml 测试咖啡旗舰店")
                .doesNotContain("订单号", "地址", "快递单号");
    }

    private ParsedPurchaseCandidate candidate(String productName, String sku, String shopName) {
        return new ParsedPurchaseCandidate(productName, sku, 26.14D, 268D, "ml",
                "tmall", "jtxw", "2026-05-22", shopName, "测试候选",
                "脱敏测试原文", 0.9D, List.of());
    }

    private void assertMatchedCoffeePreview(ParsedNormalizationPreview preview) {
        assertThat(preview.matched()).isTrue();
        assertThat(preview.ruleCode()).isEqualTo("instant_coffee");
        assertThat(preview.normalizedName()).isEqualTo("即饮咖啡");
        assertThat(preview.targetUnit()).isEqualTo("L");
        assertThat(preview.matchedKeyword()).isEqualTo("咖啡");
        assertThat(preview.sampleCount()).isNull();
        assertThat(preview.warning()).isNull();
    }

    private static NormalizationPreviewService previewService(ProductRule... rules) {
        ProductRuleProvider ruleProvider = () -> List.of(rules);
        return new NormalizationPreviewService(new ProductRuleMatcher(ruleProvider), ruleProvider);
    }
}
