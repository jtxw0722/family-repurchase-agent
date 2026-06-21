package com.jtxw.familyagent.infrastructure.ocr;

import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 22:48:00
 * @Description: 订单截图 OCR 文本规则解析器测试，覆盖单商品核心字段、优先级和缺失信息警告
 */
class OrderImageTextParserTest {
    /** 待测试的无状态 OCR 文本规则解析器。 */
    private final OrderImageTextParser parser = new OrderImageTextParser();

    @Test
    void shouldParseSingleProductFromPddLikeText() {
        ParsedPurchaseCandidate candidate = parsePddCandidate(null, null);

        assertThat(candidate.productName()).isEqualTo("舒肤佳红石榴沐浴露");
        assertThat(candidate.sku()).isEqualTo("红石榴啫喱沐浴露380g+400g");
        assertThat(candidate.price()).isEqualTo(35.22D);
        assertThat(candidate.quantity()).isEqualTo(780D);
        assertThat(candidate.unit()).isEqualTo("g");
        assertThat(candidate.shopName()).isEqualTo("宝洁官方旗舰店");
        assertThat(candidate.purchaseDate()).isEqualTo("2024-06-27 10:20:00");
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
        assertThat(candidate.purchaseDate()).isEqualTo("2026-05-22 13:09:39");
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
        assertThat(candidate.platform()).isEqualTo("tmall");
        assertThat(candidate.purchaseDate()).isEqualTo("2026-06-15");
        assertThat(candidate.shopName()).isEqualTo("天猫超市");
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
        assertThat(candidate.platform()).isEqualTo("tmall");
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
