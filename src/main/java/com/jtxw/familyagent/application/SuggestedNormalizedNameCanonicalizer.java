package com.jtxw.familyagent.application;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 13:13:18
 * @Description: LLM 建议标准品类归并器，将自由文本 suggestedNormalizedName 收敛为系统内部 normalizedName。
 */
@Component
public class SuggestedNormalizedNameCanonicalizer {
    /**
     * 猫主食罐同义名称。
     */
    private static final List<String> CAT_MAIN_CAN_NAMES = List.of("主食罐", "猫主食罐", "猫主食罐头",
            "主食罐头", "猫罐头主食罐", "全价主食罐", "主食餐盒", "猫湿粮罐头", "湿粮罐头");
    /**
     * 猫罐头宽泛名称，需要结合商品上下文拆分为主食罐或零食。
     */
    private static final List<String> CAT_CAN_BROAD_NAMES = List.of("猫罐头", "猫罐", "罐头");
    /**
     * 猫主食罐上下文关键词。
     */
    private static final List<String> CAT_MAIN_CAN_CONTEXT = List.of("猫", "幼猫", "成猫", "猫咪", "主食",
            "全价", "湿粮", "餐盒", "罐头");
    /**
     * 明确表示猫主食罐的上下文关键词。
     */
    private static final List<String> CAT_MAIN_CAN_STRONG_CONTEXT = List.of("主食", "主食罐", "主食罐头",
            "全价", "全阶段", "主食餐盒", "餐盒", "湿粮", "非零食", "成猫幼猫");
    /**
     * 猫条同义名称。
     */
    private static final List<String> CAT_STICK_NAMES = List.of("猫条", "咕噜酱", "猫咪条", "条状猫零食", "补水餐包");
    /**
     * 猫汤包同义名称。
     */
    private static final List<String> CAT_SOUP_NAMES = List.of("猫汤包", "汤包", "鲜鸡汤", "补水汤包");
    /**
     * 猫粮同义名称。
     */
    private static final List<String> CAT_FOOD_NAMES = List.of("猫粮", "全价猫粮", "主粮", "干粮",
            "烘焙粮", "冻干主粮", "生骨肉主粮");
    /**
     * 猫零食同义名称。
     */
    private static final List<String> CAT_SNACK_NAMES = List.of("猫零食", "零食罐", "尝鲜零食", "奶酪冻",
            "冻干零食", "非主食猫零食");
    /**
     * 明确表示非主食猫罐或零食的上下文关键词。
     */
    private static final List<String> CAT_SNACK_CAN_CONTEXT = List.of("零食罐", "补水罐", "尝鲜罐", "猫咪零食");
    /**
     * 美瞳和隐形眼镜同义名称。
     */
    private static final List<String> CONTACT_LENS_NAMES = List.of("美瞳", "隐形眼镜", "日抛", "月抛", "彩片");
    /**
     * 精华液同义名称。
     */
    private static final List<String> ESSENCE_NAMES = List.of("精华", "精华液", "精华油", "兰花油",
            "修护精华", "抗皱精华", "保湿精华");
    /**
     * 爽肤水同义名称。
     */
    private static final List<String> TONER_NAMES = List.of("爽肤水", "化妆水", "柔肤水", "精粹水", "保湿水");
    /**
     * 乳液同义名称。
     */
    private static final List<String> LOTION_NAMES = List.of("乳液", "保湿乳", "修护乳", "面部乳液");
    /**
     * 面霜同义名称。
     */
    private static final List<String> CREAM_NAMES = List.of("面霜", "保湿霜", "修护霜", "抗皱霜");
    /**
     * 防晒同义名称。
     */
    private static final List<String> SUNSCREEN_NAMES = List.of("防晒", "防晒霜", "防晒乳", "防晒液");
    /**
     * 洗面奶同义名称。
     */
    private static final List<String> CLEANSER_NAMES = List.of("洗面奶", "洁面", "洁面乳", "洁面膏");
    /**
     * 卸妆用品同义名称。
     */
    private static final List<String> MAKEUP_REMOVER_NAMES = List.of("卸妆油", "卸妆水", "卸妆膏", "卸妆乳");
    /**
     * 面膜同义名称。
     */
    private static final List<String> MASK_NAMES = List.of("面膜", "贴片面膜", "睡眠面膜");

    /**
     * 将 LLM 建议的标准品类归并为系统内部标准 normalizedName。
     *
     * <p>归并依据只使用原始商品名、SKU 和 LLM 建议名称，不读取价格、店铺、owner 等隐私或统计字段。</p>
     *
     * @param rawProductName          原始商品名称
     * @param sku                     商品规格或 SKU
     * @param suggestedNormalizedName LLM 建议标准品类
     * @return 系统内部标准 normalizedName；无法归并时返回清洗后的原建议
     */
    public String canonicalize(String rawProductName, String sku, String suggestedNormalizedName) {
        if (suggestedNormalizedName == null || suggestedNormalizedName.isBlank()) {
            return suggestedNormalizedName;
        }
        String normalizedName = suggestedNormalizedName.trim();
        String rawContext = safeText(rawProductName) + " " + safeText(sku);
        String context = rawContext + " " + normalizedName;

        if (containsAny(normalizedName, CAT_CAN_BROAD_NAMES)) {
            if (containsAny(rawContext, CAT_MAIN_CAN_STRONG_CONTEXT)) {
                return "猫主食罐";
            }
            if (containsAny(rawContext, CAT_SNACK_CAN_CONTEXT)) {
                return "猫零食";
            }
        }
        boolean isMainCanName = containsAny(normalizedName, CAT_MAIN_CAN_NAMES);
        if (isMainCanName) {
            boolean hasMainCanContext = containsAny(context, CAT_MAIN_CAN_CONTEXT) || normalizedName.contains("猫");
            if (hasMainCanContext) {
                return "猫主食罐";
            }
        }
        if (containsAny(context, CAT_STICK_NAMES)) {
            return "猫条";
        }
        if (containsAny(context, CAT_SOUP_NAMES)) {
            return "猫汤包";
        }
        if (containsAny(context, CAT_FOOD_NAMES)) {
            return "猫粮";
        }
        if (containsAny(context, CAT_SNACK_NAMES)) {
            return "猫零食";
        }
        if (containsAny(context, CONTACT_LENS_NAMES)) {
            return "美瞳";
        }
        if (containsAny(context, ESSENCE_NAMES)) {
            return "精华液";
        }
        if (containsAny(context, TONER_NAMES)) {
            return "爽肤水";
        }
        if (containsAny(context, LOTION_NAMES)) {
            return "乳液";
        }
        if (containsAny(context, CREAM_NAMES)) {
            return "面霜";
        }
        if (containsAny(context, SUNSCREEN_NAMES)) {
            return "防晒";
        }
        if (containsAny(context, CLEANSER_NAMES)) {
            return "洗面奶";
        }
        if (containsAny(context, MAKEUP_REMOVER_NAMES)) {
            return "卸妆用品";
        }
        if (containsAny(context, MASK_NAMES)) {
            return "面膜";
        }
        return normalizedName;
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
