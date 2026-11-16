package com.jtxw.familyagent.infrastructure.ocr;

import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/11/11 18:47:53
 * @Description: 商品标题回归测试，验证促销汇总隔离、独立品牌徽标和包装图片小字不抢占或污染标题
 */
class OrderImageProductTitleRegressionTest {
    /** 原图 OCR 商品区块，使用合成收货信息；保留真实的品牌、价格和包装图小字顺序。 */
    private static final String CHICKEN_ORDER_TEXT = """
            18:38
            !!! 5GA 134
            交易成功
            合成收货人138****0000测试路1234号后
            已签收
            测试管理处2221
            圣农
            圣农食品官方旗舰店
            官方旗舰
            回头客好店！
            品牌圣农
            秒杀
            圣农琵琶腿5斤冰鲜
            ￥48.8
            圣农
            鸡琶腿
            鸡腿鲜冻高蛋白鸡腿生鲜鸡肉
            X1
            原切琵琶腿2.5KG，高蛋白个大饱满
            3天价保>
            不支持七天无理由>
            坏了包赔
            购后小助手
            查看使用说明书>
            分享商品
            联系商家
            申请退款
            秒杀后共优惠￥4.88
            实付：￥43.92(免运费)
            已购规格
            原切琵琶腿2.5KG，高蛋白个大饱满
            该规格秒杀价
            ￥48.8
            已购数量
            1
            合计秒杀价
            ￥48.8
            平台优惠
            -￥4.88
            下单时间：2026-10-28 21:50:54
            再次拼单
            """;
    /** 被测无状态文本解析器，测试不发起 OCR 或模型网络请求。 */
    private final OrderImageTextParser parser = new OrderImageTextParser();

    @Test
    void shouldReadChickenTitleWithoutBrandBadgeOrPackagingCaptions() {
        ParsedPurchaseCandidate candidate = parse(CHICKEN_ORDER_TEXT, null);
        assertChickenTitle(candidate);
        assertThat(candidate.sku()).isEqualTo("原切琵琶腿2.5KG，高蛋白个大饱满");
        assertThat(candidate.price()).isEqualTo(43.92D);
        assertThat(candidate.purchaseQuantity()).isEqualTo(1D);
        assertThat(candidate.quantity()).isEqualTo(2.5D);
        assertThat(candidate.unit()).isEqualTo("kg");
    }

    @ParameterizedTest
    @ValueSource(strings = {"秒杀", "限时直降", "双11专场", "8.8折", "新活动名称", "补贴", "大促"})
    void shouldNotDependOnPromotionPrefixDictionary(String promotionName) {
        assertChickenTitle(parse(CHICKEN_ORDER_TEXT.replace("秒杀后共优惠", promotionName + "后共优惠"), null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pdd", "taobao", "tmall", "jd", ""})
    void shouldUseBrandAndPriceEvidenceAcrossPlatforms(String platform) {
        assertChickenTitle(parse(CHICKEN_ORDER_TEXT, platform));
    }

    @Test
    void shouldNotSelectUnknownAmountLabelOutsideProductCard() {
        assertChickenTitle(parse(CHICKEN_ORDER_TEXT.replace("秒杀后共优惠￥4.88", "本场新福利￥4.88"), null));
    }

    @Test
    void shouldKeepPromotionWordsInsideActualProductTitle() {
        ParsedPurchaseCandidate candidate = parse(CHICKEN_ORDER_TEXT
                .replace("圣农琵琶腿5斤冰鲜", "圣农优惠装琵琶腿5斤冰鲜"), null);
        assertThat(candidate.productName()).isEqualTo("圣农优惠装琵琶腿5斤冰鲜鸡腿鲜冻高蛋白鸡腿生鲜鸡肉");
    }

    /** 统一使用全家庭默认归属人解析合成订单，不调用外部服务。 */
    private ParsedPurchaseCandidate parse(String text, String platform) {
        return parser.parse(text, "jtxw", platform, null).get(0);
    }

    /** 核对完整可见标题，不能混入品牌徽标、包装图文字或促销汇总。 */
    private void assertChickenTitle(ParsedPurchaseCandidate candidate) {
        assertThat(candidate.productName()).isEqualTo("圣农琵琶腿5斤冰鲜鸡腿鲜冻高蛋白鸡腿生鲜鸡肉");
    }
}
