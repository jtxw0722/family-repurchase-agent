package com.jtxw.familyagent.domain.policy;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * @Author: jtxw
 * @Date: 2026/06/05
 * @Description: 订单归属人标准化组件，统一手动录入和文件导入的 owner 口径。
 */
@Component
public class OwnerNormalizer {
    /**
     * ASCII 字符边界，低于该值的字符按半角字符处理。
     */
    private static final int ASCII_BOUNDARY = 128;

    /**
     * 将 owner 归一到稳定的存储标识。
     *
     * @param owner 原始 owner
     * @return 标准 owner；空值默认归属 jtxw
     */
    public String normalize(String owner) {
        if (owner == null || owner.isBlank()) {
            return "jtxw";
        }
        String normalized = owner.trim();
        if (normalized.chars().allMatch(ch -> ch < ASCII_BOUNDARY)) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        return normalized;
    }
}
