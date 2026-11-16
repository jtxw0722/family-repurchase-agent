package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/11/11 19:34:39
 * @Description: 截图查重领域策略，区分订单号证据和相同商品明细，并保留受长度限制的订单溯源信息
 */
@Component
public class OrderImageDuplicatePolicy {
    /** 只读取明确订单号标签，避免将物流单号、手机号等当成订单号。 */
    private static final Pattern ORDER_NUMBER_PATTERN = Pattern.compile(
            "(?:主订单号|订单编号|订单号)\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9\\- ]{7,63})");
    /** 保持截图候选原文片段最多 500 字符的既有约定。 */
    private static final int MAX_SOURCE_TEXT_LENGTH = 500;
    /** 最小订单号数字位数，用于排除偶然匹配到的英文标签。 */
    private static final int MIN_ORDER_NUMBER_DIGITS = 8;

    /**
     * 提取明确且唯一的订单号；忽略排版空格及连字符，多个不同订单号时不作确认依据。
     *
     * @param text OCR 原文或历史溯源文本，允许为空
     * @return 规范订单号，未识别或存在冲突时为空
     */
    public String orderNumber(String text) {
        Matcher matcher = ORDER_NUMBER_PATTERN.matcher(text == null ? "" : text);
        Set<String> orderNumbers = new HashSet<>();
        while (matcher.find()) {
            String number = matcher.group(1).replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
            if (number.chars().filter(Character::isDigit).count() >= MIN_ORDER_NUMBER_DIGITS) {
                orderNumbers.add(number);
            }
        }
        return orderNumbers.size() == 1 ? orderNumbers.iterator().next() : null;
    }

    /**
     * 将原文中位于截断范围外的订单号保留到溯源片段末尾，不新增数据库字段。
     *
     * @param sourceText 原始溯源片段，允许为空
     * @param orderNumber 已校验的规范订单号，允许为空
     * @return 长度不超过 500 的溯源片段
     */
    public String preserveOrderNumber(String sourceText, String orderNumber) {
        String source = sourceText == null ? "" : sourceText;
        source = source.substring(0, Math.min(source.length(), MAX_SOURCE_TEXT_LENGTH));
        if (orderNumber == null || orderNumber.equals(orderNumber(source))) {
            return source;
        }
        String suffix = "\n订单号：" + orderNumber;
        return source.substring(0, Math.min(source.length(), MAX_SOURCE_TEXT_LENGTH - suffix.length())) + suffix;
    }

    /**
     * 检查查询必需字段，不根据当天日期、默认平台或规格重量猜测订单明细。
     *
     * @param candidate 待查询截图候选
     * @return 字段足以执行保守查重时为 true
     */
    public boolean canCheck(ParsedPurchaseCandidate candidate) {
        return purchaseDay(candidate.purchaseDate()) != null && !normalizeText(candidate.owner()).isEmpty()
                && !normalizeText(candidate.platform()).isEmpty() && !normalizeText(candidate.productName()).isEmpty()
                && !normalizeText(candidate.sku()).isEmpty() && !normalizeText(candidate.unit()).isEmpty()
                && positive(candidate.price()) && positive(candidate.quantity())
                && positive(candidate.purchaseQuantity())
                && candidate.purchaseQuantity() == Math.rint(candidate.purchaseQuantity());
    }

    /**
     * 判断候选与历史记录的商品明细是否一致，名称和规格同时核对，防止同一订单的不同商品被混淆。
     *
     * @param candidate 截图候选，必需字段已校验
     * @param record 历史记录
     * @return 平台、商品、规格、总量、计量单位均一致时为 true；日期、归属人与金额由仓储查询约束
     */
    public boolean sameItem(ParsedPurchaseCandidate candidate, PurchaseRecord record) {
        BigDecimal totalQuantity = BigDecimal.valueOf(candidate.quantity())
                .multiply(BigDecimal.valueOf(candidate.purchaseQuantity()));
        return normalizePlatform(candidate.platform()).equals(normalizePlatform(record.platform()))
                && normalizeText(candidate.productName()).equals(normalizeText(record.productName()))
                && normalizeText(candidate.sku()).equals(normalizeText(record.sku()))
                && normalizeText(candidate.unit()).equals(normalizeText(record.unit()))
                && positive(record.quantity()) && totalQuantity.compareTo(BigDecimal.valueOf(record.quantity())) == 0;
    }

    /**
     * 取订单日期，兼容截图时间与表单保存的零点时间，不接受非法日期。
     *
     * @param purchaseDate 日期或时间文本，允许为空
     * @return yyyy-MM-dd 日期；无法识别时为空
     */
    public String purchaseDay(String purchaseDate) {
        if (purchaseDate == null || purchaseDate.trim().length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(purchaseDate.trim().substring(0, 10).replace('/', '-')).toString();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    /**
     * 统一历史天猫/淘宝以及常见中英文平台别名，仅用于查重，不修改历史数据。
     *
     * @param platform 平台文本
     * @return 规范平台标识
     */
    private String normalizePlatform(String platform) {
        return switch (normalizeText(platform)) {
            case "tmall", "taobao", "天猫", "淘宝" -> "taobao";
            case "pdd", "拼多多" -> "pdd";
            case "jd", "京东" -> "jd";
            default -> normalizeText(platform);
        };
    }

    /**
     * 忽略 OCR 排版空格及英文大小写，保留商品、规格中的其余内容以避免扩大匹配。
     *
     * @param text 待比较文本，允许为空
     * @return 去除空白的小写文本，空值返回空文本
     */
    private String normalizeText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 检查金额和数量为有限正数，排除未知值及无效浮点值。
     *
     * @param value 金额或数量，允许为空
     * @return 有限且大于零时为 true
     */
    private boolean positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }
}
