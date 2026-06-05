package com.jtxw.familyagent.domain.policy;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * @Author: jtxw
 * @Date: 2026/06/05
 * @Description: 商品标题清洗器，用确定性规则生成别名学习使用的 alias_key。
 */
@Component
public class ProductTitleCleaner {
    /**
     * 根据商品标题和 SKU 生成稳定匹配键。
     *
     * <p>alias_key 用于人工确认后的正向/负向别名匹配，需要尽量去除标点和空白，
     * 但保留中文、英文、数字和常见规格单位，避免引入模糊匹配风险。</p>
     *
     * @param productName 原始商品标题
     * @param sku         商品 SKU
     * @return 清洗后的匹配键
     */
    public String aliasKey(String productName, String sku) {
        String text = safeText(productName) + " " + safeText(sku);
        StringBuilder builder = new StringBuilder();
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isLetterOrDigit(ch) || isCjk(ch)) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private boolean isCjk(char ch) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        return Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS.equals(block)
                || Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A.equals(block)
                || Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS.equals(block);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
