package com.jtxw.familyagent.domain.policy;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/06/04/19:39
 * @Description: 数量与单位解析组件，将商品标题和 SKU 中的规格文本折算为标准数量和单价。
 */
@Component
public class QuantityUnitParser {
    private static final Set<String> COUNT_UNITS = Set.of("条", "片", "颗", "只", "块", "节");

    /**
     * 解析标准数量、单位和单价。
     *
     * @param normalizedName ProductNameNormalizer 输出的标准品类
     * @param targetUnit     ProductNameNormalizer 输出的目标单位
     * @param productName    原始商品标题
     * @param sku            原始规格/SKU 文本
     * @param price          用于统计的实付/折算后金额
     * @param rawQuantity    订单行数量，例如同一 SKU 买了 3 件
     * @param rawUnit        订单导入时的原始单位
     * @return 解析后的标准数量、单位、单价和复核标记
     */
    public QuantityUnitParseResult parse(String normalizedName,
                                         String targetUnit,
                                         String productName,
                                         String sku,
                                         Double price,
                                         Double rawQuantity,
                                         String rawUnit) {
        String countUnit = normalizeCountUnit(targetUnit);
        if (countUnit != null) {
            return parseCountProduct(countUnit, countTermPatterns(countUnit), productName, sku, price, rawQuantity);
        }
        return passThrough(targetUnit, price, rawQuantity, rawUnit);
    }

    /**
     * 先解析 SKU，再解析标题。SKU 通常比标题更接近真实购买规格，因此优先级更高。
     */
    private QuantityUnitParseResult parseCountProduct(String unit,
                                                      CountTermPatterns patterns,
                                                      String productName,
                                                      String sku,
                                                      Double price,
                                                      Double rawQuantity) {
        ParsedQuantity skuQuantity = parseOne(sku, patterns);
        if (skuQuantity.parsed()) {
            return parsedResult(skuQuantity.quantity(), unit, price, rawQuantity, "sku:" + skuQuantity.evidence());
        }
        ParsedQuantity productQuantity = parseOne(productName, patterns);
        if (productQuantity.parsed()) {
            return parsedResult(productQuantity.quantity(), unit, price, rawQuantity,
                    "productName:" + productQuantity.evidence());
        }
        return new QuantityUnitParseResult(null, unit, null, 0.2D, "no explicit " + unit + " count", true);
    }

    /**
     * rawQuantity 表示订单层面的购买件数，需要乘到 SKU 内部解析出的规格数量上。
     * 例如“32片”购买 3 件，应计为 96 片。
     */
    private QuantityUnitParseResult parsedResult(double baseQuantity,
                                                 String unit,
                                                 Double price,
                                                 Double rawQuantity,
                                                 String evidence) {
        double totalQuantity = baseQuantity * orderQuantity(rawQuantity);
        Double unitPrice = price != null && totalQuantity > 0D ? price / totalQuantity : null;
        return new QuantityUnitParseResult(totalQuantity, unit, unitPrice, 0.98D, evidence, false);
    }

    /**
     * 从单段文本中解析规格片段。
     *
     * <p>若文本包含乘法表达，优先只累计乘法表达，避免“48条”和“4条*12包”同时出现时重复计数。
     * 没有乘法表达时才累计普通“数字 + 单位”片段，例如 10颗+2颗 或 80片+27片。</p>
     */
    private ParsedQuantity parseOne(String text, CountTermPatterns patterns) {
        if (text == null || text.isBlank()) {
            return ParsedQuantity.notParsed();
        }
        String normalized = normalizeText(text);
        ParsedQuantity multiplication = parseWithPattern(normalized, patterns.multiplicationPattern(), true);
        if (multiplication.parsed()) {
            return multiplication;
        }
        return parseWithPattern(normalized, patterns.singlePattern(), false);
    }

    private ParsedQuantity parseWithPattern(String normalized, Pattern pattern, boolean requireMultiplier) {
        Matcher matcher = pattern.matcher(normalized);
        double total = 0D;
        StringBuilder evidence = new StringBuilder();
        while (matcher.find()) {
            double count = Double.parseDouble(matcher.group(1));
            double multiplier = requireMultiplier ? Double.parseDouble(matcher.group(2)) : 1D;
            total += count * multiplier;
            if (!evidence.isEmpty()) {
                evidence.append("+");
            }
            evidence.append(matcher.group());
        }
        if (total <= 0D) {
            return ParsedQuantity.notParsed();
        }
        return new ParsedQuantity(true, total, evidence.toString());
    }

    private CountTermPatterns countTermPatterns(String unit) {
        String quotedUnit = Pattern.quote(unit);
        Pattern multiplicationPattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*" + quotedUnit
                + "\\s*[xX*×]\\s*(\\d+(?:\\.\\d+)?)\\s*(?:包|盒|袋|组)");
        Pattern singlePattern = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*" + quotedUnit);
        return new CountTermPatterns(multiplicationPattern, singlePattern);
    }

    private String normalizeCountUnit(String targetUnit) {
        if (targetUnit == null || targetUnit.isBlank()) {
            return null;
        }
        String normalizedUnit = targetUnit.trim();
        return COUNT_UNITS.contains(normalizedUnit) ? normalizedUnit : null;
    }

    /**
     * 非新增标准品类走透传路径，避免破坏旧导入器已经解析好的 quantity/unit。
     */
    private QuantityUnitParseResult passThrough(String targetUnit, Double price, Double rawQuantity, String rawUnit) {
        if (rawQuantity != null && rawQuantity > 0D) {
            String resolvedUnit = rawUnit == null || rawUnit.isBlank() ? targetUnit : rawUnit;
            return new QuantityUnitParseResult(rawQuantity, resolvedUnit, price == null ? null : price / rawQuantity,
                    0.7D, "raw quantity", false);
        }
        return new QuantityUnitParseResult(rawQuantity, rawUnit, null, 0.3D, "raw quantity unavailable", false);
    }

    private double orderQuantity(Double rawQuantity) {
        return rawQuantity == null || rawQuantity <= 0D ? 1D : rawQuantity;
    }

    /**
     * 统一中文全角符号和乘号，降低电商 SKU 文本差异对解析的影响。
     */
    private String normalizeText(String text) {
        return text.trim()
                .replace('（', '(')
                .replace('）', ')')
                .replace('＊', '*')
                .replace('Ｘ', 'X')
                .replace('×', 'x')
                .replace('；', ';')
                .replace('｜', '|');
    }

    private record ParsedQuantity(boolean parsed, double quantity, String evidence) {
        static ParsedQuantity notParsed() {
            return new ParsedQuantity(false, 0D, "");
        }
    }

    private record CountTermPatterns(Pattern multiplicationPattern, Pattern singlePattern) {
    }
}
