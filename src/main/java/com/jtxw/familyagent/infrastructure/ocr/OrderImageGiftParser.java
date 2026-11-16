package com.jtxw.familyagent.infrastructure.ocr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/11/10 12:55:53
 * @Description: OCR 基础设施赠品文本解析器，按明确赠品标记分离商品区块，保留主订单字段并生成备注详情
 */
final class OrderImageGiftParser {
    /** 明确赠品商品标记；普通“赠品 6件”汇总行不作为一个商品处理。 */
    private static final Pattern GIFT_ITEM_PATTERN = Pattern.compile(
            "^(?:商品名称\\s*[：:]\\s*)?(?:【赠品】|\\[赠品\\]|赠品\\s*[：:])\\s*(.*)$");
    /** 订单业务字段会结束赠品区块，避免赠品在实付款之前展开时吞掉订单金额和日期。 */
    private static final Pattern ORDER_FIELD_PATTERN = Pattern.compile(
            "^(?:商品名称|实付款?|实际支付|支付金额|商品总价|"
                    + "订单金额|店铺优惠|平台优惠|订单信息|订单编号|订单号|支付方式|下单时间|创建时间|发货时间)");
    /** 赠品价格和价格后的展示件数，不作为赠品名称或规格的一部分。 */
    private static final Pattern TRAILING_PRICE_PATTERN = Pattern.compile(
            "\\s*[¥￥]\\s*\\d+(?:\\.\\d{1,2})?\\s*(?:[xX×]\\s*\\d+)?\\s*[>〉]?\\s*$");
    /** 独立的赠品购买件数，和 12g*4支 等单份包装规格分开处理。 */
    private static final Pattern GIFT_COUNT_PATTERN = Pattern.compile("^[xX×]\\s*(\\d+)\\s*$");
    /** 与价格同行的赠品购买件数。 */
    private static final Pattern GIFT_PRICE_COUNT_PATTERN = Pattern.compile(
            ".*[¥￥]\\s*\\d+(?:\\.\\d{1,2})?\\s*[xX×]\\s*(\\d+)\\s*$");
    /** 赠品的明确数量字段，兼容与值同行或换行。 */
    private static final Pattern GIFT_COUNT_LABEL_PATTERN = Pattern.compile(
            "^(?:购买数量|已购数量|数量)\\s*[：:]?\\s*(\\d+)?\\s*(?:件|份)?\\s*$");
    /** 汇总行或数量标签下一行的件数。 */
    private static final Pattern COUNT_VALUE_PATTERN = Pattern.compile("^(\\d+)\\s*(?:件|份)?\\s*$");
    /** 仅用于汇总信息的赠品件数，不推测各赠品明细的数量。 */
    private static final Pattern GIFT_SUMMARY_PATTERN = Pattern.compile("^赠品\\s*[：:]?\\s*(\\d+)\\s*件\\s*[>〉]?$");
    /** 价格、数字和关闭按钮等不是赠品名称，也不能追加到备注中。 */
    private static final Pattern GIFT_NOISE_PATTERN = Pattern.compile(
            "^(?:[¥￥]\\s*\\d+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?|[xX×]|赠品|关闭|[>〉])$");
    /** 赠品的规格字段属于当前赠品，清理标签后保留其值，不作为主商品字段返回。 */
    private static final Pattern GIFT_SPEC_LABEL_PATTERN = Pattern.compile(
            "^(?:商品规格|已选规格|购买规格|规格|SKU)\\s*[：:]\\s*", Pattern.CASE_INSENSITIVE);

    /**
     * 按赠品标记分离主商品文本与赠品明细，规则不依赖平台固定坐标或弹窗行数。
     *
     * @param lines OCR 原文行，不允许为空
     * @return 主订单文本行和赠品详情；无赠品时详情为空文本，不产生外部副作用
     */
    GiftSection extract(List<String> lines) {
        List<String> mainLines = new ArrayList<>();
        List<String> giftDescriptions = new ArrayList<>();
        List<String> currentGiftLines = null;
        for (String line : lines) {
            if (GIFT_ITEM_PATTERN.matcher(line).matches()) {
                appendGiftDescription(giftDescriptions, currentGiftLines);
                currentGiftLines = new ArrayList<>();
                currentGiftLines.add(line);
            } else if (currentGiftLines != null && !ORDER_FIELD_PATTERN.matcher(line).find()) {
                currentGiftLines.add(line);
            } else {
                appendGiftDescription(giftDescriptions, currentGiftLines);
                currentGiftLines = null;
                mainLines.add(line);
            }
        }
        appendGiftDescription(giftDescriptions, currentGiftLines);
        String details = giftDescriptions.isEmpty() ? findGiftSummary(mainLines) : String.join("；", giftDescriptions);
        return new GiftSection(List.copyOf(mainLines), details);
    }

    /**
     * 将单个赠品区块合并为名称、规格和份数，份数之后的包装图微小文字不再拼入名称。
     *
     * @param descriptions 已解析的赠品描述列表，写入当前赠品的描述
     * @param giftLines 当前赠品区块，允许为空；为空时不修改列表
     */
    private void appendGiftDescription(List<String> descriptions, List<String> giftLines) {
        if (giftLines == null || giftLines.isEmpty()) {
            return;
        }
        Matcher itemMatcher = GIFT_ITEM_PATTERN.matcher(giftLines.get(0));
        if (!itemMatcher.matches()) {
            return;
        }
        StringBuilder description = new StringBuilder(cleanFragment(itemMatcher.group(1)));
        boolean firstLineContainsCount = GIFT_PRICE_COUNT_PATTERN.matcher(giftLines.get(0)).matches();
        for (int index = 1; !firstLineContainsCount && index < giftLines.size(); index++) {
            String line = giftLines.get(index);
            if (GIFT_COUNT_PATTERN.matcher(line).matches() || GIFT_COUNT_LABEL_PATTERN.matcher(line).matches()) {
                break;
            }
            String fragment = cleanFragment(line);
            if (!fragment.isBlank() && !GIFT_NOISE_PATTERN.matcher(fragment).matches()) {
                description.append(fragment);
            }
            if (GIFT_PRICE_COUNT_PATTERN.matcher(line).matches()) {
                break;
            }
        }
        if (!description.isEmpty()) {
            String count = findGiftCount(giftLines);
            descriptions.add(description + (count == null ? "（数量待核对）" : " ×" + count));
        }
    }

    /**
     * 清理赠品名称尾部的展示价格，保留其中的规格乘号和包装数。
     *
     * @param text 待清理的赠品片段，不允许为空
     * @return 不带价格和多余空白的片段
     */
    private String cleanFragment(String text) {
        String fragment = GIFT_SPEC_LABEL_PATTERN.matcher(text.trim()).replaceFirst("");
        return TRAILING_PRICE_PATTERN.matcher(fragment).replaceFirst("").replaceAll("\\s+", " ").trim();
    }

    /**
     * 提取赠品明确购买份数，缺失或冲突时不使用包装规格中的乘数兜底。
     *
     * @param giftLines 单个赠品的原文行
     * @return 正整数份数文本；无法确定时返回 null
     */
    private String findGiftCount(List<String> giftLines) {
        String count = null;
        for (int index = 0; index < giftLines.size(); index++) {
            String line = giftLines.get(index);
            Matcher marker = GIFT_COUNT_PATTERN.matcher(line);
            Matcher priceCount = GIFT_PRICE_COUNT_PATTERN.matcher(line);
            Matcher label = GIFT_COUNT_LABEL_PATTERN.matcher(line);
            String candidate = null;
            if (marker.matches()) {
                candidate = marker.group(1);
            } else if (priceCount.matches()) {
                candidate = priceCount.group(1);
            } else if (label.matches()) {
                candidate = label.group(1);
                if (candidate == null && index + 1 < giftLines.size()) {
                    Matcher nextValue = COUNT_VALUE_PATTERN.matcher(giftLines.get(index + 1));
                    if (nextValue.matches()) {
                        candidate = nextValue.group(1);
                    }
                }
            }
            if (candidate != null) {
                BigDecimal parsedCount = new BigDecimal(candidate);
                if (parsedCount.signum() <= 0 || parsedCount.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                    return null;
                }
                candidate = parsedCount.toPlainString();
                if (count != null && !count.equals(candidate)) {
                    return null;
                }
                count = candidate;
            }
        }
        return count;
    }

    /**
     * 赠品面板未展开时只保留截图可见的汇总件数，不编造赠品名称和规格。
     *
     * @param lines 主订单原文行
     * @return 汇总说明；未出现赠品时返回空文本
     */
    private String findGiftSummary(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            Matcher inline = GIFT_SUMMARY_PATTERN.matcher(lines.get(index));
            if (inline.matches()) {
                return "共" + inline.group(1) + "件（详情未展开）";
            }
            if ("赠品".equals(lines.get(index)) && index + 1 < lines.size()) {
                Matcher count = COUNT_VALUE_PATTERN.matcher(lines.get(index + 1));
                if (count.matches()) {
                    return "共" + count.group(1) + "件（详情未展开）";
                }
            }
        }
        return "";
    }

    /**
     * @Author: jtxw
     * @Date: 2026/11/10 12:55:53
     * @Description: 赠品分离结果，在基础设施文本解析器之间传递主订单原文与赠品备注详情
     * @param mainLines 已移除赠品商品区块的主订单原文行，不允许为空
     * @param details 赠品详情或汇总说明，未识别到赠品时为空文本
     */
    record GiftSection(List<String> mainLines, String details) {
    }
}
