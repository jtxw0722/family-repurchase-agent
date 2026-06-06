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
            return weightPreferred(unit, "g", "猫主食罐按重量比价更稳定，当前单位不适合批量确认。");
        }
        if (CAT_STICK.equals(safeNormalizedName) || CAT_SNACK.equals(safeNormalizedName)
                || CAT_SOUP.equals(safeNormalizedName)) {
            return weightPreferred(unit, "g", safeNormalizedName + "按重量比价更稳定，当前单位不适合批量确认。");
        }
        if (CAT_FOOD.equals(safeNormalizedName) || CAT_LITTER.equals(safeNormalizedName)) {
            if ("kg".equalsIgnoreCase(unit) || "g".equalsIgnoreCase(unit)) {
                return TargetUnitSafetyResult.safe("kg");
            }
            return unsafe(unit, safeNormalizedName + "适合按 kg 建立价格基准，当前单位不适合批量确认。");
        }
        if (CONTACT_LENS.equals(safeNormalizedName)) {
            return "片".equals(unit)
                    ? TargetUnitSafetyResult.safe("片")
                    : unsafe(unit, "美瞳 / 隐形眼镜适合按片建立价格基准，当前单位不适合批量确认。");
        }
        if (SKINCARE_NAMES.contains(safeNormalizedName)) {
            if ("ml".equalsIgnoreCase(unit)) {
                return TargetUnitSafetyResult.safe("ml");
            }
            if ("g".equalsIgnoreCase(unit)) {
                return TargetUnitSafetyResult.safe("g");
            }
            return unsafe(unit, safeNormalizedName + "适合按 ml 或 g 建立价格基准，当前单位不适合批量确认。");
        }
        return TargetUnitSafetyResult.safe(unit);
    }

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
        if ("KG".equals(matchedUnit) || "Kg".equals(matchedUnit) || "kg".equalsIgnoreCase(matchedUnit)) {
            return "kg";
        }
        if ("G".equals(matchedUnit) || "g".equalsIgnoreCase(matchedUnit)) {
            return "g";
        }
        if ("ML".equals(matchedUnit) || "Ml".equals(matchedUnit) || "ml".equalsIgnoreCase(matchedUnit)) {
            return "ml";
        }
        if ("L".equals(matchedUnit) || "l".equalsIgnoreCase(matchedUnit)) {
            return "L";
        }
        return matchedUnit;
    }

    private TargetUnitSafetyResult weightPreferred(String unit, String canonicalUnit, String unsafeReason) {
        if ("g".equalsIgnoreCase(unit) || "kg".equalsIgnoreCase(unit)) {
            return TargetUnitSafetyResult.safe(canonicalUnit);
        }
        if (unit.isBlank() || UNSAFE_PACKAGE_UNITS.contains(unit)) {
            return unsafe(unit, unsafeReason);
        }
        return unsafe(unit, unsafeReason);
    }

    private TargetUnitSafetyResult unsafe(String targetUnit, String reason) {
        return new TargetUnitSafetyResult(targetUnit, false, reason);
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * targetUnit 安全校验结果。
     *
     * @param targetUnit         归并后的建议目标单位
     * @param batchApprovalSafe  是否允许进入 pending_batch_approval
     * @param unsafeReason       不允许批量确认时追加到 suggestion reason 的中文说明
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
