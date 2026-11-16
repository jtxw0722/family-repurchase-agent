package com.jtxw.familyagent.infrastructure.translation;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/11/16 19:42:51
 * @Description: 基础设施层离线中英词库适配器，从 Maven 依赖资源读取完整词条，不调用模型或外部翻译服务
 */
@Component
public class OfflineRuleCodeTranslator {
    /** offline-translate 依赖内置的中英词库资源名，不允许使用用户指定路径。 */
    private static final String DICTIONARY_RESOURCE = "cn-2-en.txt";
    /** 编码可接受的英文短语形式；复杂释义及非英文结果必须人工确认。 */
    private static final Pattern ENGLISH_PHRASE = Pattern.compile("[A-Za-z][A-Za-z0-9 -]*");
    /** 完整中文词条到英文释义的只读快照，无词条时不回退到逐字拼接。 */
    private final Map<String, String> translations;

    /** 初始化依赖包词库；资源缺失或损坏时明确失败，不回退为网络调用。 */
    public OfflineRuleCodeTranslator() {
        translations = loadDictionary();
    }

    /**
     * 查询完整词条的英文释义；不会写库、创建文件或发起网络请求。
     *
     * @param name 已清洗的完整中文名称，不允许为空
     * @return 适合生成规则编码的英文短语
     * @throws IllegalArgumentException 完整词条未收录、存在多种释义或释义不适合编码时抛出
     */
    public String translate(String name) {
        String english = translations.get(name);
        if (english == null || !ENGLISH_PHRASE.matcher(english).matches()) {
            throw new IllegalArgumentException("离线词库未收录该完整名称或译义不明确，请手动填写英文编码。");
        }
        return english;
    }

    /**
     * 从第三方依赖读取词库并排除存在不同释义的重复词条，保持结果稳定。
     *
     * @return 不可修改的中英词条快照
     * @throws IllegalStateException 依赖资源缺失、不可读取或没有有效词条时抛出
     */
    private Map<String, String> loadDictionary() {
        Map<String, String> entries = new HashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(DICTIONARY_RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf('=');
                if (separator <= 0 || separator == line.length() - 1) {
                    throw new IllegalStateException("离线中英词库格式无效。");
                }
                String chinese = line.substring(0, separator).trim();
                String english = line.substring(separator + 1).trim();
                String previous = entries.putIfAbsent(chinese, english);
                // 不根据词条顺序猜测多义词；留给用户明确其业务含义。
                if (previous != null && !previous.equals(english)) {
                    ambiguousNames.add(chinese);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("离线中英词库加载失败，请检查运行包依赖。", exception);
        }
        ambiguousNames.forEach(entries::remove);
        if (entries.isEmpty()) {
            throw new IllegalStateException("离线中英词库为空，请检查运行包依赖。");
        }
        return Map.copyOf(entries);
    }
}
