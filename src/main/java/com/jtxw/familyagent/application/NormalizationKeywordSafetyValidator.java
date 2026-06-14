package com.jtxw.familyagent.application;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 00:44:27
 * @Description: 归一化规则关键词安全校验器，负责拦截完整商品标题、规格组合、营销词和店铺词等危险 keyword
 */
@Service
public class NormalizationKeywordSafetyValidator {
    /**
     * keyword 最大长度，超过该长度通常已经接近商品标题而非可复用规则关键词。
     */
    private static final int MAX_KEYWORD_LENGTH = 12;
    /**
     * 明显规格组合正则，例如 380g+400g、2.5kg*8。
     */
    private static final Pattern SPEC_COMBINATION_PATTERN = Pattern.compile(
            ".*\\d+(\\.\\d+)?\\s*(g|kg|ml|l|L|片|抽|包|瓶|袋)\\s*([+*xX×])\\s*\\d+.*");
    /**
     * 纯数字或纯规格正则，不适合作为规则关键词。
     */
    private static final Pattern ONLY_SPEC_PATTERN = Pattern.compile(
            "^\\d+(\\.\\d+)?\\s*(g|kg|ml|l|L|片|抽|包|瓶|袋)?$");
    /**
     * 价格、优惠、活动和赠品类词，命中后不允许作为自动写入关键词。
     */
    private static final List<String> PROMOTION_WORDS = List.of(
            "优惠", "满减", "预售", "定金", "尾款", "赠品", "活动", "买一送一", "券", "折扣", "秒杀", "升级");
    /**
     * 店铺来源类词，命中后说明 keyword 混入渠道信息。
     */
    private static final List<String> SHOP_WORDS = List.of("旗舰店", "自营", "官方", "专卖店", "直营");

    /**
     * 校验 keyword 是否可以作为规则关键词写入。
     *
     * @param keyword 待校验关键词，允许为空
     * @return 警告原因列表；为空表示通过安全校验
     */
    public List<String> validate(String keyword) {
        if (keyword == null || keyword.trim().isBlank()) {
            return List.of("keyword 不能为空");
        }
        String value = keyword.trim();
        if (value.length() > MAX_KEYWORD_LENGTH) {
            return List.of("危险 keyword：长度过长，疑似完整商品标题");
        }
        if (SPEC_COMBINATION_PATTERN.matcher(value).matches()) {
            return List.of("危险 keyword：包含明显规格组合");
        }
        if (ONLY_SPEC_PATTERN.matcher(value).matches()) {
            return List.of("危险 keyword：纯数字或纯规格不能作为关键词");
        }
        if (containsAny(value, PROMOTION_WORDS)) {
            return List.of("危险 keyword：包含价格、优惠、预售、赠品或活动词");
        }
        if (containsAny(value, SHOP_WORDS)) {
            return List.of("危险 keyword：包含店铺或渠道词");
        }
        return List.of();
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
