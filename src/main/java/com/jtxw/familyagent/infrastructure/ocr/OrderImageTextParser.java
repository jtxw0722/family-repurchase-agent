package com.jtxw.familyagent.infrastructure.ocr;

import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author: jtxw
 * @Date: 2026/11/14 22:31:14
 * @Description: 订单截图 OCR 文本规则解析器，使用可解释启发式规则生成待确认购买候选样本
 */
@Component
public class OrderImageTextParser {
    /**
     * 候选样本原文片段最大字符数。
     */
    private static final int MAX_SOURCE_TEXT_LENGTH = 500;
    /**
     * 无状态赠品分离器，避免赠品区块污染主商品的字段和价格判断。
     */
    private final OrderImageGiftParser giftParser = new OrderImageGiftParser();
    /**
     * 多商品截图不能可靠分摊订单总金额的警告。
     */
    private static final String MULTI_PRODUCT_PRICE_WARNING = "多商品截图无法可靠分摊实付金额";
    /**
     * SKU 包装数量与商品标题规格冲突时的人工确认警告。
     */
    private static final String PACKAGE_COUNT_CONFLICT_WARNING = "SKU 包装数量与商品标题规格数量不一致，需用户确认";
    /** 只有营销文字而没有可见商品主体时，要求用户展开原图或补填名称。 */
    private static final Pattern MARKETING_ONLY_TITLE_PATTERN = Pattern.compile(
            "^[【\\[]?[^】\\]]*(?:直播间爆品|直播间专享)[】\\]]?$");
    /** 次卡规格必须同时具有明确包装数和次数，不能凭商品图或卡名猜测总权益。 */
    private static final Pattern CARD_SPECIFICATION_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(kg|g|ml|L|片|条|抽|颗)\\s*[xX*×]\\s*(\\d+)\\s*(?:包|袋|盒|瓶|罐|支|条|卷)\\s*[xX*×]\\s*(\\d+)\\s*次", Pattern.CASE_INSENSITIVE);
    /** 次卡名称中的明确权益次数，用于与规格乘数交叉核对。 */
    private static final Pattern CARD_COUNT_PATTERN = Pattern.compile("(\\d+)\\s*次卡");
    /** 只有未闭合规格括号后的包装乘数可跨越一行独立商品价。 */
    private static final Pattern SKU_CONTINUATION_PATTERN = Pattern.compile(
            "^[xX*×]\\s*\\d+\\s*(?:次|包|袋|盒|瓶|罐|支|条|卷)\\s*[）)\\]】].*");
    /** 次卡含量计算后的人工核对说明，不改变购买件数。 */
    private static final String CARD_TOTAL_WARNING = "次卡按全部权益计算，请确认实付金额对应整张卡";
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
            "实付款?(?:\\s*[（(][^）)\\r\\n]*[）)])?\\s*[：:]?\\s*[¥￥]?\\s*(\\d+(?:\\.\\d{1,2})?)");
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
                    + "客服|退货包运费|7天无理由退货|闲鱼转卖|优惠解析|一键解析优惠|^直播$|^预售$|^赠品$|^\\d+件$");
    /**
     * 单独出现的数量、金额和移动端状态文本，不应用作商品名称。
     */
    private static final Pattern NON_PRODUCT_VALUE_PATTERN = Pattern.compile(
            "^(?:[xX×*]\\s*\\d+|[¥￥]\\s*[-+]?\\d+(?:\\.\\d+)?|[-+]?\\d+(?:\\.\\d+)?\\s*元|"
                    + "\\d{1,2}:\\d{2}[a-zA-Z]?|\\d+[Gg]?)$");
    /**
     * 收货联系行中的完整或部分隐藏手机号，不得作为商品标题；不修改或输出原始联系信息。
     */
    private static final Pattern RECIPIENT_CONTACT_PATTERN = Pattern.compile(
            "1[3-9]\\d{9}|1[3-9]\\d{1,2}\\*{3,4}\\d{4}");
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
     * 拼多多订单详情页平台特征；百亿补贴为多平台共用文案，不能单独用于判断平台。
     */
    private static final Pattern PDD_PLATFORM_FEATURE_PATTERN = Pattern.compile(
            "拼多多|拼单|再次拼单|补贴后共优惠");
    /**
     * 商品汇总区起始字段，SKU 无标签回退扫描到此处后停止。
     */
    private static final Pattern PRODUCT_SUMMARY_LINE_PATTERN = Pattern.compile(
            "商品总价|店铺优惠|平台(?:及达人)?优惠|红包已抵|实付款?|订单信息");
    /**
     * 带字段边界的优惠汇总及活动价标签，不能参与商品名称评分或拼接；保留“优惠装猫粮”等真实标题。
     */
    private static final Pattern ORDER_AMOUNT_SUMMARY_PATTERN = Pattern.compile(
            "^(?:(?:[^：:¥￥\\r\\n]*后)?(?:共|已)?优惠(?:合计|总额|金额)?"
                    + "|合计优惠|(?:该规格|合计)[^：:¥￥\\r\\n]*价)(?=\\s*(?:[：:]|[¥￥]|[-+]?\\d|$))");
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
                    + "|^(?:NEW!?|EVER|CLEAN|OL|®|\\d+%|旗舰营养|超凡守护)$",
            Pattern.CASE_INSENSITIVE);
    /**
     * 页面省略号表示当前可见标题已经结束，后续规格不得用于猜测补全被隐藏的标题。
     */
    private static final Pattern TRUNCATED_TITLE_END_PATTERN = Pattern.compile("(?:\\.{2,}|…+)\\s*$");
    /**
     * 明确购买件数标签，兼容值与标签同行或分行；kg、ml 等计量单位不能作为件数。
     */
    private static final Pattern PURCHASE_COUNT_LABEL_PATTERN = Pattern.compile(
            "^(?:已购数量|购买数量|商品数量|数量)\\s*[：:]?\\s*(\\d+)?\\s*(?:件|份|袋|包|盒|瓶|箱|套|组)?\\s*$");
    /**
     * 购买件数标签下一行的独立数量，不能从任意裸数字或金额推测购买件数。
     */
    private static final Pattern PURCHASE_COUNT_VALUE_PATTERN = Pattern.compile(
            "^(\\d+)\\s*(?:件|份|袋|包|盒|瓶|箱|套|组)?\\s*$");
    /**
     * 商品价格旁的购买件数标记，只接受独立 ×N 或紧随人民币价格的 ×N，排除规格乘数。
     */
    private static final Pattern PURCHASE_COUNT_MARKER_PATTERN = Pattern.compile(
            "^(?:.*[¥￥]\\s*\\d+(?:\\.\\d{1,2})?\\s*)?[xX×]\\s*(\\d+)\\s*$");
    /**
     * 单商品候选最多允许一个价格旁件数标记；出现多个标记时需要人工区分商品。
     */
    private static final int MAX_PURCHASE_COUNT_MARKERS = 1;
    /**
     * 店铺名称旁的独立平台徽标，不删除真实店名中的品牌或旗舰店后缀。
     */
    private static final Pattern SHOP_BADGE_PATTERN = Pattern.compile(
            "[【\\[]\\s*(?:官方旗舰店|旗舰店|11\\.11)\\s*[】\\]]");
    /**
     * OCR 拼接到店名尾部的双十一活动徽标，只清理尾部，保留店名内部的合法数字。
     */
    private static final Pattern TRAILING_SHOP_EVENT_PATTERN = Pattern.compile("\\s*11\\.11\\s*$");
    /**
     * 真实旗舰店名称后再次识别到的平台徽标，连续重复时仅保留真实店名的一次后缀。
     */
    private static final Pattern DUPLICATE_SHOP_SUFFIX_PATTERN = Pattern.compile("(旗舰店)(?:\\s*旗舰店)+$");
    /**
     * 口味后接分隔符或以计量值开头的描述为独立规格；不能作为商品标题主体或续行。
     */
    private static final Pattern SKU_DESCRIPTION_PREFIX_PATTERN = Pattern.compile(
            "^(?:[^;；]+[;；]|\\d+(?:\\.\\d+)?\\s*(?:kg|g|ml|L)(?=$|[^a-zA-Z]))", Pattern.CASE_INSENSITIVE);
    /** 带预售、预定标记的完整商品标题起点，优先于包装图中的品牌标志和短广告文字。 */
    private static final Pattern PROMOTIONAL_TITLE_PREFIX_PATTERN = Pattern.compile(
            "^[【\\[][^】\\]]*(?:预售|预定|预订)[^】\\]]*[】\\]]\\s*.+");
    /** 明确预售标题起点的额外优先级，避免选择包装图片上的品牌标志。 */
    private static final int PROMOTIONAL_TITLE_SCORE_BONUS = 30;
    /** 淘宝预售徽标前短旁注的最大字符数，只在明确预售标题区块内使用。 */
    private static final int MAX_BADGE_FRAGMENT_LENGTH = 4;
    /** 独立品牌徽标，例如“品牌圣农”；仅用作文本证据，不作为完整标题。 */
    private static final Pattern BRAND_BADGE_PATTERN = Pattern.compile("^品牌\\s*(\\S+)\\s*$");
    /** 标题同时匹配前方独立品牌和紧邻商品价时的额外分数，避免短品牌徽标抢占主标题。 */
    private static final int BRAND_AND_PRICE_TITLE_SCORE_BONUS = 40;
    /** 价格之后重复包装图短文字的最大长度，不用于过滤普通标题续行。 */
    private static final int MAX_REPEATED_CAPTION_LENGTH = 4;
    /** 相邻包装小字在首字识别不同时，至少需要两个连续相同的尾字才能作为重复片段跳过。 */
    private static final int MIN_REPEATED_CAPTION_SUFFIX_LENGTH = 2;

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
        List<String> recognizedLines = normalizedText.lines().map(String::trim).filter(line -> !line.isBlank()).toList();
        OrderImageGiftParser.GiftSection giftSection = giftParser.extract(recognizedLines);
        List<String> lines = giftSection.mainLines();
        String mainText = String.join("\n", lines);
        // 先确定平台，再应用平台专用清洗规则；请求指定的平台优先，未知平台仅使用通用规则。
        String platform = hasText(requestedPlatform) ? normalizePlatform(requestedPlatform) : detectPlatform(mainText);
        List<String> explicitProductNames = extractExplicitProductNames(lines, platform);
        boolean multipleProducts = explicitProductNames.size() > 1;
        List<String> productNames = resolveProductNames(lines, explicitProductNames, platform);

        String sku = findSku(lines, productNames.get(0), platform);
        Double price = multipleProducts ? null : findPrice(mainText);
        Quantity quantity = findQuantity(sku, productNames, mainText);
        // 购买件数与单件含量分开提取，多商品截图不把某一件商品的数量套用到其他商品。
        Double purchaseQuantity = multipleProducts ? null : findPurchaseQuantity(lines);
        String purchaseDate = hasText(requestedPurchaseDate) ? requestedPurchaseDate.trim() : findDate(mainText);
        String shopName = findShopName(lines);
        String sourceText = normalizedText.substring(0, Math.min(normalizedText.length(), MAX_SOURCE_TEXT_LENGTH));

        List<ParsedPurchaseCandidate> candidates = new ArrayList<>();
        for (String productName : productNames) {
            boolean incompleteTitle = hasText(productName) && MARKETING_ONLY_TITLE_PATTERN.matcher(productName).matches();
            // 保留原始标题作为规格定位锚点，但不能把营销标语作为可保存的商品名称。
            if (incompleteTitle) {
                productName = null;
            }
            List<String> warnings = buildWarnings(productName, price, quantity, multipleProducts);
            if (incompleteTitle) {
                warnings.add("商品标题未完整显示，请展开截图或补充商品名称");
            }
            if (purchaseQuantity == null) {
                warnings.add("未识别到明确购买数量，请核对");
            }
            candidates.add(new ParsedPurchaseCandidate(
                    productName, sku, price, quantity.quantity(), quantity.unit(), platform, owner,
                    purchaseDate, shopName, ParsedPurchaseCandidate.formatOcrNote(false, giftSection.details()), sourceText,
                    calculateConfidence(productName, price, quantity, purchaseDate, shopName),
                    List.copyOf(warnings), null, purchaseQuantity, giftSection.details()));
        }
        return List.copyOf(candidates);
    }

    /**
     * 从 OCR 文本行中提取带有"商品名称"标签的显式商品名。
     *
     * @param lines OCR 原文按行拆分的文本列表
     * @param platform 已确定的平台，允许为空；仅拼多多订单清理其营销前缀
     * @return 清洗后的显式商品名称列表
     */
    private List<String> extractExplicitProductNames(List<String> lines, String platform) {
        List<String> names = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = PRODUCT_NAME_PATTERN.matcher(line);
            if (matcher.matches() && hasText(matcher.group(1))) {
                names.add(cleanProductNameCandidate(matcher.group(1), platform));
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
     * @param platform             已确定的平台，允许为空
     * @return 商品名称候选列表，无法识别时包含一个 null 元素
     */
    private List<String> resolveProductNames(List<String> lines, List<String> explicitProductNames, String platform) {
        if (!explicitProductNames.isEmpty()) {
            return explicitProductNames;
        }
        List<String> productNames = new ArrayList<>();
        productNames.add(findHeuristicProductName(lines, platform));
        return productNames;
    }

    /**
     * 在店铺之后、实付和规格详情之前选择商品标题，并排除横幅、物流和按钮等系统噪声。
     *
     * @param lines OCR 文本行
     * @param platform 已确定的平台，允许为空
     * @return 商品标题；没有可信候选时返回 null
     */
    private String findHeuristicProductName(List<String> lines, String platform) {
        int shopIndex = findFirstLineIndex(lines, this::isShopLine);
        int paidIndex = findFirstLineIndex(lines, line -> line.contains("实付"));
        int skuDetailIndex = findFirstLineIndex(lines, line -> SKU_LABEL_PATTERN.matcher(line).matches());
        // 明确规格标签提供了另一份可核对的文本证据，用于识别商品标题下面的重复规格，而非推测平台布局。
        String labeledSku = findSku(lines, null, platform);
        int candidateEndIndex = findProductTitleCandidateEnd(lines);

        ProductNameCandidate bestCandidate = null;
        for (int index = 0; index < candidateEndIndex; index++) {
            String rawLine = lines.get(index);
            String productNameCandidate = cleanProductNameCandidate(rawLine, platform);
            if (!isProductNameCandidate(productNameCandidate) || isLabeledSkuFragment(productNameCandidate, labeledSku)) {
                continue;
            }
            int score = scoreProductNameCandidate(
                    rawLine, productNameCandidate, index, shopIndex, paidIndex, skuDetailIndex);
            // 只有品牌、标题、相邻商品价三者相互印证时加分，不凭任意金额行提升标题候选。
            if (index + 1 < lines.size() && isStandaloneProductPrice(lines.get(index + 1))
                    && hasMatchingBrandBadge(lines, index, productNameCandidate)) {
                score += BRAND_AND_PRICE_TITLE_SCORE_BONUS;
            }
            if (bestCandidate == null || score > bestCandidate.score()) {
                bestCandidate = new ProductNameCandidate(productNameCandidate, score, index);
            }
        }
        return bestCandidate == null ? null : buildProductNameFromProductBlock(lines, bestCandidate, platform, labeledSku);
    }

    /**
     * 以先出现的商品价和购买件数确定标题候选区域，避免订单下方任意新促销金额再次抢占标题。
     *
     * @param lines OCR 文本行
     * @return 候选区域末尾的下一行索引；缺少价格或件数证据时保留原有整页候选范围
     */
    private int findProductTitleCandidateEnd(List<String> lines) {
        boolean productPriceSeen = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            productPriceSeen |= STANDALONE_PRICE_PATTERN.matcher(line).find();
            if (productPriceSeen && PURCHASE_COUNT_MARKER_PATTERN.matcher(line).matches()) {
                return index + 1;
            }
        }
        return lines.size();
    }

    /** 根据前方独立品牌徽标判断标题是否确实以该品牌开头，不清理其他平台的品牌前缀。 */
    private boolean hasMatchingBrandBadge(List<String> lines, int titleIndex, String title) {
        for (int index = 0; index < titleIndex; index++) {
            Matcher matcher = BRAND_BADGE_PATTERN.matcher(lines.get(index));
            if (matcher.matches() && title.startsWith(matcher.group(1)) && !title.equals(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    /** 判断行是否只有商品金额，含业务说明或商品名称的金额行不能作为相邻价格锚点。 */
    private boolean isStandaloneProductPrice(String line) {
        return NON_PRODUCT_VALUE_PATTERN.matcher(line).matches() && STANDALONE_PRICE_PATTERN.matcher(line).find();
    }

    /**
     * 从主标题行向后扫描商品区块，跳过图片噪声并拼接被 OCR 拆开的有效标题片段。
     *
     * @param lines         OCR 文本行
     * @param baseCandidate 已选出的主标题及其位置
     * @param platform      已确定的平台，允许为空
     * @param labeledSku    明确规格标签解析出的规格，允许为空；用于识别重复规格的起点
     * @return 拼接并清洗后的商品名称
     */
    private String buildProductNameFromProductBlock(List<String> lines, ProductNameCandidate baseCandidate,
                                                  String platform, String labeledSku) {
        StringBuilder productNameBuilder = new StringBuilder(baseCandidate.value());
        boolean afterStandalonePrice = false;
        boolean repeatedCaptionSeen = false;
        for (int index = baseCandidate.lineIndex() + 1; index < lines.size(); index++) {
            if (TRUNCATED_TITLE_END_PATTERN.matcher(productNameBuilder).find()) {
                break;
            }
            String line = lines.get(index);
            if (isStandaloneProductPrice(line)) {
                afterStandalonePrice = true;
                repeatedCaptionSeen = false;
                continue;
            }
            if (PRODUCT_SUMMARY_LINE_PATTERN.matcher(line).find()
                    || ORDER_AMOUNT_SUMMARY_PATTERN.matcher(line).find()
                    || SKU_PATTERN.matcher(line).matches()
                    || SKU_LABEL_PATTERN.matcher(line).matches()
                    || isShopLine(line)
                    || isSkuDescription(line)
                    || isLabeledSkuFragment(line, labeledSku)
                    || SPECIFICATION_MULTIPLIER_PATTERN.matcher(line).find()
                    || (line.startsWith("【") && DIRECT_QUANTITY_PATTERN.matcher(line).find())) {
                break;
            }
            // 标题续行也会包含容量或重量，不能仅因出现 5kg、500ml 就把它当作独立规格。
            String titleFragment = cleanProductNameCandidate(line, platform);
            // 只在独立价格之后的重复小字组内去重；真实标题续行一旦出现，就恢复普通拼接。
            if (afterStandalonePrice && isRepeatedPackagingCaption(
                    titleFragment, productNameBuilder.toString(), repeatedCaptionSeen)) {
                repeatedCaptionSeen = true;
                continue;
            }
            // 淘宝预售标题附近可能混入包装图的小字，仅跳过紧邻独立预售徽标的短旁注。
            // 普通平台、无明确预售起点、末尾短标题续行均继续沿用通用拼接规则。
            if ("taobao".equals(platform)
                    && PROMOTIONAL_TITLE_PREFIX_PATTERN.matcher(lines.get(baseCandidate.lineIndex())).matches()
                    && titleFragment.length() <= MAX_BADGE_FRAGMENT_LENGTH
                    && index + 1 < lines.size() && "预售".equals(lines.get(index + 1))) {
                continue;
            }
            if (isProductTitleContinuation(titleFragment)) {
                productNameBuilder.append(titleFragment);
                afterStandalonePrice = false;
                repeatedCaptionSeen = false;
            }
        }
        return cleanProductNameCandidate(productNameBuilder.toString(), platform);
    }

    /**
     * 判断紧跟价格的短文本是否重复了已选标题内容，不根据商品品牌或平台维护专属词表。
     *
     * @param fragment 待判断短文本
     * @param title 已确认的标题起始内容
     * @param repeatedCaptionSeen 相邻前一片段是否已被确认为重复包装文字
     * @return 短文本直接重复标题，或在重复小字组内仅首字不同且尾字连续匹配时返回 true
     */
    private boolean isRepeatedPackagingCaption(String fragment, String title, boolean repeatedCaptionSeen) {
        if (!hasText(fragment) || fragment.length() > MAX_REPEATED_CAPTION_LENGTH || !containsChinese(fragment)) {
            return false;
        }
        if (title.contains(fragment)) {
            return true;
        }
        return repeatedCaptionSeen && fragment.length() > MIN_REPEATED_CAPTION_SUFFIX_LENGTH
                && title.contains(fragment.substring(1));
    }

    /**
     * 判断文本是否是有效中文标题续行，排除价格、按钮、图标和营销短语；规格边界由调用方判断。
     *
     * @param line 已完成尾部价格清洗的 OCR 文本行
     * @return 包含有效中文商品描述且不属于噪声时返回 true
     */
    private boolean isProductTitleContinuation(String line) {
        return hasText(line)
                && containsChinese(line)
                && !SYSTEM_LINE_PATTERN.matcher(line).find()
                && !RECIPIENT_CONTACT_PATTERN.matcher(line).find()
                && !ORDER_AMOUNT_SUMMARY_PATTERN.matcher(line).find()
                && !PRODUCT_TITLE_FRAGMENT_NOISE_PATTERN.matcher(line).find()
                && !NON_PRODUCT_VALUE_PATTERN.matcher(line).matches()
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
                && !RECIPIENT_CONTACT_PATTERN.matcher(line).find()
                && !ORDER_AMOUNT_SUMMARY_PATTERN.matcher(line).find()
                && !NON_PRODUCT_VALUE_PATTERN.matcher(line).matches()
                && !PRODUCT_TITLE_FRAGMENT_NOISE_PATTERN.matcher(line).find()
                && !isSkuDescription(line)
                && detectPlatform(line) == null
                && !isShopLine(line);
    }

    /**
     * 判断口味、颜色等分隔描述是否为含计量值的规格，保持标题中的年份及普通容量描述不受影响。
     *
     * @param line 已去除首尾空白的 OCR 文本行
     * @return 有明确规格前缀且包含目标计量单位时返回 true
     */
    private boolean isSkuDescription(String line) {
        return SKU_DESCRIPTION_PREFIX_PATTERN.matcher(line).find() && DIRECT_QUANTITY_PATTERN.matcher(line).find();
    }

    /**
     * 判断含计量值的文本是否重复了明确已购规格的起始片段，作为标题与规格之间的边界。
     *
     * @param line 商品区文本行，允许为空
     * @param labeledSku 明确规格标签的完整值，允许为空
     * @return 片段含计量值且与已购规格起始内容一致时返回 true；不凭重量或包装单位单独判断
     */
    private boolean isLabeledSkuFragment(String line, String labeledSku) {
        if (!hasText(line) || !hasText(labeledSku) || !DIRECT_QUANTITY_PATTERN.matcher(line).find()) {
            return false;
        }
        // OCR 会在标题卡片和订单详情中使用不同空白分隔，同一规格比较时只忽略空白，不猜测或改写文字。
        String compactFragment = cleanSkuCandidate(line).replaceAll("\\s+", "");
        String compactSku = labeledSku.replaceAll("\\s+", "");
        return !compactFragment.isEmpty() && compactSku.startsWith(compactFragment);
    }

    /**
     * 清除 OCR 商品标题尾部附着的价格、数量按钮和装饰符号，同时保留标题中的中英文及规格数字。
     *
     * @param line OCR 原始商品标题候选
     * @param platform 已确定的平台，允许为空；拼多多专用前缀不得用于其他平台
     * @return 清洗后的商品标题；原始文本为空时返回空文本
     */
    private String cleanProductNameCandidate(String line, String platform) {
        if (!hasText(line)) {
            return line;
        }
        String cleanedLine = line.trim();
        if ("pdd".equalsIgnoreCase(platform) || "拼多多".equals(platform)) {
            cleanedLine = PDD_PRODUCT_MARKETING_PREFIX_PATTERN.matcher(cleanedLine).replaceFirst("");
        }
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
        if (PROMOTIONAL_TITLE_PREFIX_PATTERN.matcher(rawLine).matches()) {
            score += PROMOTIONAL_TITLE_SCORE_BONUS;
        }
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
     * @param platform    已确定的平台，允许为空
     * @return 规格文本，未找到时返回空
     */
    private String findSku(List<String> lines, String productName, String platform) {
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher matcher = SKU_PATTERN.matcher(line);
            if (matcher.matches()) {
                return collectSkuLines(lines, index + 1, matcher.group(1));
            }
            if (SKU_LABEL_PATTERN.matcher(line).matches()) {
                String nextValue = findNextValidSkuValue(lines, index + 1);
                if (nextValue != null) {
                    return nextValue;
                }
            }
        }
        return findSkuFromProductBlock(lines, productName, platform);
    }

    /**
     * 在缺少规格标签时，从商品标题之后到订单汇总区之前寻找包含明确目标单位的副标题。
     *
     * @param lines       OCR 文本行
     * @param productName 已选出的商品名称，允许为空
     * @param platform    已确定的平台，允许为空
     * @return 商品区块中的规格副标题；没有可信候选时返回 null
     */
    private String findSkuFromProductBlock(List<String> lines, String productName, String platform) {
        if (!hasText(productName)) {
            return null;
        }
        int productNameIndex = findFirstLineIndex(
                lines, line -> productName.startsWith(cleanProductNameCandidate(line, platform))
                        && hasText(cleanProductNameCandidate(line, platform)));
        if (productNameIndex < 0) {
            return null;
        }
        int specificationIndex = -1;
        for (int index = productNameIndex + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (PRODUCT_SUMMARY_LINE_PATTERN.matcher(line).find()) {
                break;
            }
            if (!SYSTEM_LINE_PATTERN.matcher(line).find()
                    && !NON_PRODUCT_VALUE_PATTERN.matcher(line).matches()
                    && !isShopLine(line)
                    && DIRECT_QUANTITY_PATTERN.matcher(line).find()) {
                if (specificationIndex < 0 || isMoreCompleteSkuStart(lines.get(specificationIndex), line)) {
                    specificationIndex = index;
                }
            }
        }
        return specificationIndex < 0 ? null
                : collectSkuLines(lines, specificationIndex + 1, lines.get(specificationIndex));
    }

    /**
     * 同一商品区内出现相同计量规格时，优先采用更长的描述行，避免先出现的缩略图小字抢占规格。
     *
     * @param previous 已选规格首行
     * @param candidate 后续规格首行
     * @return 后续行有相同明确计量值且描述更完整时为 true
     */
    private boolean isMoreCompleteSkuStart(String previous, String candidate) {
        Matcher previousQuantity = DIRECT_QUANTITY_PATTERN.matcher(previous);
        Matcher candidateQuantity = DIRECT_QUANTITY_PATTERN.matcher(candidate);
        return previousQuantity.find() && candidateQuantity.find()
                && previousQuantity.group().replaceAll("\\s+", "").equalsIgnoreCase(
                        candidateQuantity.group().replaceAll("\\s+", ""))
                && candidate.length() > previous.length();
    }

    /**
     * 从规格标签之后读取连续规格文本，遇到其他业务字段时停止，避免跨区块误取值。
     *
     * @param lines OCR 文本行
     * @param startIndex 规格标签之后的首行位置
     * @return 合并后的规格，未识别到有效文本时返回 null
     */
    private String findNextValidSkuValue(List<String> lines, int startIndex) {
        return collectSkuLines(lines, startIndex, "");
    }

    /**
     * 合并被 OCR 换行拆开的规格；规则依据字段边界，不假设具体平台的行数或页面位置。
     *
     * @param lines OCR 文本行
     * @param startIndex 需要继续读取的首行位置
     * @param firstFragment 标签同一行或商品副标题中的规格片段，允许为空文本
     * @return 完整可见规格；没有有效文本时返回 null，不补造截图未显示的内容
     */
    private String collectSkuLines(List<String> lines, int startIndex, String firstFragment) {
        StringBuilder specification = new StringBuilder(cleanSkuCandidate(firstFragment));
        for (int index = startIndex; index < lines.size(); index++) {
            String line = lines.get(index);
            // 原价与换行规格在视觉上并排，OCR 可能将其插入未闭合的括号中；只跨越这一种明确连续情形。
            if (isStandaloneProductPrice(line) && index + 1 < lines.size()
                    && hasUnclosedSkuBracket(specification.toString())
                    && SKU_CONTINUATION_PATTERN.matcher(lines.get(index + 1)).matches()) {
                continue;
            }
            if (SYSTEM_LINE_PATTERN.matcher(line).find()
                    || ORDER_AMOUNT_SUMMARY_PATTERN.matcher(line).find()
                    || PRODUCT_NAME_PATTERN.matcher(line).matches()
                    || NON_PRODUCT_VALUE_PATTERN.matcher(line).matches()
                    || PRODUCT_TITLE_FRAGMENT_NOISE_PATTERN.matcher(line).find()
                    || isShopLine(line)) {
                break;
            }
            String cleanedLine = cleanSkuCandidate(line);
            // 首行可能只有商品描述，容量、包装数和被拆开的品牌名会出现在后续行。
            if (containsChinese(cleanedLine) || isValidSkuValue(cleanedLine)) {
                specification.append(cleanedLine);
            }
        }
        String result = cleanSkuCandidate(specification.toString());
        return result.isBlank() ? null : result;
    }

    /**
     * 判断规格中是否存在尚未闭合的中英文括号，不按固定平台或固定行数合并文字。
     *
     * @param specification 已收集的规格
     * @return 左括号数量多于右括号时为 true
     */
    private boolean hasUnclosedSkuBracket(String specification) {
        long openings = specification.chars().filter(value -> "([（【".indexOf(value) >= 0).count();
        long closings = specification.chars().filter(value -> ")]）】".indexOf(value) >= 0).count();
        return openings > closings;
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
     * 按 SKU、商品名称、完整原文的顺序解析单件含量，并保守处理 SKU 包装数与标题规格冲突。
     * 次卡只根据完整 SKU 核对全部权益，证据不足时不回退到标题或商品图中的单次含量。
     *
     * @param sku          OCR 识别的规格文本，允许为空
     * @param productNames OCR 识别的商品名称列表
     * @param rawText      OCR 完整原文
     * @return 最高可信度的数量解析结果，冲突或无法识别时返回空结果
     */
    private Quantity findQuantity(String sku, List<String> productNames, String rawText) {
        if (hasText(sku) && sku.contains("次卡")) {
            return findCardQuantity(sku);
        }
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
     * 根据同一规格中的卡次数与连续乘式计算整卡权益；证据缺失、冲突或多个表达式时留空待核对。
     *
     * @param sku 已隔离主商品的完整规格
     * @return 明确权益总量及整卡金额核对提示，不修改购买件数
     */
    private Quantity findCardQuantity(String sku) {
        Matcher cardCount = CARD_COUNT_PATTERN.matcher(sku);
        Matcher specification = CARD_SPECIFICATION_PATTERN.matcher(sku);
        if (!cardCount.find() || !specification.find()) {
            return Quantity.empty("次卡权益规格不完整，请确认总含量与整卡金额");
        }
        BigDecimal count = new BigDecimal(cardCount.group(1));
        BigDecimal rounds = new BigDecimal(specification.group(4));
        BigDecimal packages = new BigDecimal(specification.group(3));
        BigDecimal amount = new BigDecimal(specification.group(1));
        String unit = normalizeUnit(specification.group(2));
        if (count.signum() <= 0 || packages.signum() <= 0 || amount.signum() <= 0
                || count.compareTo(rounds) != 0 || cardCount.find() || specification.find()) {
            return Quantity.empty("次卡权益数量不一致，请确认总含量与整卡金额");
        }
        double totalQuantity = amount.multiply(packages).multiply(rounds).doubleValue();
        return Double.isFinite(totalQuantity)
                ? new Quantity(totalQuantity, unit, CARD_TOTAL_WARNING)
                : Quantity.empty("次卡权益数量无效，请确认总含量");
    }

    /**
     * 从购买件数标签和价格旁的 ×N 提取件数，不使用 5kg*1袋 等规格中的包装乘数。
     *
     * @param lines 已去除空行的 OCR 文本行
     * @return 明确且一致的正整数件数；缺失、冲突或出现多个商品数量标记时返回 null
     */
    private Double findPurchaseQuantity(List<String> lines) {
        List<Double> purchaseCountCandidates = new ArrayList<>();
        int purchaseMarkerCount = 0;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher labelMatcher = PURCHASE_COUNT_LABEL_PATTERN.matcher(line);
            Matcher markerMatcher = PURCHASE_COUNT_MARKER_PATTERN.matcher(line);
            String countText = null;
            if (labelMatcher.matches()) {
                countText = labelMatcher.group(1);
                if (countText == null && index + 1 < lines.size()) {
                    Matcher valueMatcher = PURCHASE_COUNT_VALUE_PATTERN.matcher(lines.get(index + 1));
                    if (valueMatcher.matches()) {
                        countText = valueMatcher.group(1);
                    }
                }
            } else if (markerMatcher.matches()) {
                countText = markerMatcher.group(1);
                purchaseMarkerCount++;
            }
            if (countText == null) {
                continue;
            }
            BigDecimal count = new BigDecimal(countText);
            // 购买件数必须为可表示的正整数，异常识别值不能进入表单参与单价计算。
            if (count.signum() > 0 && count.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0) {
                double purchaseCount = count.doubleValue();
                if (!purchaseCountCandidates.contains(purchaseCount)) {
                    purchaseCountCandidates.add(purchaseCount);
                }
            }
        }
        return purchaseMarkerCount <= MAX_PURCHASE_COUNT_MARKERS && purchaseCountCandidates.size() == 1
                ? purchaseCountCandidates.get(0) : null;
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
                return cleanShopNameCandidate(line);
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
        String shopNameCandidate = cleanShopNameCandidate(line);
        if ("旗舰店".equals(shopNameCandidate) || "官方旗舰店".equals(shopNameCandidate)) {
            return false;
        }
        return SHOP_NAME_PATTERN.matcher(shopNameCandidate).matches();
    }

    /**
     * 提取店铺名称文本，清理店铺标签、活动徽标和重复后缀，保留名称中的英文、数字和真实旗舰店后缀。
     *
     * @param line 店铺候选原文行，不允许为空
     * @return 清理后的名称；仅有平台徽标时可能为空文本
     */
    private String cleanShopNameCandidate(String line) {
        int separatorIndex = Math.max(line.indexOf('：'), line.indexOf(':'));
        String shopName = separatorIndex >= 0 ? line.substring(separatorIndex + 1).trim() : line.trim();
        shopName = SHOP_BADGE_PATTERN.matcher(shopName).replaceAll("").trim();
        shopName = shopName.replaceFirst("\\s*[>〉]+\\s*$", "").trim();
        shopName = TRAILING_SHOP_EVENT_PATTERN.matcher(shopName).replaceFirst("").trim();
        shopName = shopName.replaceFirst("\\s*(?:直播中|直播)\\s*$", "").trim();
        shopName = DUPLICATE_SHOP_SUFFIX_PATTERN.matcher(shopName).replaceFirst("$1").trim();
        // 天猫标志可能与店名拼在同一 OCR 行；“天猫超市”本身是店名，必须保留。
        if (shopName.startsWith("天猫") && !shopName.startsWith("天猫超市")) {
            shopName = shopName.substring("天猫".length()).trim();
        }
        return shopName;
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
            return "taobao";
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
     * 统一截图请求中的平台别名，天猫归属淘宝；其余既有平台标识原样保留。
     *
     * @param platform 请求提供的非空平台名称或标识
     * @return 已清理首尾空白的平台标识
     */
    private String normalizePlatform(String platform) {
        String value = platform.trim();
        return "tmall".equalsIgnoreCase(value) || "天猫".equals(value) || "淘宝".equals(value) ? "taobao" : value;
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
