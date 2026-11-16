package com.jtxw.familyagent.infrastructure.ocr;

import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/11/11 13:26:35
 * @Description: 订单截图 OCR 文本规则解析器测试，覆盖跨平台字段边界、换行标题与规格和缺失信息警告
 */
class OrderImageTextParserTest {
    /** 待测试的无状态 OCR 文本规则解析器。 */
    private final OrderImageTextParser parser = new OrderImageTextParser();

    /** 使用实际 OCR 商品区块及合成收货信息，避免联系行、优惠汇总和重复规格抢占主商品标题。 */
    @Test
    void shouldReadBoostCatFoodTitleInsteadOfDiscountSummary() {
        ParsedPurchaseCandidate candidate = parser.parse("""
                13:19
                !!! 5GA (16
                交易成功
                合成收货人138****0000测试路1234号后
                已签收
                测试管理处2221
                高高的
                高高的宠物小铺五星好店！
                定物小铺
                格吾安boost乳铁蛋白鲜肉1.5kg全价
                ￥69.9
                冻干猫粮小圆盾烘焙鸡肉配方
                X1
                乳铁蛋白小圆盾1.5kg/袋，有防伪拆开不
                退不换
                退货包运费保障中
                分享商品
                联系商家
                申请退款
                大促后共优惠￥6.99
                实付：￥62.91(免运费)
                已购规格
                乳铁蛋白小圆盾1.5kg/袋，有防伪拆开不退不换
                该规格大促价
                ￥69.9
                已购数量
                1
                合计大促价
                ￥69.9
                平台优惠
                -￥6.99
                下单时间：2026-10-28 21:51:44
                再次拼单
                泰森
                世界500强品牌
                格吾安
                格吾安
                泰森
                去皮鸡大脑
                更多
                查看物流
                立即评价
                """, "jtxw", null, null).get(0);

        assertThat(candidate.productName()).isEqualTo("格吾安boost乳铁蛋白鲜肉1.5kg全价冻干猫粮小圆盾烘焙鸡肉配方");
        assertThat(candidate.sku()).isEqualTo("乳铁蛋白小圆盾1.5kg/袋，有防伪拆开不退不换");
        assertThat(candidate.price()).isEqualTo(62.91D);
        assertThat(candidate.purchaseQuantity()).isEqualTo(1D);
        assertThat(candidate.quantity()).isEqualTo(1.5D);
        assertThat(candidate.unit()).isEqualTo("kg");
    }

    /** 金额汇总与重复规格根据文本语义识别，不依赖某个平台的坐标或固定行数。 */
    @ParameterizedTest
    @ValueSource(strings = {"pdd", "taobao", "tmall", "jd", ""})
    void shouldKeepTitleSeparateFromRepeatedPurchasedSkuAcrossPlatforms(String platform) {
        ParsedPurchaseCandidate candidate = parser.parse("""
                格吾安boost乳铁蛋白鲜肉1.5kg全价
                ￥69.9
                冻干猫粮小圆盾烘焙鸡肉配方
                ×1
                乳铁蛋白小圆盾1.5kg/袋，有防伪拆开不
                退不换
                活动后共优惠￥6.99
                实付款：￥62.91
                商品规格：乳铁蛋白小圆盾1.5kg/袋，有防伪拆开不退不换
                已购数量：1
                """, "jtxw", platform, null).get(0);
        assertThat(candidate.productName()).isEqualTo("格吾安boost乳铁蛋白鲜肉1.5kg全价冻干猫粮小圆盾烘焙鸡肉配方");
        assertThat(candidate.sku()).isEqualTo("乳铁蛋白小圆盾1.5kg/袋，有防伪拆开不退不换");
    }

    /** 标题中的优惠装或每袋含量仍属于商品描述，不能仅因出现“优惠”或“kg/袋”而过滤。 */
    @Test
    void shouldKeepDiscountProductWordingAndPerBagContentInRealTitle() {
        ParsedPurchaseCandidate candidate = parser.parse("""
                优惠装全价猫粮1.5kg/袋鸡肉配方
                ×1
                已购规格：原味1.5kg/袋
                实付款：￥62.91
                """, "jtxw", "jd", null).get(0);
        assertThat(candidate.productName()).isEqualTo("优惠装全价猫粮1.5kg/袋鸡肉配方");
    }

    /**
     * 使用已移除收货信息的实际 OCR 商品区块，验证年份、标题和已购规格跨行时不会被截断。
     */
    @Test
    void shouldMergeRiceTitleAndPurchasedSpecificationAcrossLines() {
        ParsedPurchaseCandidate candidate = parser.parse("""
                柴火大院粮油旗舰店旗舰店
                百亿补贴
                品牌柴火大院【柴火大院】20
                ￥47.9
                26年新米五常生态香米5kg10斤五..
                ×1
                【官方旗舰正品保障】【五常核心产区地
                道原粮】柴火大院五常生态香米5kg*1袋
                7天价保>
                7天无理由退货>
                分享商品
                实付：￥41.41（免运费）
                已购规格
                【官方旗舰正品保障】，【五常核心产区地道原粮】柴火
                大院五常生态香米5kg*1袋
                该规格补贴价
                ￥47.9
                已购数量
                1
                再次拼单
                """, "jtxw", null, null).get(0);

        assertThat(candidate.productName()).isEqualTo("柴火大院【柴火大院】2026年新米五常生态香米5kg10斤五..");
        assertThat(candidate.sku()).isEqualTo("【官方旗舰正品保障】，【五常核心产区地道原粮】柴火大院五常生态香米5kg*1袋");
        assertThat(candidate.price()).isEqualTo(41.41D);
        assertThat(candidate.quantity()).isEqualTo(5D);
        assertThat(candidate.purchaseQuantity()).isEqualTo(1D);
        assertThat(candidate.unit()).isEqualTo("kg");
        assertThat(candidate.platform()).isEqualTo("pdd");
        assertThat(candidate.shopName()).isEqualTo("柴火大院粮油旗舰店");
    }

    /**
     * 购买件数从价格旁标记或明确数量字段提取，单件 5kg 的规格不应替代购买件数。
     */
    @ParameterizedTest
    @MethodSource("purchaseCountCases")
    void shouldSeparatePurchaseCountFromSingleItemContent(String countText, double expectedCount) {
        ParsedPurchaseCandidate candidate = parser.parse(
                "商品名称：合成测试大米\n规格：5kg*1袋\n实付：41.41元\n" + countText,
                "jtxw", null, null).get(0);

        assertThat(candidate.purchaseQuantity()).isEqualTo(expectedCount);
        assertThat(candidate.quantity()).isEqualTo(5D);
        assertThat(candidate.unit()).isEqualTo("kg");
    }

    /**
     * 不同平台使用同一件数语义，不根据单个平台的固定页面位置取值。
     */
    @ParameterizedTest
    @ValueSource(strings = {"pdd", "taobao", "tmall", "jd", ""})
    void shouldReadPurchaseCountAcrossPlatforms(String platform) {
        ParsedPurchaseCandidate candidate = parser.parse(
                "商品名称：合成测试大米\n规格：5kg*1袋\n￥49.9 ×2\n实付款：79.8元",
                "jtxw", platform, null).get(0);

        assertThat(candidate.purchaseQuantity()).isEqualTo(2D);
        assertThat(candidate.quantity()).isEqualTo(5D);
    }

    /**
     * 件数缺失、冲突、非法或包含多商品标记时保留空值，不把规格乘数或订单金额当作购买件数。
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "×2\n已购数量：1", "×1\n×1", "数量：0", "数量：-1", "已购数量：1.5",
            "×9999999999999999999999999999"})
    void shouldNotGuessMissingOrAmbiguousPurchaseCount(String countText) {
        ParsedPurchaseCandidate candidate = parser.parse(
                "商品名称：合成测试大米\n规格：5kg*2袋\n实付：41.41元\n" + countText,
                "jtxw", null, null).get(0);

        assertThat(candidate.purchaseQuantity()).isNull();
        assertThat(candidate.quantity()).isEqualTo(10D);
        assertThat(candidate.warnings()).contains("未识别到明确购买数量，请核对");
    }

    /**
     * 店铺名称仅移除附加徽标，保留真实店名中的旗舰店后缀和英文数字品牌。
     */
    @ParameterizedTest
    @MethodSource("shopBadgeCases")
    void shouldRemoveShopBadgesWithoutDamagingRealShopName(String shopLine, String expectedName) {
        ParsedPurchaseCandidate candidate = parser.parse(shopLine
                + "\n商品名称：合成测试商品\n规格：5kg\n×1\n实付：41.41元",
                "jtxw", null, null).get(0);

        assertThat(candidate.shopName()).isEqualTo(expectedName);
    }

    /**
     * 提供价格同行、独立标记、同行标签和换行标签的购买件数样例。
     *
     * @return 件数文本与明确的预期件数
     */
    private static Stream<Arguments> purchaseCountCases() {
        return Stream.of(Arguments.of("x1", 1D), Arguments.of("X2", 2D), Arguments.of("×3", 3D),
                Arguments.of("￥41.41 ×2", 2D), Arguments.of("已购数量：2", 2D),
                Arguments.of("购买数量\n2", 2D), Arguments.of("数量：2件", 2D), Arguments.of("商品数量2", 2D));
    }

    /**
     * 提供店铺徽标同行、无空格拼接和真实名称需要保留数字的场景。
     *
     * @return 原始店铺行与清理后的真实名称
     */
    private static Stream<Arguments> shopBadgeCases() {
        return Stream.of(
                Arguments.of("柴火大院粮油旗舰店 【旗舰店】 【11.11】 >", "柴火大院粮油旗舰店"),
                Arguments.of("柴火大院粮油旗舰店旗舰店11.11", "柴火大院粮油旗舰店"),
                Arguments.of("店铺：柴火大院粮油旗舰店 旗舰店 11.11", "柴火大院粮油旗舰店"),
                Arguments.of("天猫超市【11.11】", "天猫超市"),
                Arguments.of("京东自营 [11.11]", "京东自营"),
                Arguments.of("3M官方旗舰店旗舰店", "3M官方旗舰店"));
    }

    /**
     * 通用换行规则不依赖拼多多标签或平台布局，其他平台及未知平台均保留含重量的标题续行。
     */
    @ParameterizedTest
    @ValueSource(strings = {"taobao", "tmall", "jd", ""})
    void shouldMergeWeightBearingTitleWithoutPddLayout(String platform) {
        ParsedPurchaseCandidate candidate = parser.parse("""
                合成粮油官方旗舰店
                品牌精选东北新米20
                ￥49.9
                26年五常大米5kg家庭装
                颜色分类：原味；
                家庭装5kg*1袋
                实付款：￥39.9
                """, "jtxw", platform, null).get(0);

        assertThat(candidate.productName()).isEqualTo("品牌精选东北新米2026年五常大米5kg家庭装");
        assertThat(candidate.sku()).isEqualTo("原味；家庭装5kg*1袋");
        assertThat(candidate.price()).isEqualTo(39.9D);
        assertThat(candidate.quantity()).isEqualTo(5D);
    }

    /**
     * 显式规格跨行时应在下一业务字段处停止，不能把数量和金额标签拼入规格。
     */
    @ParameterizedTest
    @ValueSource(strings = {"pdd", "taobao", "jd", ""})
    void shouldMergeLabeledSkuWithoutCrossingOrderFields(String platform) {
        ParsedPurchaseCandidate candidate = parser.parse("""
                商品名称：合成洗衣液
                商品规格：清香型；
                家庭装500ml*2瓶
                已购数量：1
                实付款：￥19.9
                """, "jtxw", platform, null).get(0);

        assertThat(candidate.sku()).isEqualTo("清香型；家庭装500ml*2瓶");
        assertThat(candidate.quantity()).isEqualTo(1000D);
        assertThat(candidate.unit()).isEqualTo("ml");
    }

    /**
     * 拼多多专用营销前缀不得清理其他平台或未知平台商品名称中的同名文本。
     */
    @ParameterizedTest
    @ValueSource(strings = {"taobao", "tmall", "jd", ""})
    void shouldKeepOtherPlatformBrandPrefix(String platform) {
        ParsedPurchaseCandidate candidate = parser.parse(
                "商品名称：品牌好货 合成测试纸巾\n规格：100抽\n实付：10元", "jtxw", platform, null).get(0);

        assertThat(candidate.productName()).isEqualTo("品牌好货 合成测试纸巾");
    }

    /**
     * 百亿补贴为多平台共享营销文案，未知平台保持未知，不能据此套用拼多多清洗规则。
     */
    @ParameterizedTest
    @MethodSource("sharedSubsidyPlatformCases")
    void shouldNotInferPddFromSharedSubsidyBadge(String platformText, String expectedPlatform) {
        ParsedPurchaseCandidate candidate = parser.parse(platformText
                + "\n百亿补贴\n商品名称：品牌好货 合成测试纸巾\n规格：100抽\n实付：10元",
                "jtxw", null, null).get(0);

        assertThat(candidate.platform()).isEqualTo(expectedPlatform);
        assertThat(candidate.productName()).isEqualTo("品牌好货 合成测试纸巾");
    }

    /**
     * 提供共享补贴文案下各平台标识及无法判断平台的测试场景。
     *
     * @return 页面平台文字和预期平台标识，不读取真实订单数据
     */
    private static Stream<Arguments> sharedSubsidyPlatformCases() {
        return Stream.of(Arguments.of("淘宝", "taobao"), Arguments.of("天猫", "taobao"),
                Arguments.of("京东", "jd"), Arguments.of("", null));
    }

    @Test
    void shouldParseSingleProductFromPddLikeText() {
        ParsedPurchaseCandidate candidate = parsePddCandidate(null, null);

        assertThat(candidate.productName()).isEqualTo("舒肤佳红石榴沐浴露");
        assertThat(candidate.sku()).isEqualTo("红石榴啫喱沐浴露380g+400g");
        assertThat(candidate.price()).isEqualTo(35.22D);
        assertThat(candidate.quantity()).isEqualTo(780D);
        assertThat(candidate.unit()).isEqualTo("g");
        assertThat(candidate.shopName()).isEqualTo("宝洁官方旗舰店");
        assertThat(candidate.purchaseDate()).isEqualTo("2024-06-27");
        assertThat(candidate.normalization()).isNull();
    }

    @Test
    void shouldParsePriceFromPaidAmountText() {
        ParsedPurchaseCandidate candidate = parser.parse("商品名称：合成测试纸巾\n实付款：￥19.90", null, null, null).get(0);

        assertThat(candidate.price()).isEqualTo(19.90D);
    }

    @Test
    void shouldPreferRequestPurchaseDateWhenProvided() {
        ParsedPurchaseCandidate candidate = parsePddCandidate(null, "2026-06-18");

        assertThat(candidate.purchaseDate()).isEqualTo("2026-06-18");
    }

    @Test
    void shouldReturnWarningWhenPriceMissing() {
        ParsedPurchaseCandidate candidate = parser.parse("商品名称：合成测试商品\n规格：10片", null, null, null).get(0);

        assertThat(candidate.warnings()).contains("未识别到实付金额");
    }

    @Test
    void shouldReturnWarningWhenQuantityUnitMissing() {
        ParsedPurchaseCandidate candidate = parser.parse("商品名称：合成测试商品\n实付：20元", null, null, null).get(0);

        assertThat(candidate.warnings()).contains("未识别到明确数量和单位");
    }

    @Test
    void shouldParseSkuFromSpecLine() {
        ParsedPurchaseCandidate candidate = parsePddCandidate(null, null);

        assertThat(candidate.sku()).isEqualTo("红石榴啫喱沐浴露380g+400g");
    }

    @Test
    void shouldParseSanitizedRealOcrFixture() {
        String rawText = """
                08:20m
                4G 69
                交易成功
                为了补偿您在购物过程中遇到的不便，已赠您共3元无
                门槛券
                张三 138****0000 测试地址4179号
                已签收
                测试社区2-203
                雀巢咖啡食品旗舰店
                旗舰店
                回头客好店！
                NESCAFE
                百亿补贴
                品牌 雀巢咖啡
                【8瓶】雀
                ￥31.49
                巢咖啡即饮咖啡丝滑拿铁268m..
                x1
                丝滑拿铁268ML*7瓶+1瓶摩卡
                降价补差>
                丝滑拿铁即饮咖啡
                分享商品
                联系商家
                申请退款
                补贴后共优惠￥5.35
                实付：￥26.14（免运费）
                已购规格
                丝滑拿铁268ML*7瓶+1瓶摩卡
                该规格补贴价
                ￥31.49
                已购数量
                1
                合计补贴价
                ￥31.49
                平台优惠
                -￥5.35
                订单号：TEST-ORDER-NO丨复制
                商品快照：发生交易争议时，可作为判断依据>
                支付方式：支付宝
                物流公司：申通快递
                快递单号：TEST-EXPRESS-NO丨复制
                下单时间：2026-05-22 13:09:39
                拼单时间：2026-05-22 13:35:23
                发货时间：2026-05-23 08:12:41
                成交时间：2026-06-02 08:12:41
                更多
                查看物流
                追加评价
                再次拼单
                """;

        ParsedPurchaseCandidate candidate = parser.parse(rawText, "jtxw", null, null).get(0);

        assertThat(candidate.productName()).isNotNull();
        assertThat(candidate.productName()).doesNotContain("为了补偿", "门槛券");
        assertThat(candidate.productName()).contains("咖啡");
        assertThat(candidate.sku()).isEqualTo("丝滑拿铁268ML*7瓶+1瓶摩卡");
        assertThat(candidate.price()).isEqualTo(26.14D);
        assertThat(candidate.quantity()).isEqualTo(2144D);
        assertThat(candidate.unit()).isEqualTo("ml");
        assertThat(candidate.shopName()).isEqualTo("雀巢咖啡食品旗舰店");
        assertThat(candidate.purchaseDate()).isEqualTo("2026-05-22");
    }

    @Test
    void shouldParseSanitizedTmallOcrFixture() {
        String rawText = """
                05:50
                仓库已发货
                还剩5天13小时自动确认
                天猫超市
                天猫超市
                直播中>
                88VIP好评率98%，平均5小时退款
                大颗粒减少带砂
                EverClean铂钻猫砂原装进√￥45.82>
                EVER
                新配
                CLEAN
                【新品升级】金标微香10L-减少
                ￥198
                30%
                ?
                大促价保 破损包退 上门换新>
                X1
                加入购物车
                申请售后
                商品总价共1件
                ￥198
                店铺优惠
                -￥86
                平台及达人优惠
                红包已抵7.19元
                -¥66.18
                实付款
                ￥45.82~
                订单信息2026-06-15
                收起
                订单编号
                TEST-ORDER-NO|复制
                交易快照
                发生交易争议时，可作为判断依据>
                发货时间
                2026-06-15 19:21:09
                付款时间
                2026-06-15 18:13:30
                创建时间
                2026-06-15 18:13:20
                支付宝交易号
                TEST-ALIPAY-TRADE-NO
                催促配送
                查看物流
                确认收货
                客服
                更多
                """;

        ParsedPurchaseCandidate candidate = parser.parse(rawText, "jtxw", null, null).get(0);

        assertThat(candidate.productName()).isEqualTo("EverClean铂钻猫砂原装进");
        assertThat(candidate.productName()).doesNotContain("红包", "店铺优惠", "￥45.82");
        assertThat(candidate.sku()).isEqualTo("【新品升级】金标微香10L-减少");
        assertThat(candidate.price()).isEqualTo(45.82D);
        assertThat(candidate.quantity()).isEqualTo(10D);
        assertThat(candidate.unit()).isEqualTo("L");
        assertThat(candidate.platform()).isEqualTo("taobao");
        assertThat(candidate.purchaseDate()).isEqualTo("2026-06-15");
        assertThat(candidate.shopName()).isEqualTo("天猫超市");
    }

    @Test
    void shouldParsePddWahahaOrderDetailWithoutLogisticsNoise() {
        String rawText = """
                07:34
                交易成功
                [收货信息已隐藏]
                百亿补贴·娃哈哈 品牌
                百亿补贴 品牌 娃哈哈纯净水596ml*24瓶整箱家庭饮用水旅游... ¥25.73 ×1
                娃哈哈纯净水596ml*24瓶【纸箱.塑模随机】
                降价补差
                分享商品 联系商家 申请退款
                补贴后共优惠¥6.83 实付: ¥18.9 (免运费)
                已购规格 娃哈哈纯净水596ml*24瓶【纸箱.塑模随机】
                该规格补贴价 ¥25.73
                已购数量 1
                合计补贴价 ¥25.73
                平台优惠 -¥6.83
                订单编号：[编号已隐藏] 复制
                支付方式：支付宝
                物流公司：申通快递
                快递单号：[编号已隐藏] 复制
                下单时间：2026-06-04 11:57:17
                更多 查看物流 再次拼单 立即评价
                """;

        ParsedPurchaseCandidate candidate = parser.parse(rawText, "jtxw", null, null).get(0);

        assertThat(candidate.productName()).contains("娃哈哈纯净水596ml*24瓶");
        assertThat(candidate.productName()).doesNotContain("您的快件已取走");
        assertThat(candidate.productName()).doesNotContain("交易成功");
        assertThat(candidate.productName()).doesNotContain("订单编号");
        assertThat(candidate.productName()).doesNotContain("¥25.73");
        assertThat(candidate.sku()).isEqualTo("娃哈哈纯净水596ml*24瓶【纸箱.塑模随机】");
        assertThat(candidate.price()).isEqualTo(18.9D);
        assertThat(candidate.platform()).isEqualTo("pdd");
        assertThat(candidate.purchaseDate()).isEqualTo("2026-06-04");
    }

    @Test
    void shouldParsePddHushubaoOrderDetailWithPurchasedSpec() {
        String rawText = """
                08:06
                交易成功
                [收货信息已隐藏]
                百亿补贴·护舒宝 品牌
                百亿补贴 品牌 护舒宝液体卫生巾防漏安睡裤夜用安心裤女... ¥21.86 ×1
                防漏安睡裤L码*10条
                退货包运费保障中
                补贴后共优惠¥4.96 实付¥16.9（免运费）
                已购规格 防漏安睡裤L码*10条
                该规格补贴价 ¥21.86
                已购数量 1
                平台优惠 -¥4.96
                下单时间：2026-06-05 09:08:07
                更多 查看物流 再次拼单 立即评价
                """;

        ParsedPurchaseCandidate candidate = parser.parse(rawText, "jtxw", null, null).get(0);

        assertThat(candidate.productName()).contains("护舒宝液体卫生巾防漏安睡裤");
        assertThat(candidate.productName()).doesNotContain("交易成功", "¥21.86");
        assertThat(candidate.sku()).isEqualTo("防漏安睡裤L码*10条");
        assertThat(candidate.price()).isEqualTo(16.9D);
        assertThat(candidate.platform()).isEqualTo("pdd");
        assertThat(candidate.purchaseDate()).isEqualTo("2026-06-05");
    }

    @Test
    void shouldPreferLabeledOrderDateOverLogisticsDate() {
        ParsedPurchaseCandidate candidate = parser.parse("""
                您的快件已取走【合成驿站】
                2026-06-07 12:08:34
                商品名称：合成测试纸巾
                规格：100抽
                实付：12.50元
                下单时间：2026-06-04 11:57:17
                """, "jtxw", null, null).get(0);

        assertThat(candidate.purchaseDate()).isEqualTo("2026-06-04");
    }

    @Test
    void shouldNotDetectPddOnlyByConfirmReceiptButton() {
        ParsedPurchaseCandidate candidate = parser.parse("""
                商品名称：合成测试纸巾
                规格：100抽
                实付：12.50元
                确认收货
                """, "jtxw", null, null).get(0);

        assertThat(candidate.platform()).isNull();
    }

    @Test
    void shouldMergeTmallProductTitleFragments() {
        String rawText = """
                06:21
                仓库已发货
                还剩5天12小时自动确认
                天猫超市
                直播中>
                天猫超市
                88VIP好评率98%，平均5小时退款
                大颗粒减少带砂
                EverClean铂钻猫砂原装进
                #货#30秒
                NEW!
                EVER
                新配方
                OL
                口膨润土矿砂除臭低尘净
                ￥45.82>
                CLEAN
                30%
                ®
                味速凝
                【新品升级】金标微香10L-减少
                ￥198
                大促价保破损包退上门换新>
                X1
                加入购物车
                申请售后
                商品总价共1件
                ￥198
                店铺优惠
                -￥86
                平台及达人优惠
                红包已抵7.19元
                -¥66.18
                ￥45.82~
                实付款
                订单信息2026-06-15
                收起
                订单编号
                TEST-ORDER-NO|复制
                交易快照
                发生交易争议时，可作为判断依据
                发货时间
                2026-06-15 19:21:09
                付款时间
                2026-06-15 18:13:30
                创建时间
                2026-06-15 18:13:20
                催促配送
                查看物流
                确认收货
                客服
                更多
                """;

        ParsedPurchaseCandidate candidate = parser.parse(rawText, "jtxw", null, null).get(0);

        assertThat(candidate.productName())
                .contains("EverClean", "猫砂", "进口", "膨润土", "除臭", "低尘", "味速凝")
                .doesNotContain("NEW", "CLEAN", "30%", "￥45.82", "#货", "新配方", "OL");
        assertThat(candidate.sku()).isEqualTo("【新品升级】金标微香10L-减少");
        assertThat(candidate.price()).isEqualTo(45.82D);
        assertThat(candidate.quantity()).isEqualTo(10D);
        assertThat(candidate.unit()).isEqualTo("L");
        assertThat(candidate.platform()).isEqualTo("taobao");
        assertThat(candidate.purchaseDate()).isEqualTo("2026-06-15");
        assertThat(candidate.shopName()).isEqualTo("天猫超市");
    }

    @Test
    void shouldIgnoreShopDiscountWhenFindingShopName() {
        ParsedPurchaseCandidate candidate = parser.parse("""
                天猫超市
                商品总价共1件
                店铺优惠
                平台及达人优惠
                实付款
                """, "jtxw", null, null).get(0);

        assertThat(candidate.shopName()).isEqualTo("天猫超市");
        assertThat(candidate.shopName()).isNotEqualTo("店铺优惠");
    }

    @Test
    void shouldFallbackToNearbySkuWithoutLabel() {
        ParsedPurchaseCandidate candidate = parser.parse("""
                天猫超市
                EverClean铂钻猫砂原装进√￥45.82>
                【新品升级】金标微香10L-减少
                实付款
                ￥45.82
                订单信息2026-06-15
                """, "jtxw", null, null).get(0);

        assertThat(candidate.productName()).isEqualTo("EverClean铂钻猫砂原装进");
        assertThat(candidate.sku()).isEqualTo("【新品升级】金标微香10L-减少");
        assertThat(candidate.quantity()).isEqualTo(10D);
        assertThat(candidate.unit()).isEqualTo("L");
    }

    @Test
    void shouldCleanTrailingPriceFromTmallSkuCandidate() {
        ParsedPurchaseCandidate candidate = parser.parse("""
                天猫超市                直播中 >
                EverClean铂钻猫砂原装进口膨润土矿砂除臭低尘净味速凝                ¥45.82
                【新品升级】金标微香10L-减少...                ¥198
                商品总价 共1件                ¥198
                店铺优惠                -¥86
                实付款                ¥45.82
                订单信息 2026-06-15
                """, "jtxw", null, null).get(0);

        assertThat(candidate.sku()).isEqualTo("【新品升级】金标微香10L-减少...");
        assertThat(candidate.sku()).doesNotContain("¥198");
        assertThat(candidate.quantity()).isEqualTo(10D);
        assertThat(candidate.unit()).isEqualTo("L");
        assertThat(candidate.price()).isEqualTo(45.82D);
    }

    @ParameterizedTest
    @MethodSource("trailingSkuPriceCases")
    void shouldCleanTrailingCurrencyVariantsFromSku(String specification, String expectedSku) {
        ParsedPurchaseCandidate candidate = parser.parse(
                "已购规格：" + specification + "\n实付款：￥45.82", "jtxw", null, null).get(0);

        assertThat(candidate.sku()).isEqualTo(expectedSku);
    }

    @Test
    void shouldKeepValidSpecificationNumbersWhenSkuHasNoTrailingPrice() {
        String specification = "丝滑拿铁268ML*7瓶+1瓶摩卡";

        ParsedPurchaseCandidate candidate = parser.parse(
                "已购规格：" + specification + "\n实付款：￥26.14", "jtxw", null, null).get(0);

        assertThat(candidate.sku()).isEqualTo(specification);
    }

    @ParameterizedTest
    @MethodSource("nextLineSkuCases")
    void shouldParseSkuFromNextLineAfterLabel(String label, String specification) {
        String rawText = label + "\n" + specification + "\n实付：￥26.14";

        ParsedPurchaseCandidate candidate = parser.parse(rawText, "jtxw", null, null).get(0);

        assertThat(candidate.sku()).isEqualTo(specification);
    }

    @ParameterizedTest
    @MethodSource("bonusPackageCases")
    void shouldParseBonusPackageQuantity(String specification, double expectedQuantity, String expectedUnit) {
        ParsedPurchaseCandidate candidate = parseSpecification("合成测试商品", specification);

        assertThat(candidate.quantity()).isEqualTo(expectedQuantity);
        assertThat(candidate.unit()).isEqualTo(expectedUnit);
    }

    @Test
    void shouldDetectPlatformFromRawText() {
        ParsedPurchaseCandidate candidate = parsePddCandidate(null, null);

        assertThat(candidate.platform()).isEqualTo("pdd");
    }

    @Test
    void shouldPreferRequestedPlatform() {
        ParsedPurchaseCandidate candidate = parsePddCandidate("offline", null);

        assertThat(candidate.platform()).isEqualTo("offline");
    }

    @Test
    void shouldReturnWarningCandidateWhenRawTextIsBlank() {
        ParsedPurchaseCandidate candidate = parser.parse("   ", null, null, null).get(0);

        assertThat(candidate.productName()).isNull();
        assertThat(candidate.price()).isNull();
        assertThat(candidate.quantity()).isNull();
        assertThat(candidate.unit()).isNull();
        assertThat(candidate.warnings()).contains(
                "未识别到商品名称",
                "未识别到实付金额",
                "未识别到明确数量和单位"
        );
    }

    @Test
    void shouldReturnWarningCandidateWhenOnlySystemLinesExist() {
        ParsedPurchaseCandidate candidate = parser.parse("""
                拼多多
                订单号：123456
                下单时间：2026-06-18 10:00:00
                实付：39.90元
                """, null, null, null).get(0);

        assertThat(candidate.productName()).isNull();
        assertThat(candidate.price()).isEqualTo(39.90D);
        assertThat(candidate.warnings()).contains("未识别到商品名称");
    }

    @Test
    void shouldNotTruncateDecimalPackageMultiplier() {
        ParsedPurchaseCandidate candidate = parseSpecification("合成测试商品", "60g*2.5袋");

        assertThat(candidate.quantity()).isNull();
        assertThat(candidate.unit()).isNull();
        assertThat(candidate.warnings()).contains("未识别到明确数量和单位");
    }

    @ParameterizedTest
    @MethodSource("unitBeforeMultiplierCases")
    void shouldParseMultiplicationQuantityWithUnitBeforeMultiplier(String specification,
                                                                  double expectedQuantity,
                                                                  String expectedUnit) {
        ParsedPurchaseCandidate candidate = parseSpecification("合成测试商品", specification);

        assertThat(candidate.quantity()).isEqualTo(expectedQuantity);
        assertThat(candidate.unit()).isEqualTo(expectedUnit);
    }

    @ParameterizedTest
    @MethodSource("packageBeforeUnitCases")
    void shouldParsePackageCountBeforePerUnitSpec(String specification,
                                                 double expectedQuantity,
                                                 String expectedUnit) {
        ParsedPurchaseCandidate candidate = parseSpecification("合成测试商品", specification);

        assertThat(candidate.quantity()).isEqualTo(expectedQuantity);
        assertThat(candidate.unit()).isEqualTo(expectedUnit);
    }

    @ParameterizedTest
    @MethodSource("packageOnlyCases")
    void shouldNotTreatPackageOnlyAsQuantity(String specification) {
        ParsedPurchaseCandidate candidate = parseSpecification("合成测试商品", specification);

        assertThat(candidate.quantity()).isNull();
        assertThat(candidate.unit()).isNull();
        assertThat(candidate.warnings()).contains("未识别到明确数量和单位");
    }

    @Test
    void shouldFallbackToProductNameWhenSkuHasNoQuantity() {
        ParsedPurchaseCandidate candidate = parseSpecification("舒肤佳沐浴露720ml", "红石榴味");

        assertThat(candidate.quantity()).isEqualTo(720D);
        assertThat(candidate.unit()).isEqualTo("ml");
    }

    @Test
    void shouldPreferSkuQuantityWhenSkuHasQuantity() {
        ParsedPurchaseCandidate candidate = parseSpecification(
                "舒肤佳沐浴露720ml", "红石榴啫喱沐浴露380g+400g");

        assertThat(candidate.quantity()).isEqualTo(780D);
        assertThat(candidate.unit()).isEqualTo("g");
    }

    @Test
    void shouldNotUseProductTitlePackSpecWhenSkuPackageCountConflicts() {
        ParsedPurchaseCandidate candidate = parseSpecification(
                "猫湿粮 70g*10包", "混合口味;3包[试吃装]");

        assertThat(candidate.quantity()).isNull();
        assertThat(candidate.unit()).isNull();
        assertThat(candidate.warnings())
                .contains("SKU 包装数量与商品标题规格数量不一致，需用户确认", "未识别到明确数量和单位");
    }

    @Test
    void shouldFallbackWhenSkuPackageCountMatchesProductTitle() {
        ParsedPurchaseCandidate candidate = parseSpecification(
                "猫湿粮 70g*10包", "10包[混合口味]");

        assertThat(candidate.quantity()).isEqualTo(700D);
        assertThat(candidate.unit()).isEqualTo("g");
    }

    @Test
    void shouldNotAddDifferentUnitsWithoutExplicitConversion() {
        ParsedPurchaseCandidate candidate = parseSpecification("合成测试商品", "1kg+500g");

        assertThat(candidate.quantity()).isNull();
        assertThat(candidate.unit()).isNull();
        assertThat(candidate.warnings()).contains("未识别到明确数量和单位");
    }

    @Test
    void shouldReturnMultipleCandidatesWithoutAllocatingPrice() {
        List<ParsedPurchaseCandidate> candidates = parser.parse("""
                商品名称：合成商品甲
                商品名称：合成商品乙
                实付：30元
                """, null, "pdd", null);

        assertThat(candidates).hasSize(2);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.price()).isNull();
            assertThat(candidate.warnings()).contains("多商品截图无法可靠分摊实付金额");
        });
    }

    /**
     * 使用合成拼多多订单文本解析首个候选样本，验证平台和日期透传。
     */
    private ParsedPurchaseCandidate parsePddCandidate(String platform, String purchaseDate) {
        String rawText = """
                拼多多
                宝洁官方旗舰店
                舒肤佳红石榴沐浴露
                规格：红石榴啫喱沐浴露380g+400g
                实付 ¥35.22
                下单时间：2024-06-27 10:20:00
                """;
        return parser.parse(rawText, "jtxw", platform, purchaseDate).get(0);
    }

    /**
     * 使用指定商品名称和规格文本构造合成 OCR 原文并解析首个候选样本。
     */
    private ParsedPurchaseCandidate parseSpecification(String productName, String specification) {
        String rawText = "商品名称：" + productName + "\n规格：" + specification + "\n实付：39.90元";
        return parser.parse(rawText, "jtxw", null, null).get(0);
    }

    /**
     * 提供"单份规格在前、包装乘数在后"的数量解析测试用例。
     */
    private static Stream<Arguments> unitBeforeMultiplierCases() {
        return Stream.of(
                Arguments.of("2.5kg*8", 20D, "kg"),
                Arguments.of("60g*10袋", 600D, "g"),
                Arguments.of("500ml×2瓶", 1000D, "ml"),
                Arguments.of("100抽X3包", 300D, "抽"),
                Arguments.of("10片装*2", 20D, "片")
        );
    }

    /**
     * 提供规格标签在下一行的跨行 SKU 解析测试用例。
     */
    private static Stream<Arguments> nextLineSkuCases() {
        return Stream.of(
                Arguments.of("已购规格", "丝滑拿铁268ML*7瓶+1瓶摩卡"),
                Arguments.of("商品规格", "100抽*3包")
        );
    }

    /**
     * 提供规格文本尾部附着价格符号的清理测试用例。
     */
    private static Stream<Arguments> trailingSkuPriceCases() {
        return Stream.of(
                Arguments.of("【新品升级】金标微香10L-减少... ￥198.00", "【新品升级】金标微香10L-减少..."),
                Arguments.of("【新品升级】金标微香10L-减少... ¥198", "【新品升级】金标微香10L-减少..."),
                Arguments.of("【新品升级】金标微香10L-减少... -¥66.18", "【新品升级】金标微香10L-减少...")
        );
    }

    /**
     * 提供"基础包装数加赠送包装数"组合装数量解析测试用例。
     */
    private static Stream<Arguments> bonusPackageCases() {
        return Stream.of(
                Arguments.of("268ML*7瓶+1瓶摩卡", 2144D, "ml"),
                Arguments.of("268ml×7瓶+1瓶", 2144D, "ml"),
                Arguments.of("70g*10包+2包", 840D, "g"),
                Arguments.of("250ml*12盒+1盒", 3250D, "ml"),
                Arguments.of("100抽*3包+1包", 400D, "抽")
        );
    }

    /**
     * 提供"包装数在前、单份规格在后"的数量解析测试用例。
     */
    private static Stream<Arguments> packageBeforeUnitCases() {
        return Stream.of(
                Arguments.of("3包*100抽/包", 300D, "抽"),
                Arguments.of("2瓶×500ml/瓶", 1000D, "ml"),
                Arguments.of("10袋x60g/袋", 600D, "g")
        );
    }

    /**
     * 提供仅有包装数但无明确单份规格的测试用例。
     */
    private static Stream<Arguments> packageOnlyCases() {
        return Stream.of(
                Arguments.of("3包"),
                Arguments.of("2盒"),
                Arguments.of("1件")
        );
    }
}
