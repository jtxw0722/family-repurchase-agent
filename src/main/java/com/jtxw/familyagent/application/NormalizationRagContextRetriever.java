package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.NormalizationRagContext;
import com.jtxw.familyagent.domain.policy.ProductRule;
import com.jtxw.familyagent.domain.policy.ProductRuleProvider;
import com.jtxw.familyagent.domain.policy.ProductTitleCleaner;
import com.jtxw.familyagent.infrastructure.persistence.ProductAliasRepository;
import com.jtxw.familyagent.infrastructure.persistence.ProductNegativeAliasRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 16:50:03
 * @Description: 商品归一化轻量 RAG 上下文检索服务，从 SQLite 别名和归一化规则 Provider 构造 LLM 证据。
 */
@Service
public class NormalizationRagContextRetriever {
    private static final int POSITIVE_ALIAS_LIMIT = 5;
    private static final int NEGATIVE_ALIAS_LIMIT = 5;
    private static final int RULE_LIMIT = 10;

    private final ProductTitleCleaner productTitleCleaner;
    private final ProductAliasRepository productAliasRepository;
    private final ProductNegativeAliasRepository productNegativeAliasRepository;
    private final ProductRuleProvider productRuleProvider;

    public NormalizationRagContextRetriever(ProductTitleCleaner productTitleCleaner,
                                            ProductAliasRepository productAliasRepository,
                                            ProductNegativeAliasRepository productNegativeAliasRepository,
                                            ProductRuleProvider productRuleProvider) {
        this.productTitleCleaner = productTitleCleaner;
        this.productAliasRepository = productAliasRepository;
        this.productNegativeAliasRepository = productNegativeAliasRepository;
        this.productRuleProvider = productRuleProvider;
    }

    /**
     * 根据商品标题、SKU 和电商分类构建 LLM 上下文。
     *
     * <p>第一版只做本地轻量检索，RAG 证据仅进入提示词，不直接决定数据库状态。</p>
     *
     * @param productName 原始商品名称
     * @param sku         商品规格或 SKU
     * @param category    电商一级分类
     * @param subCategory 电商二级分类
     * @return 轻量 RAG 上下文
     */
    public NormalizationRagContext retrieve(String productName, String sku, String category, String subCategory) {
        String aliasKey = productTitleCleaner.aliasKey(productName, sku);
        List<String> positiveAliases = productAliasRepository.listSimilar(aliasKey, POSITIVE_ALIAS_LIMIT).stream()
                .map(alias -> "正向别名：" + alias.alias() + " => " + alias.normalizedName()
                        + "，targetUnit=" + safeText(alias.targetUnit()))
                .toList();
        List<String> negativeAliases = productNegativeAliasRepository.listSimilar(aliasKey, NEGATIVE_ALIAS_LIMIT).stream()
                .map(alias -> "负向别名：" + alias.alias() + "，拒绝品类=" + alias.rejectedNormalizedName()
                        + "，reason=" + safeText(alias.reason()))
                .toList();
        List<String> ruleSummaries = matchedRuleSummaries(productName, sku, category, subCategory);
        List<String> categoryHints = List.of(
                "系统面向家庭 / 个人长期复购消耗品，不只面向家庭共享物品。",
                "REPURCHASE_CONSUMABLE：会持续消耗、可能重复购买、适合建立本地价格基准的商品，包括家庭日用品、宠物消耗品、个人护理和美妆护肤消耗品。",
                "猫罐头：猫主食罐、主食罐、猫罐头、湿粮、餐盒、一餐一杯、奶猫罐、幼猫罐、成猫罐。",
                "猫零食：猫条、猫汤包、咕噜酱、补水零食、猫咪零食。",
                "猫粮：猫粮、全价猫粮、主粮、干粮、烘焙粮、冻干主粮、生骨肉主粮。",
                "个人护理 / 美妆护肤消耗品：美瞳、隐形眼镜、日抛、精华液、爽肤水、乳液、面霜、防晒、洗面奶、卸妆用品、面膜。",
                "色号强相关彩妆：粉底液、遮瑕、口红、唇釉、眉笔、眼影、腮红、眼线笔可能复购，但色号差异明显，第一阶段优先 REVIEW。",
                "DURABLE：长期使用的耐用品，例如手机壳、包、衣服、鞋、饰品、相机配件、茶具、猫砂盆、储粮桶、猫粮勺。",
                "COUPON_OR_DEPOSIT：预售券、定金、优惠券、服务券等支付或权益类商品，应排除。",
                "券/定金：预售券、定金、锁定、加赠、优惠券、预定礼。",
                "NON_REPURCHASE：酒店住宿、一次性礼品、临时购买品、服务类订单、偶发性商品等不适合作为本地复购价格基准的商品或服务。",
                "猫主食罐、猫条、猫粮、猫零食、猫汤包不要混成同一个 normalizedName。"
        );
        return new NormalizationRagContext(positiveAliases, negativeAliases, ruleSummaries, categoryHints);
    }

    private List<String> matchedRuleSummaries(String productName, String sku, String category, String subCategory) {
        String text = String.join(" ", safeText(productName), safeText(sku), safeText(category), safeText(subCategory));
        Set<String> summaries = new LinkedHashSet<>();
        List<ProductRule> rules = productRuleProvider.listEnabledRules();
        boolean hasIncludedRuleKeyword = false;
        for (ProductRule rule : rules) {
            if (summaries.size() >= RULE_LIMIT) {
                break;
            }
            boolean included = overlaps(text, rule.includeKeywords());
            if (included) {
                hasIncludedRuleKeyword = true;
            }
            // 与 ProductRuleMatcher 保持一致：exclude 只排除当前规则，且优先级高于 include。
            if (included && !overlaps(text, rule.excludeKeywords())) {
                summaries.add("规则：" + rule.id()
                        + "，normalizedName=" + rule.normalizedName()
                        + "，category=" + safeText(rule.category())
                        + "，standardUnit=" + safeText(rule.standardUnit())
                        + "，unitFamily=" + rule.unitFamily()
                        + "，includeKeywords=" + rule.includeKeywords()
                        + "，excludeKeywords=" + rule.excludeKeywords());
            }
        }
        if (summaries.isEmpty() && !hasIncludedRuleKeyword) {
            return rules.stream()
                    .limit(Math.min(RULE_LIMIT, rules.size()))
                    .map(rule -> "规则：" + rule.id()
                            + "，normalizedName=" + rule.normalizedName()
                            + "，category=" + safeText(rule.category())
                            + "，standardUnit=" + safeText(rule.standardUnit())
                            + "，unitFamily=" + rule.unitFamily())
                    .toList();
        }
        return new ArrayList<>(summaries);
    }

    private boolean overlaps(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (text.contains(keyword.trim())) {
                return true;
            }
        }
        return false;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
