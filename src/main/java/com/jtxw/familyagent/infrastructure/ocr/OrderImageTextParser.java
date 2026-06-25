package com.jtxw.familyagent.infrastructure.ocr;

import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 19:26:00
 * @Description: 订单截图 OCR 文本规则解析器，使用可解释启发式规则生成待确认购买候选样本
 */
@Component
public class OrderImageTextParser {
    /**
     * 候选样本原文片段最大字符数。
     */
    private static final int MAX_SOURCE_TEXT_LENGTH = 500;
    /**
     * 候选样本固定说明。
     */
    private static final String CANDIDATE_NOTE = "OCR 识别候选，需用户确认后再入库";
    /**
     * 多商品截图不能可靠分摊订单总金额的警告。
     */
    private static final String MULTI_PRODUCT_PRICE_WARNING = "多商品截图无法可靠分摊实付金额";
    /**
     * SKU 包装数量与商品标题规格冲突时的人工确认警告。
     */
    private static final String PACKAGE_COUNT_CONFLICT_WARNING = "SKU 包装数量与商品标题规格数量不一致，需用户确认";
    /**
     * 明确乘法规格的候选优先级。
     */
    private static final int MULTIPLICATION_PRIORITY = 300;
    /**
     * 带赠送包装的明确组合装规格候选优先级。
     */
    private static final int BONUS_PACKAGE_PRIORITY = 400;
    /**
     * 同单位加法规格的候选优先级。
     */
    private static final int ADDITION_PRIORITY = 200;
    /**
     * 直接规格的候选优先级。
     */
    private static final int DIRECT_PRIORITY = 100;
    /**
     * 带业务标签的高优先级金额。
     */
    private static final Pattern LABELED_PRICE_PATTERN = Pattern.compile(
            "(?:实付款?|实际支付|支付金额|合计|订单金额|总价)\\s*[：:]?\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)");
    /**
     * 明确实付金额，优先级高于优惠、补贴价、商品划线价等页面金额。
     */
    private static final Pattern PAID_PRICE_PATTERN = Pattern.compile(
            "实付款?\\s*[：:]?\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)");
    /**
     * 没有业务标签的低优先级金额。
     */
    private static final Pattern STANDALONE_PRICE_PATTERN = Pattern.compile(
            "(?:[¥￥]\\s*(\\d+(?:\\.\\d{1,2})?)|(\\d+(?:\\.\\d{1,2})?)\\s*元)");
    /**
     * 明确商品名称字段。
     */
    private static final Pattern PRODUCT_NAME_PATTERN = Pattern.compile("^商品名称\\s*[：:;；]\\s*(.+)$");
    /**
     * 商品规格字段，支持标签和值位于同一行。
     */
    private static final Pattern SKU_PATTERN = Pattern.compile(
            "^(?:已购规格|商品规格|已选规格|购买规格|规格|颜色分类|套餐|型号|SKU)\\s*[：:;；]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);
    /**
     * 可从下一条有效文本读取值的规格标签。
     */
    private static final Pattern SKU_LABEL_PATTERN = Pattern.compile(
            "^(?:已购规格|商品规格|已选规格|购买规格|规格|颜色分类|套餐|型号|SKU)\\s*[：:;；]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    /**
     * SKU 候选行尾误带的人民币价格，兼容负号位于币种符号前后。
     */
    private static final Pattern TRAILING_SKU_PRICE_PATTERN = Pattern.compile(
            "\\s*-?\\s*[￥¥]\\s*-?\\s*\\d+(?:\\.\\d{1,2})?\\s*$");
    /**
     * SKU 候选行尾的单件数量按钮。
     */
    private static final Pattern TRAILING_SKU_QUANTITY_PATTERN = Pattern.compile(
            "\\s*[xX×]\\s*1\\s*$");
    /**
     * SKU 候选行尾的页面跳转符号。
     */
    private static final Pattern TRAILING_SKU_UI_PATTERN = Pattern.compile("\\s*[>〉]\\s*$");
    /**
     * 带明确订单语义标签的日期或时间，优先级高于物流和签收区域中的普通日期。
     */
    private static final Pattern LABELED_DATE_PATTERN = Pattern.compile(
            "(?:下单时间|支付时间|订单时间|创建时间)\\s*[：:]?\\s*"
                    + "(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?)");
    /**
     * 任意日期或时间，仅在缺少明确订单日期标签时作为兜底。
     */
    private static final Pattern ANY_DATE_PATTERN = Pattern.compile(
            "(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?)");
    /**
     * 明确商品数量单位，不包含只表示外包装的包、袋、盒等单位。
     */
    private static final String TARGET_UNIT_PATTERN = "(kg|g|ml|L|片|条|抽|颗)";
    /**
     * 仅用于乘数语义的包装单位。
     */
    private static final String PACKAGE_UNIT_PATTERN = "(包|袋|盒|瓶|罐|支|条|卷)";
    /**
     * 带赠送包装的组合装表达式，例如 268ml*7瓶+1瓶摩卡。
     */
    private static final Pattern BONUS_PACKAGE_QUANTITY_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*" + TARGET_UNIT_PATTERN
                    + "\\s*(?:装\\s*)?[xX*×]\\s*(\\d+)\\s*(包|袋|盒|瓶|罐|支)"
                    + "\\s*\\+\\s*(\\d+)\\s*\\4",
            Pattern.CASE_INSENSITIVE);
    /**
     * 规格在前、包装乘数在后的表达式，例如 60g*10袋。
     */
    private static final Pattern UNIT_BEFORE_MULTIPLIER_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*" + TARGET_UNIT_PATTERN
                    + "\\s*(?:装\\s*)?[xX*×]\\s*(\\d+)(?!\\.\\d)\\s*" + PACKAGE_UNIT_PATTERN + "?",
            Pattern.CASE_INSENSITIVE);
    /**
     * 包装数在前、每份规格在后的表达式，例如 3包*100抽/包。
     */
    private static final Pattern PACKAGE_BEFORE_UNIT_PATTERN = Pattern.compile(
            "(\\d+)(?!\\.\\d)\\s*" + PACKAGE_UNIT_PATTERN
                    + "\\s*[xX*×]\\s*(\\d+(?:\\.\\d+)?)\\s*" + TARGET_UNIT_PATTERN
                    + "(?:\\s*/\\s*" + PACKAGE_UNIT_PATTERN + ")?",
            Pattern.CASE_INSENSITIVE);
    /**
     * 小数包装乘数表达式，不允许降级为其中的直接规格。
     */
    private static final Pattern DECIMAL_PACKAGE_MULTIPLIER_PATTERN = Pattern.compile(
            "(?:\\d+(?:\\.\\d+)?\\s*" + TARGET_UNIT_PATTERN
                    + "\\s*(?:装\\s*)?[xX*×]\\s*\\d+\\.\\d+\\s*" + PACKAGE_UNIT_PATTERN + "?"
                    + "|\\d+\\.\\d+\\s*" + PACKAGE_UNIT_PATTERN
                    + "\\s*[xX*×]\\s*\\d+(?:\\.\\d+)?\\s*" + TARGET_UNIT_PATTERN + ")",
            Pattern.CASE_INSENSITIVE);
    /**
     * 同单位加法表达式，例如 380g+400g。
     */
    private static final Pattern ADDITION_QUANTITY_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*" + TARGET_UNIT_PATTERN
                    + "\\s*\\+\\s*(\\d+(?:\\.\\d+)?)\\s*" + TARGET_UNIT_PATTERN,
            Pattern.CASE_INSENSITIVE);
    /**
     * 直接规格或装数表达式，例如 720ml、10片装。
     */
    private static final Pattern DIRECT_QUANTITY_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*" + TARGET_UNIT_PATTERN + "(?:\\s*装)?",
            Pattern.CASE_INSENSITIVE);
    /**
     * SKU 中没有目标单位的独立包装数量，用于判断标题规格是否可能是通用展示规格。
     */
    private static final Pattern PACKAGE_COUNT_ONLY_PATTERN = Pattern.compile(
            "(?:^|[;；,，\\s])(?:混合口味[;；,，]?)?\\s*(\\d+)\\s*(包|袋|盒|瓶|罐|支|卷|件)(?:$|[^xX*×\\d])");
    /**
     * 系统字段及交互文案，不应用作商品名称或规格值。
     */
    private static final Pattern SYSTEM_LINE_PATTERN = Pattern.compile(
            "交易成功|您的快件已取走|快件已取走|为了补偿|门槛券|已签收|收货|地址|手机号|\\[姓名已隐藏]|"
                    + "\\[手机号已隐藏]|\\[地址已隐藏]|\\[收货信息已隐藏]|旗舰店|回头客好店|降价补差|"
                    + "分享商品|联系商家|申请退款|补贴后共优惠|实付|已购规格|商品规格|已选规格|购买规格|规格|"
                    + "该规格补贴价|已购数量|合计补贴价|平台优惠|订单号|商品快照|支付方式|物流公司|快递单号|"
                    + "下单时间|拼单时间|发货时间|成交时间|支付时间|订单时间|创建时间|优惠券|运费|合计|订单金额|"
                    + "总价|颜色分类|套餐|型号|SKU|更多|查看物流|追加评价|再次拼单|复制|仓库已发货|自动确认|"
                    + "直播中|好评率|退款|大促价保|破损包退|上门换新|加入购物车|申请售后|商品总价|店铺优惠|"
                    + "平台及达人优惠|红包已抵|订单信息|收起|交易快照|支付宝交易号|催促配送|确认收货|立即评价|"
                    + "客服|退货包运费|7天无理由退货");
    /**
     * 单独出现的数量、金额和移动端状态文本，不应用作商品名称。
     */
    private static final Pattern NON_PRODUCT_VALUE_PATTERN = Pattern.compile(
            "^(?:[xX×*]\\s*\\d+|[¥￥]\\s*[-+]?\\d+(?:\\.\\d+)?|[-+]?\\d+(?:\\.\\d+)?\\s*元|"
                    + "\\d{1,2}:\\d{2}[a-zA-Z]?|\\d+[Gg]?)$");
    /**
     * 商品名称候选中的明确规格乘法，通常表示 SKU 而不是商品主体标题。
     */
    private static final Pattern SPECIFICATION_MULTIPLIER_PATTERN = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*" + TARGET_UNIT_PATTERN + "\\s*(?:装\\s*)?[xX*×]\\s*\\d+",
            Pattern.CASE_INSENSITIVE);
    /**
     * 商品名称行尾附着的商品价和购买数量，例如 ¥25.73 ×1。
     */
    private static final Pattern PRODUCT_NAME_TRAILING_PRICE_QUANTITY_PATTERN = Pattern.compile(
            "\\s*[¥￥]\\s*\\d+(?:\\.\\d{1,2})?\\s*(?:[xX×]\\s*\\d+)?\\s*$");
    /**
     * 商品名称行尾附着的单件购买数量按钮。
     */
    private static final Pattern PRODUCT_NAME_TRAILING_QUANTITY_PATTERN = Pattern.compile("\\s*[xX×]\\s*1\\s*[>√]*\\s*$");
    /**
     * 拼多多商品标题常见营销前缀，清理时必须保留后面的真实品牌和商品名。
     */
    private static final Pattern PDD_PRODUCT_MARKETING_PREFIX_PATTERN = Pattern.compile(
            "^(?:百亿补贴[·\\s]*[^\\s]*\\s*品牌\\s*|百亿补贴\\s*品牌\\s*|百亿补贴\\s*|品牌好货\\s*|品牌\\s*)+");
    /**
     * 拼多多订单详情页平台强特征。
     */
    private static final Pattern PDD_PLATFORM_FEATURE_PATTERN = Pattern.compile(
            "拼多多|百亿补贴|拼单|再次拼单|补贴后共优惠|退货包运费保障中");
    /**
     * 商品汇总区起始字段，SKU 无标签回退扫描到此处后停止。
     */
    private static final Pattern PRODUCT_SUMMARY_LINE_PATTERN = Pattern.compile(
            "商品总价|店铺优惠|平台(?:及达人)?优惠|红包已抵|实付款?|订单信息");
    /**
     * 店铺优惠和金额汇总行，不能作为店铺名称。
     */
    private static final Pattern SHOP_NOISE_LINE_PATTERN = Pattern.compile(
            "店铺优惠|平台优惠|平台及达人优惠|商品总价|实付款?");
    /**
     * 常见可信店铺名称后缀。
     */
    private static final Pattern SHOP_NAME_PATTERN = Pattern.compile(
            "^(?:天猫超市|京东自营|.+(?:官方旗舰店|旗舰店|专营店|专卖店|自营))$");
    /**
     * 商品图片中的图标、营销短语和拍摄提示，不应拼入商品标题。
     */
    private static final Pattern PRODUCT_TITLE_FRAGMENT_NOISE_PATTERN = Pattern.compile(
            "(?:#.*?\\d+秒|新配方?|大促价保|破损包退|上门换新|加入购物车|申请售后)"
                    + "|^(?:NEW!?|EVER|CLEAN|OL|®|\\d+%)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 将 OCR 原文解析为一个或多个候选样本。
     *
     * <p>请求中的平台和购买日期优先于 OCR 识别值。多个明确“商品名称”字段会生成多个候选，
     * 但不会推测订单总金额的商品分摊。</p>
     *
     * @param rawText               OCR 原始文本，允许为空
     * @param owner                 请求提供的订单归属人，允许为空
     * @param requestedPlatform     请求提供的平台，允许为空
     * @param requestedPurchaseDate 请求提供的购买日期，允许为空
     * @return 待确认候选样本列表；空文本仍返回一个带警告的候选
     */
    public List<ParsedPurchaseCandidate> parse(String rawText,
                                               String owner,
                                               String requestedPlatform,
                                               String requestedPurchaseDate) {
        String normalizedText = rawText == null ? "" : rawText.trim();
        List<String> lines = normalizedText.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
        List<String> explicitProductNames = extractExplicitProductNames(lines);
        boolean multipleProducts = explicitProductNames.size() > 1;
        List<String> productNames = resolveProductNames(lines, explicitProductNames);

        String sku = findSku(lines, productNames.get(0));
        Double price = multipleProducts ? null : findPrice(normalizedText);
        Quantity quantity = findQuantity(sku, productNames, normalizedText);
        String purchaseDate = hasText(requestedPurchaseDate) ? requestedPurchaseDate.trim() : findDate(normalizedText);
        String platform = hasText(requestedPlatform) ? requestedPlatform.trim() : detectPlatform(normalizedText);
        String shopName = findShopName(lines);
        String sourceText = normalizedText.substring(0, Math.min(normalizedText.length(), MAX_SOURCE_TEXT_LENGTH));

        List<ParsedPurchaseCandidate> candidates = new ArrayList<>();
        for (String productName : productNames) {
            List<String> warnings = buildWarnings(productName, price, quantity, multipleProducts);
            candidates.add(new ParsedPurchaseCandidate(
                    productName, sku, price, quantity.quantity(), quantity.unit(), platform, owner,
                    purchaseDate, shopName, CANDIDATE_NOTE, sourceText,
                    calculateConfidence(productName, price, quantity, purchaseDate, shopName), List.copyOf(warnings)));
        }
        return List.copyOf(candidates);
    }

    /**
     * 从 OCR 文本行中提取带有"商品名称"标签的显式商品名。
     *
     * @param lines OCR 原文按行拆分的文本列表
     * @return 清洗后的显式商品名称列表
     */
    private List<String> extractExplicitProductNames(List<String> lines) {
        List<String> names = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = PRODUCT_NAME_PATTERN.matcher(line);
            if (matcher.matches() && hasText(matcher.group(1))) {
                names.add(cleanProductNameCandidate(matcher.group(1)));
            }
        }
        return names;
    }

    /**
     * 解析商品名称候选。
     *
     * <p>即使无法识别商品名称，也返回一个包含 null 的列表，以便后续统一生成带警告的候选样本，
     * 而不是因 List.of(null) 抛出空指针异常。</p>
     *
     * @param lines                OCR 文本行
     * @param explicitProductNames 明确“商品名称：xxx”解析出的商品名列表
     * @return 商品名称候选列表，无法识别时包含一个 null 元素
     */
    private List<String> resolveProductNames(List<String> lines, List<String> explicitProductNames) {
        if (!explicitProductNames.isEmpty()) {
            return explicitProductNames;
        }
        List<String> productNames = new ArrayList<>();
        productNames.add(findHeuristicProductName(lines));
        return productNames;
    }

    /**
     * 在店铺之后、实付和规格详情之前选择商品标题，并排除横幅、物流和按钮等系统噪声。
     */
    private String findHeuristicProductName(List<String> lines) {
        int shopIndex = findFirstLineIndex(lines, this::isShopLine);
        int paidIndex = findFirstLineIndex(lines, line -> line.contains("实付"));
        int skuDetailIndex = findFirstLineIndex(lines, line -> SKU_LABEL_PATTERN.matcher(line).matches());

        ProductNameCandidate bestCandidate = null;
        for (int index = 0; index < lines.size(); index++) {
            String rawLine = lines.get(index);
            String productNameCandidate = cleanProductNameCandidate(rawLine);
            if (!isProductNameCandidate(productNameCandidate)) {
                continue;
            }
            int score = scoreProductNameCandidate(
                    rawLine, productNameCandidate, index, shopIndex, paidIndex, skuDetailIndex);
            if (bestCandidate == null || score > bestCandidate.score()) {
                bestCandidate = new ProductNameCandidate(productNameCandidate, score, index);
            }
        }
        return bestCandidate == null ? null : buildProductNameFromProductBlock(lines, bestCandidate);
    }

    /**
     * 从主标题行向后扫描商品区块，跳过图片噪声并拼接被 OCR 拆开的有效标题片段。
     *
     * @param lines         OCR 文本行
     * @param baseCandidate 已选出的主标题及其位置
     * @return 拼接并清洗后的商品名称
     */
    private String buildProductNameFromProductBlock(List<String> lines, ProductNameCandidate baseCandidate) {
        StringBuilder productNameBuilder = new StringBuilder(baseCandidate.value());
        for (int index = baseCandidate.lineIndex() + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (PRODUCT_SUMMARY_LINE_PATTERN.matcher(line).find()
                    || DIRECT_QUANTITY_PATTERN.matcher(line).find()) {
                break;
            }
            String titleFragment = cleanProductNameCandidate(line);
            if (isProductTitleContinuation(titleFragment)) {
                productNameBuilder.append(titleFragment);
            }
        }
        return cleanProductNameCandidate(productNameBuilder.toString());
    }

    /**
     * 判断文本是否是可拼接的商品标题续行，排除规格、价格、按钮、图标和营销短语。
     *
     * @param line 已完成尾部价格清洗的 OCR 文本行
     * @return 包含有效中文商品描述且不属于噪声时返回 true
     */
    private boolean isProductTitleContinuation(String line) {
        return hasText(line)
                && containsChinese(line)
                && !SYSTEM_LINE_PATTERN.matcher(line).find()
                && !PRODUCT_TITLE_FRAGMENT_NOISE_PATTERN.matcher(line).find()
                && !NON_PRODUCT_VALUE_PATTERN.matcher(line).matches()
                && !DIRECT_QUANTITY_PATTERN.matcher(line).find()
                && !isShopLine(line);
    }

    /**
     * 查找首个满足业务条件的文本行位置。
     *
     * @param lines     OCR 文本行
     * @param predicate 文本行匹配条件
     * @return 首个匹配位置；不存在时返回 -1
     */
    private int findFirstLineIndex(List<String> lines, java.util.function.Predicate<String> predicate) {
        for (int index = 0; index < lines.size(); index++) {
            if (predicate.test(lines.get(index))) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 判断文本行是否具备商品名称候选的基本特征。
     */
    private boolean isProductNameCandidate(String line) {
        return hasText(line)
                && containsChinese(line)
                && !SYSTEM_LINE_PATTERN.matcher(line).find()
                && !NON_PRODUCT_VALUE_PATTERN.matcher(line).matches()
                && detectPlatform(line) == null
                && !isShopLine(line);
    }

    /**
     * 清除 OCR 商品标题尾部附着的价格、数量按钮和装饰符号，同时保留标题中的中英文及规格数字。
     *
     * @param line OCR 原始商品标题候选
     * @return 清洗后的商品标题；原始文本为空时返回空文本
     */
    private String cleanProductNameCandidate(String line) {
        if (!hasText(line)) {
            return line;
        }
        String cleanedLine = PDD_PRODUCT_MARKETING_PREFIX_PATTERN.matcher(line.trim()).replaceFirst("");
        cleanedLine = PRODUCT_NAME_TRAILING_PRICE_QUANTITY_PATTERN.matcher(cleanedLine).replaceFirst("")
                .replaceFirst("\\s*√?\\s*[¥￥]\\s*\\d+(?:\\.\\d{1,2})?.*$", "");
        cleanedLine = PRODUCT_NAME_TRAILING_QUANTITY_PATTERN.matcher(cleanedLine).replaceFirst("")
                .replaceFirst("[>√\\s]+$", "");
        return cleanedLine.trim();
    }

    /**
     * 根据商品区块位置和规格特征计算标题候选分数。
     */
    private int scoreProductNameCandidate(String rawLine,
                                          String line,
                                          int lineIndex,
                                          int shopIndex,
                                          int paidIndex,
                                          int skuDetailIndex) {
        int score = line.length();
        if (shopIndex >= 0 && lineIndex > shopIndex) {
            score += 30;
        }
        if (paidIndex >= 0 && lineIndex < paidIndex) {
            score += 20;
        }
        if (skuDetailIndex >= 0 && lineIndex < skuDetailIndex) {
            score += 10;
        }
        boolean hasTrailingPriceAndQuantity = rawLine.matches(".*[¥￥]\\s*\\d+(?:\\.\\d{1,2})?\\s*[xX×]\\s*\\d+.*");
        boolean hasPrice = rawLine.matches(".*[¥￥]\\s*\\d+(?:\\.\\d{1,2})?.*");
        if (hasTrailingPriceAndQuantity) {
            score += 40;
        } else if (hasPrice) {
            score += 25;
        }
        if (rawLine.contains("百亿补贴") || rawLine.contains("品牌好货") || rawLine.contains("品牌")) {
            score += 20;
        }
        // 完整乘法规格更可能是 SKU；保留含容量但不完整的 OCR 商品标题。
        if (SPECIFICATION_MULTIPLIER_PATTERN.matcher(line).find()) {
            score -= hasPrice ? 5 : 30;
        }
        return score;
    }

    /**
     * 按标签优先、商品区块兜底的顺序从 OCR 文本行中提取规格信息。
     *
     * @param lines       OCR 原文按行拆分的文本列表
     * @param productName 已识别的商品名称，用于兜底扫描范围限定
     * @return 规格文本，未找到时返回空
     */
    private String findSku(List<String> lines, String productName) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher matcher = SKU_PATTERN.matcher(line);
            if (matcher.matches()) {
                return cleanSkuCandidate(matcher.group(1));
            }
            if (SKU_LABEL_PATTERN.matcher(line).matches()) {
                String nextValue = findNextValidSkuValue(lines, index + 1);
                if (nextValue != null) {
                    return nextValue;
                }
            }
        }
        return findSkuFromProductBlock(lines, productName);
    }

    /**
     * 在缺少规格标签时，从商品标题之后到订单汇总区之前寻找包含明确目标单位的副标题。
     *
     * @param lines       OCR 文本行
     * @param productName 已选出的商品名称，允许为空
     * @return 商品区块中的规格副标题；没有可信候选时返回 null
     */
    private String findSkuFromProductBlock(List<String> lines, String productName) {
        if (!hasText(productName)) {
            return null;
        }
        int productNameIndex = findFirstLineIndex(
                lines, line -> productName.startsWith(cleanProductNameCandidate(line))
                        && hasText(cleanProductNameCandidate(line)));
        if (productNameIndex < 0) {
            return null;
        }
        for (int index = productNameIndex + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (PRODUCT_SUMMARY_LINE_PATTERN.matcher(line).find()) {
                return null;
            }
            if (!SYSTEM_LINE_PATTERN.matcher(line).find()
                    && !NON_PRODUCT_VALUE_PATTERN.matcher(line).matches()
                    && !isShopLine(line)
                    && DIRECT_QUANTITY_PATTERN.matcher(line).find()) {
                return cleanSkuCandidate(line);
            }
        }
        return null;
    }

    /**
     * 从规格标签之后读取首条有效规格文本，遇到其他业务字段时停止，避免跨区块误取值。
     */
    private String findNextValidSkuValue(List<String> lines, int startIndex) {
        for (int index = startIndex; index < lines.size(); index++) {
            String line = lines.get(index);
            if (SYSTEM_LINE_PATTERN.matcher(line).find() || NON_PRODUCT_VALUE_PATTERN.matcher(line).matches()) {
                return null;
            }
            String cleanedLine = cleanSkuCandidate(line);
            if (isValidSkuValue(cleanedLine)) {
                return cleanedLine;
            }
        }
        return null;
    }

    /**
     * 清理 SKU 候选行尾的价格、单件数量和页面跳转符号，保留中间的容量及包装规格数字。
     *
     * @param value OCR 识别得到的 SKU 候选文本，允许为空
     * @return 清理后的 SKU 文本；输入为空时原样返回
     */
    private String cleanSkuCandidate(String value) {
        if (!hasText(value)) {
            return value;
        }
        String cleanedValue = TRAILING_SKU_UI_PATTERN.matcher(value.trim()).replaceFirst("");
        cleanedValue = TRAILING_SKU_QUANTITY_PATTERN.matcher(cleanedValue).replaceFirst("");
        cleanedValue = TRAILING_SKU_UI_PATTERN.matcher(cleanedValue).replaceFirst("");
        cleanedValue = TRAILING_SKU_PRICE_PATTERN.matcher(cleanedValue).replaceFirst("");
        cleanedValue = TRAILING_SKU_UI_PATTERN.matcher(cleanedValue).replaceFirst("");
        return cleanedValue.replaceAll("\\s+", " ").trim();
    }

    /**
     * 判断标签后的文本是否包含可解释的规格信息。
     */
    private boolean isValidSkuValue(String line) {
        return DIRECT_QUANTITY_PATTERN.matcher(line).find()
                || PACKAGE_COUNT_ONLY_PATTERN.matcher(line).find()
                || line.matches(".*(?:口味|味|颜色|色|型号|款|码|容量|拿铁|摩卡).*?");
    }

    /**
     * 从 OCR 原文中提取实付金额，优先匹配带标签的价格，兜底匹配独立金额。
     *
     * @param rawText OCR 识别的完整原始文本
     * @return 实付金额，未找到时返回空
     */
    private Double findPrice(String rawText) {
        Matcher paidMatcher = PAID_PRICE_PATTERN.matcher(rawText);
        if (paidMatcher.find()) {
            return Double.valueOf(paidMatcher.group(1));
        }
        Matcher labeledMatcher = LABELED_PRICE_PATTERN.matcher(rawText);
        if (labeledMatcher.find()) {
            return Double.valueOf(labeledMatcher.group(1));
        }
        Matcher standaloneMatcher = STANDALONE_PRICE_PATTERN.matcher(rawText);
        if (!standaloneMatcher.find()) {
            return null;
        }
        String amount = standaloneMatcher.group(1) == null ? standaloneMatcher.group(2) : standaloneMatcher.group(1);
        return Double.valueOf(amount);
    }

    /**
     * 按 SKU、商品名称、完整原文的顺序解析数量，并保守处理 SKU 包装数与标题规格冲突。
     *
     * @param sku          OCR 识别的规格文本，允许为空
     * @param productNames OCR 识别的商品名称列表
     * @param rawText      OCR 完整原文
     * @return 最高可信度的数量解析结果，冲突或无法识别时返回空结果
     */
    private Quantity findQuantity(String sku, List<String> productNames, String rawText) {
        QuantityCandidate skuCandidate = findBestQuantityCandidate(sku);
        if (skuCandidate != null) {
            return skuCandidate.toQuantity();
        }

        Integer skuPackageCount = findPackageCountOnly(sku);
        for (String productName : productNames) {
            QuantityCandidate productCandidate = findBestQuantityCandidate(productName);
            if (productCandidate == null) {
                continue;
            }
            if (skuPackageCount != null && productCandidate.packageCount() != null
                    && !skuPackageCount.equals(productCandidate.packageCount())) {
                return Quantity.empty(PACKAGE_COUNT_CONFLICT_WARNING);
            }
            return productCandidate.toQuantity();
        }

        // SKU 已提供独立包装数时，不再从包含通用标题规格的全文盲目推断。
        if (skuPackageCount != null) {
            return Quantity.empty();
        }
        QuantityCandidate rawTextCandidate = findBestQuantityCandidate(rawText);
        return rawTextCandidate == null ? Quantity.empty() : rawTextCandidate.toQuantity();
    }

    /**
     * 从单段文本提取数量候选，并按“乘法、加法、直接规格”和数量降序选择。
     *
     * @param text SKU、商品名称或 OCR 原文
     * @return 最可信的数量候选；没有明确目标单位时返回 null
     */
    private QuantityCandidate findBestQuantityCandidate(String text) {
        if (!hasText(text)) {
            return null;
        }
        if (containsMixedUnitAddition(text) || DECIMAL_PACKAGE_MULTIPLIER_PATTERN.matcher(text).find()) {
            return null;
        }
        List<QuantityCandidate> candidates = new ArrayList<>();
        collectBonusPackageCandidates(text, candidates);
        collectUnitBeforeMultiplierCandidates(text, candidates);
        collectPackageBeforeUnitCandidates(text, candidates);
        collectAdditionCandidates(text, candidates);
        collectDirectCandidates(text, candidates);
        return candidates.stream()
                .max((left, right) -> {
                    int priorityComparison = Integer.compare(left.priority(), right.priority());
                    return priorityComparison != 0
                            ? priorityComparison : Double.compare(left.quantity(), right.quantity());
                })
                .orElse(null);
    }

    /**
     * 收集“单份规格乘基础包装数再加赠送包装数”的组合装候选。
     */
    private void collectBonusPackageCandidates(String text, List<QuantityCandidate> candidates) {
        Matcher matcher = BONUS_PACKAGE_QUANTITY_PATTERN.matcher(text);
        while (matcher.find()) {
            double perPackageQuantity = Double.parseDouble(matcher.group(1));
            int packageCount = integerPackageCount(matcher.group(3)) + integerPackageCount(matcher.group(5));
            candidates.add(new QuantityCandidate(perPackageQuantity * packageCount, normalizeUnit(matcher.group(2)),
                    BONUS_PACKAGE_PRIORITY, "bonus_package", matcher.group(), packageCount));
        }
    }

    /**
     * 判断文本是否包含不同单位的加法，避免把 1kg+500g 误识别为其中一个直接规格。
     */
    private boolean containsMixedUnitAddition(String text) {
        Matcher matcher = ADDITION_QUANTITY_PATTERN.matcher(text);
        while (matcher.find()) {
            if (!normalizeUnit(matcher.group(2)).equals(normalizeUnit(matcher.group(4)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集"单份规格在前、包装乘数在后"的数量候选，例如 2.5kg*8。
     *
     * @param text       待匹配的文本段
     * @param candidates 候选列表，匹配成功时追加
     */
    private void collectUnitBeforeMultiplierCandidates(String text, List<QuantityCandidate> candidates) {
        Matcher matcher = UNIT_BEFORE_MULTIPLIER_PATTERN.matcher(text);
        while (matcher.find()) {
            double perPackageQuantity = Double.parseDouble(matcher.group(1));
            int packageCount = integerPackageCount(matcher.group(3));
            candidates.add(new QuantityCandidate(perPackageQuantity * packageCount, normalizeUnit(matcher.group(2)),
                    MULTIPLICATION_PRIORITY, "unit_before_multiplier", matcher.group(), packageCount));
        }
    }

    /**
     * 收集"包装数在前、单份规格在后"的数量候选，例如 3包*100抽/包。
     *
     * @param text       待匹配的文本段
     * @param candidates 候选列表，匹配成功时追加
     */
    private void collectPackageBeforeUnitCandidates(String text, List<QuantityCandidate> candidates) {
        Matcher matcher = PACKAGE_BEFORE_UNIT_PATTERN.matcher(text);
        while (matcher.find()) {
            int packageCount = integerPackageCount(matcher.group(1));
            double perPackageQuantity = Double.parseDouble(matcher.group(3));
            candidates.add(new QuantityCandidate(perPackageQuantity * packageCount, normalizeUnit(matcher.group(4)),
                    MULTIPLICATION_PRIORITY, "package_before_unit", matcher.group(), packageCount));
        }
    }

    /**
     * 收集同单位加法数量候选，例如 1kg+500g 转换为同单位后求和。
     *
     * @param text       待匹配的文本段
     * @param candidates 候选列表，匹配成功时追加
     */
    private void collectAdditionCandidates(String text, List<QuantityCandidate> candidates) {
        Matcher matcher = ADDITION_QUANTITY_PATTERN.matcher(text);
        while (matcher.find()) {
            String leftUnit = normalizeUnit(matcher.group(2));
            String rightUnit = normalizeUnit(matcher.group(4));
            if (leftUnit.equals(rightUnit)) {
                double total = Double.parseDouble(matcher.group(1)) + Double.parseDouble(matcher.group(3));
                candidates.add(new QuantityCandidate(total, leftUnit, ADDITION_PRIORITY,
                        "same_unit_addition", matcher.group(), null));
            }
        }
    }

    /**
     * 收集直接规格数量候选，例如 5kg、300ml、100抽。
     *
     * @param text       待匹配的文本段
     * @param candidates 候选列表，匹配成功时追加
     */
    private void collectDirectCandidates(String text, List<QuantityCandidate> candidates) {
        Matcher matcher = DIRECT_QUANTITY_PATTERN.matcher(text);
        while (matcher.find()) {
            candidates.add(new QuantityCandidate(Double.parseDouble(matcher.group(1)), normalizeUnit(matcher.group(2)),
                    DIRECT_PRIORITY, "direct", matcher.group(), null));
        }
    }

    /**
     * 从文本中提取仅有包装数但无明确单份规格的数量，例如"3包""2盒"。
     *
     * @param text 待匹配的文本段
     * @return 包装数，未找到或已有更精确数量候选时返回空
     */
    private Integer findPackageCountOnly(String text) {
        if (!hasText(text) || findBestQuantityCandidate(text) != null) {
            return null;
        }
        Matcher matcher = PACKAGE_COUNT_ONLY_PATTERN.matcher(text);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /**
     * 将包装数字符串解析为整数，格式非法时由调用方处理异常。
     *
     * @param value 包装数字符串
     * @return 整数包装数
     */
    private int integerPackageCount(String value) {
        return Integer.parseInt(value);
    }

    /**
     * 将 OCR 识别的单位文本归一化为标准统计单位，统一大小写和等价写法。
     *
     * @param unit OCR 识别的原始单位文本
     * @return 归一化后的标准单位，无法识别时返回原始值
     */
    private String normalizeUnit(String unit) {
        String lowerCaseUnit = unit.toLowerCase(Locale.ROOT);
        return switch (lowerCaseUnit) {
            case "kg" -> "kg";
            case "g" -> "g";
            case "l" -> "L";
            case "ml" -> "ml";
            default -> unit;
        };
    }

    /**
     * 从 OCR 原文中提取下单日期，支持斜杠和横杠分隔格式。
     *
     * @param rawText OCR 识别的完整原始文本
     * @return 标准化后的日期字符串，未找到时返回空
     */
    private String findDate(String rawText) {
        Matcher labeledMatcher = LABELED_DATE_PATTERN.matcher(rawText);
        if (labeledMatcher.find()) {
            return normalizeDate(labeledMatcher.group(1));
        }
        Matcher anyMatcher = ANY_DATE_PATTERN.matcher(rawText);
        if (!anyMatcher.find()) {
            return null;
        }
        return normalizeDate(anyMatcher.group(1));
    }

    /**
     * 将 OCR 日期统一归一化为 yyyy-MM-dd，丢弃时间部分以匹配购买日期字段语义。
     *
     * @param dateText OCR 中识别出的日期或日期时间
     * @return 标准日期字符串
     */
    private String normalizeDate(String dateText) {
        String normalizedDate = dateText.replace('/', '-');
        return normalizedDate.length() > 10 ? normalizedDate.substring(0, 10) : normalizedDate;
    }

    /**
     * 从 OCR 文本行中识别店铺名称，跳过优惠和金额汇总等噪声字段。
     *
     * @param lines OCR 原文按行拆分的文本列表
     * @return 店铺名称，未找到时返回空
     */
    private String findShopName(List<String> lines) {
        for (String line : lines) {
            if (isShopLine(line)) {
                int separatorIndex = Math.max(line.indexOf('：'), line.indexOf(':'));
                return separatorIndex >= 0 ? line.substring(separatorIndex + 1).trim() : line;
            }
        }
        return null;
    }

    /**
     * 判断文本行是否为店铺名称行，要求冒号后的名称匹配店铺名称特征且不是噪声字段。
     *
     * @param line OCR 文本行
     * @return 是店铺名称行时返回 true
     */
    private boolean isShopLine(String line) {
        if (isShopNoiseLine(line)) {
            return false;
        }
        int separatorIndex = Math.max(line.indexOf('：'), line.indexOf(':'));
        String shopNameCandidate = separatorIndex >= 0 ? line.substring(separatorIndex + 1).trim() : line.trim();
        return SHOP_NAME_PATTERN.matcher(shopNameCandidate).matches();
    }

    /**
     * 判断文本是否为优惠或金额汇总字段，避免将“店铺优惠”等订单字段误识别为店铺。
     */
    private boolean isShopNoiseLine(String line) {
        return SHOP_NOISE_LINE_PATTERN.matcher(line).find();
    }

    /**
     * 根据 OCR 原文中的平台关键词识别购买平台，无法识别时返回空。
     *
     * @param rawText OCR 识别的完整原始文本
     * @return 平台标识，例如 pdd、taobao、jd；无法识别时返回空
     */
    private String detectPlatform(String rawText) {
        if (rawText.contains("淘宝")) {
            return "taobao";
        }
        if (rawText.contains("天猫")) {
            return "tmall";
        }
        if (rawText.contains("京东")) {
            return "jd";
        }
        if (PDD_PLATFORM_FEATURE_PATTERN.matcher(rawText).find()) {
            return "pdd";
        }
        if (rawText.contains("抖音")) {
            return "douyin";
        }
        if (rawText.contains("美团")) {
            return "meituan";
        }
        if (rawText.contains("线下")) {
            return "offline";
        }
        return null;
    }

    /**
     * 根据关键字段缺失和多商品边界生成可供用户确认的解释性警告。
     */
    private List<String> buildWarnings(String productName, Double price, Quantity quantity, boolean multipleProducts) {
        List<String> warnings = new ArrayList<>();
        if (!hasText(productName)) {
            warnings.add("未识别到商品名称");
        }
        if (price == null) {
            warnings.add("未识别到实付金额");
        }
        if (hasText(quantity.warning())) {
            warnings.add(quantity.warning());
        }
        if (quantity.quantity() == null || quantity.unit() == null) {
            warnings.add("未识别到明确数量和单位");
        }
        if (multipleProducts) {
            warnings.add(MULTI_PRODUCT_PRICE_WARNING);
        }
        return warnings;
    }

    /**
     * 根据关键字段完整度计算候选样本置信度，取值范围 0.1 到 0.95。
     *
     * @param productName  商品名称，允许为空
     * @param price        实付金额，允许为空
     * @param quantity     解析后的数量对象
     * @param purchaseDate 下单日期，允许为空
     * @param shopName     店铺名称，允许为空
     * @return 置信度评分
     */
    private double calculateConfidence(String productName,
                                       Double price,
                                       Quantity quantity,
                                       String purchaseDate,
                                       String shopName) {
        double confidence = 0.5D;
        confidence += hasText(productName) ? 0.15D : 0D;
        confidence += price != null ? 0.15D : 0D;
        confidence += quantity.quantity() != null && quantity.unit() != null ? 0.1D : 0D;
        confidence += hasText(purchaseDate) ? 0.05D : 0D;
        confidence += hasText(shopName) ? 0.05D : 0D;
        return Math.max(0.1D, Math.min(0.95D, confidence));
    }

    /**
     * 判断文本是否包含 CJK 统一汉字字符。
     *
     * @param text 待判断文本
     * @return 包含中文字符时返回 true
     */
    private boolean containsChinese(String text) {
        return text.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    /**
     * 判断文本是否包含非空白字符。
     *
     * @param text 待判断文本，允许为空
     * @return 文本非空且包含非空白字符时返回 true
     */
    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    /**
     * 规则解析得到的数量与单位，两个字段同时为空表示无法可靠识别。
     *
     * @param quantity 解析后的总数量，无法识别时为空
     * @param unit     数量对应的标准单位，无法识别时为空
     * @param warning  数量解析冲突警告，无冲突时为空
     */
    private record Quantity(Double quantity, String unit, String warning) {
        private static Quantity empty() {
            return new Quantity(null, null, null);
        }

        private static Quantity empty(String warning) {
            return new Quantity(null, null, warning);
        }
    }

    /**
     * 单段文本中的数量解析候选，用优先级和表达式证据避免重复累计同一规格。
     *
     * @param quantity     解析后的总数量
     * @param unit         数量对应的标准单位
     * @param priority     候选优先级，乘法高于加法和直接规格
     * @param source       解析规则来源
     * @param expression   命中的原始规格表达式
     * @param packageCount 包装乘数，不涉及包装时为空
     */
    private record QuantityCandidate(Double quantity,
                                     String unit,
                                     int priority,
                                     String source,
                                     String expression,
                                     Integer packageCount) {
        private Quantity toQuantity() {
            return new Quantity(quantity, unit, null);
        }
    }

    /**
     * 商品名称候选及其评分，用于在 OCR 商品区块中选择最可信标题。
     *
     * @param value     商品名称候选文本
     * @param score     商品区块位置和文本特征计算出的评分
     * @param lineIndex 商品主标题在 OCR 文本行中的位置
     */
    private record ProductNameCandidate(String value, int score, int lineIndex) {
    }
}
