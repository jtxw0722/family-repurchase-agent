package com.jtxw.familyagent.application;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 19:17:57
 * @Description: LLM 建议 targetUnit 归并与批量确认安全校验器。
 */
@Component
public class SuggestedTargetUnitCanonicalizer {
    /**
     * 标准目标单位：千克。
     */
    private static final String TARGET_UNIT_KILOGRAM = "kg";
    /**
     * 目标单位别名：大写千克。
     */
    private static final String TARGET_UNIT_KILOGRAM_UPPER = "KG";
    /**
     * 目标单位别名：首字母大写千克。
     */
    private static final String TARGET_UNIT_KILOGRAM_TITLE = "Kg";
    /**
     * 标准目标单位：克。
     */
    private static final String TARGET_UNIT_GRAM = "g";
    /**
     * 目标单位别名：大写克。
     */
    private static final String TARGET_UNIT_GRAM_UPPER = "G";
    /**
     * 标准目标单位：毫升。
     */
    private static final String TARGET_UNIT_MILLILITER = "ml";
    /**
     * 目标单位别名：大写毫升。
     */
    private static final String TARGET_UNIT_MILLILITER_UPPER = "ML";
    /**
     * 目标单位别名：首字母大写毫升。
     */
    private static final String TARGET_UNIT_MILLILITER_TITLE = "Ml";
    /**
     * 标准目标单位：升。
     */
    private static final String TARGET_UNIT_LITER = "L";
    /**
     * 目标单位别名：小写升。
     */
    private static final String TARGET_UNIT_LITER_LOWER = "l";
    /**
     * 标准目标单位：片。
     */
    private static final String TARGET_UNIT_PIECE = "片";
    /**
     * 猫主食罐标准品类名称。
     */
    private static final String CAT_MAIN_CAN = "猫主食罐";
    /**
     * 猫条标准品类名称。
     */
    private static final String CAT_STICK = "猫条";
    /**
     * 猫零食标准品类名称。
     */
    private static final String CAT_SNACK = "猫零食";
    /**
     * 猫汤包标准品类名称。
     */
    private static final String CAT_SOUP = "猫汤包";
    /**
     * 猫粮标准品类名称。
     */
    private static final String CAT_FOOD = "猫粮";
    /**
     * 猫砂标准品类名称。
     */
    private static final String CAT_LITTER = "猫砂";
    /**
     * 美瞳标准品类名称。
     */
    private static final String CONTACT_LENS = "美瞳";
    /**
     * 件数或包装单位，不能直接作为规格差异较大商品的批量确认单位。
     */
    private static final Set<String> UNSAFE_PACKAGE_UNITS = Set.of("罐", "盒", "包", "杯", "条");
    /**
     * 护肤与个人护理消耗品，允许按容量或重量建立价格基准。
     */
    private static final Set<String> SKINCARE_NAMES = Set.of("精华液", "爽肤水", "乳液", "防晒", "洗面奶");
    /**
     * targetUnit 中允许保存的纯单位，从规格值中提取后再进入安全判断。
     */
    private static final Pattern PURE_UNIT_PATTERN = Pattern.compile("(?i)(kg|g|ml|l|片|罐|盒|包|杯|条)");

    /**
     * 归并 targetUnit 并判断当前 normalizedName + targetUnit 是否适合批量确认。
     *
     * @param normalizedName 系统内部标准品类名称
     * @param targetUnit     LLM 建议目标单位
     * @return 单位安全校验结果
     */
    public TargetUnitSafetyResult canonicalize(String normalizedName, String targetUnit) {
        String safeNormalizedName = safeText(normalizedName);
        String unit = pureUnit(targetUnit);
        if (CAT_MAIN_CAN.equals(safeNormalizedName)) {
            return weightPreferred(unit, TARGET_UNIT_GRAM, "猫主食罐按重量比价更稳定，当前单位不适合批量确认。");
        }
        if (CAT_STICK.equals(safeNormalizedName) || CAT_SNACK.equals(safeNormalizedName)
                || CAT_SOUP.equals(safeNormalizedName)) {
            return weightPreferred(unit, TARGET_UNIT_GRAM, safeNormalizedName + "按重量比价更稳定，当前单位不适合批量确认。");
        }
        if (CAT_FOOD.equals(safeNormalizedName) || CAT_LITTER.equals(safeNormalizedName)) {
            if (TARGET_UNIT_KILOGRAM.equalsIgnoreCase(unit) || TARGET_UNIT_GRAM.equalsIgnoreCase(unit)) {
                return TargetUnitSafetyResult.safe(TARGET_UNIT_KILOGRAM);
            }
            return unsafe(unit, safeNormalizedName + "适合按 kg 建立价格基准，当前单位不适合批量确认。");
        }
        if (CONTACT_LENS.equals(safeNormalizedName)) {
            return TARGET_UNIT_PIECE.equals(unit)
                    ? TargetUnitSafetyResult.safe(TARGET_UNIT_PIECE)
                    : unsafe(unit, "美瞳 / 隐形眼镜适合按片建立价格基准，当前单位不适合批量确认。");
        }
        if (SKINCARE_NAMES.contains(safeNormalizedName)) {
            if (TARGET_UNIT_MILLILITER.equalsIgnoreCase(unit)) {
                return TargetUnitSafetyResult.safe(TARGET_UNIT_MILLILITER);
            }
            if (TARGET_UNIT_GRAM.equalsIgnoreCase(unit)) {
                return TargetUnitSafetyResult.safe(TARGET_UNIT_GRAM);
            }
            return unsafe(unit, safeNormalizedName + "适合按 ml 或 g 建立价格基准，当前单位不适合批量确认。");
        }
        return TargetUnitSafetyResult.safe(unit);
    }

    /**
     * 从 LLM 建议的 targetUnit 中提取可用于安全判断的纯单位。
     *
     * <p>先做空值安全处理，并将中文乘号 × 替换为 *；再通过 PURE_UNIT_PATTERN 从规格文本中提取单位；
     * 对 KG/Kg/kg、G/g、ML/Ml/ml、L/l 做规范化。如果无法提取单位，则返回原始清洗后的文本。</p>
     *
     * @param targetUnit LLM 建议目标单位或规格文本
     * @return 归并后的纯单位，或原始清洗文本
     */
    private String pureUnit(String targetUnit) {
        String unit = safeText(targetUnit).replace('×', '*');
        if (unit.isBlank()) {
            return "";
        }
        Matcher matcher = PURE_UNIT_PATTERN.matcher(unit);
        if (!matcher.find()) {
            return unit;
        }
        String matchedUnit = matcher.group(1);
        if (TARGET_UNIT_KILOGRAM_UPPER.equals(matchedUnit) || TARGET_UNIT_KILOGRAM_TITLE.equals(matchedUnit)
                || TARGET_UNIT_KILOGRAM.equalsIgnoreCase(matchedUnit)) {
            return TARGET_UNIT_KILOGRAM;
        }
        if (TARGET_UNIT_GRAM_UPPER.equals(matchedUnit) || TARGET_UNIT_GRAM.equalsIgnoreCase(matchedUnit)) {
            return TARGET_UNIT_GRAM;
        }
        if (TARGET_UNIT_MILLILITER_UPPER.equals(matchedUnit) || TARGET_UNIT_MILLILITER_TITLE.equals(matchedUnit)
                || TARGET_UNIT_MILLILITER.equalsIgnoreCase(matchedUnit)) {
            return TARGET_UNIT_MILLILITER;
        }
        if (TARGET_UNIT_LITER.equals(matchedUnit) || TARGET_UNIT_LITER_LOWER.equalsIgnoreCase(matchedUnit)) {
            return TARGET_UNIT_LITER;
        }
        return matchedUnit;
    }

    /**
     * 用于适合按重量比价的品类安全判断。
     *
     * <p>g / kg 视为安全单位，返回指定标准单位；空单位或包装单位视为不适合批量确认；
     * 其他单位当前也按不安全处理，避免错误批量确认。</p>
     *
     * @param unit          归并后的目标单位
     * @param canonicalUnit 安全时返回的标准单位
     * @param unsafeReason  不允许批量确认时的说明
     * @return targetUnit 安全校验结果
     */
    private TargetUnitSafetyResult weightPreferred(String unit, String canonicalUnit, String unsafeReason) {
        if (TARGET_UNIT_GRAM.equalsIgnoreCase(unit) || TARGET_UNIT_KILOGRAM.equalsIgnoreCase(unit)) {
            return TargetUnitSafetyResult.safe(canonicalUnit);
        }
        if (unit.isBlank() || UNSAFE_PACKAGE_UNITS.contains(unit)) {
            return unsafe(unit, unsafeReason);
        }
        return unsafe(unit, unsafeReason);
    }

    /**
     * 创建不允许批量确认的 TargetUnitSafetyResult。
     *
     * <p>保留当前 targetUnit 和不安全原因，供上层追加到 suggestion reason。</p>
     *
     * @param targetUnit 当前目标单位
     * @param reason     不允许批量确认的原因
     * @return 不允许批量确认的安全校验结果
     */
    private TargetUnitSafetyResult unsafe(String targetUnit, String reason) {
        return new TargetUnitSafetyResult(targetUnit, false, reason);
    }

    /**
     * 对字符串做 null-safe 清洗。
     *
     * <p>null 返回空字符串；非 null 时返回 trim 后文本。</p>
     *
     * @param value 原始文本
     * @return 清洗后的文本
     */
    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * targetUnit 安全校验结果。
     *
     * @param targetUnit        归并后的建议目标单位
     * @param batchApprovalSafe 是否允许进入 pending_batch_approval
     * @param unsafeReason      不允许批量确认时追加到 suggestion reason 的中文说明
     */
    public record TargetUnitSafetyResult(String targetUnit,
                                         boolean batchApprovalSafe,
                                         String unsafeReason) {
        /**
         * 创建允许批量确认的 targetUnit 结果。
         *
         * @param targetUnit 归并后的建议目标单位
         * @return 允许批量确认的结果
         */
        public static TargetUnitSafetyResult safe(String targetUnit) {
            return new TargetUnitSafetyResult(targetUnit, true, "");
        }
    }
}
