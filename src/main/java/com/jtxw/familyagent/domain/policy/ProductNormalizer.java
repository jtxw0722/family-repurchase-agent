package com.jtxw.familyagent.domain.policy;

import org.springframework.stereotype.Component;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/10:38
 * @Description: 商品名称归一化规则，用于降低同类消耗品名称差异。
 */
@Component
public class ProductNormalizer {
    public String normalize(String productName) {
        if (productName == null || productName.isBlank()) {
            return "未命名商品";
        }
        String name = productName.trim();
        if (name.contains("猫砂")) {
            return "猫砂";
        }
        if (name.contains("猫粮")) {
            return "猫粮";
        }
        if (name.contains("纸巾") || name.contains("抽纸")) {
            return "纸巾";
        }
        if (name.contains("洗衣液")) {
            return "洗衣液";
        }
        return name;
    }
}
