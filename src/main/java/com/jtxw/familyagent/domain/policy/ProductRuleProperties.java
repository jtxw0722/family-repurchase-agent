package com.jtxw.familyagent.domain.policy;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/05/29/23:20
 * @Description: 从 product-rules.yml 加载商品匹配规则。
 */
@Component
public class ProductRuleProperties {
    private static final String DEFAULT_RULES_FILE = "product-rules.yml";

    private final List<ProductRule> rules;

    public ProductRuleProperties() {
        this(loadRulesFromClasspath());
    }

    public ProductRuleProperties(List<ProductRule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public List<ProductRule> rules() {
        return rules;
    }

    @SuppressWarnings("unchecked")
    private static List<ProductRule> loadRulesFromClasspath() {
        ClassPathResource resource = new ClassPathResource(DEFAULT_RULES_FILE);
        if (!resource.exists()) {
            return List.of();
        }
        try (InputStream inputStream = resource.getInputStream()) {
            Object loaded = new Yaml().load(inputStream);
            if (!(loaded instanceof Map<?, ?> root)) {
                return List.of();
            }
            Object ruleNodes = root.get("rules");
            if (!(ruleNodes instanceof List<?> nodes)) {
                return List.of();
            }
            List<ProductRule> loadedRules = new ArrayList<>();
            for (Object node : nodes) {
                if (node instanceof Map<?, ?> map) {
                    loadedRules.add(toRule((Map<String, Object>) map));
                }
            }
            return loadedRules;
        } catch (Exception e) {
            throw new IllegalStateException("加载 " + DEFAULT_RULES_FILE + " 失败", e);
        }
    }

    private static ProductRule toRule(Map<String, Object> values) {
        return new ProductRule(
                stringValue(values.get("id")),
                stringValue(values.get("normalizedName")),
                intValue(values.get("priority")),
                stringList(values.get("includeKeywords")),
                stringList(values.get("excludeKeywords")),
                stringValue(values.get("standardUnit")),
                unitFamily(values.get("unitFamily"))
        );
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(String::valueOf)
                .filter(text -> !text.isBlank())
                .toList();
    }

    private static UnitFamily unitFamily(Object value) {
        if (value == null) {
            return UnitFamily.UNKNOWN;
        }
        return UnitFamily.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
    }
}
