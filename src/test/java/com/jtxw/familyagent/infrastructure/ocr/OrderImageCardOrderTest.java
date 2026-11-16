package com.jtxw.familyagent.infrastructure.ocr;

import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/11/14 22:31:14
 * @Description: 订单截图解析回归测试，验证缩略图干扰、跨价格行的规格续行和次卡总权益的保守计算
 */
class OrderImageCardOrderTest {
    /** 实际 OCR 商品区域结构，订单编号及时间使用合成数据，不包含收货信息。 */
    private static final String CARD_ORDER = """
            交易成功
            天猫测试猫砂旗舰店
            直播
            测试猫砂卡-6次卡
            预售
            【李佳琦直播间爆品√￥440.1>
            植物猫砂2.5kg*4包*6次
            6次卡
            测试牌
            原味植物猫砂6次卡[（2.5kg*4包
            ￥636
            *6次）]
            88VIP退货包运费大促价保>
            X1
            闲鱼转卖
            申请售后
            实付款 (包含定金实付款)
            ￥440.1
            订单编号
            1234567890123456789
            创建时间
            2026-11-01 00:27:14
            """;
    /** 被测规则解析器，不调用外部模型或真实订单数据库。 */
    private final OrderImageTextParser parser = new OrderImageTextParser();

    /** 规格从实际副标题起取，跨越单独原价保留完整乘数，营销标题不冒充商品名。 */
    @ParameterizedTest
    @ValueSource(strings = {"taobao", "tmall", "jd", "pdd"})
    void shouldRecoverCardSpecificationWithoutInventingProductTitle(String platform) {
        ParsedPurchaseCandidate candidate = parser.parse(CARD_ORDER, "test-owner", platform, null).get(0);
        assertThat(candidate.productName()).isNull();
        assertThat(candidate.sku()).isEqualTo("原味植物猫砂6次卡[（2.5kg*4包*6次）]");
        assertThat(candidate.quantity()).isEqualTo(60D);
        assertThat(candidate.unit()).isEqualTo("kg");
        assertThat(candidate.purchaseQuantity()).isEqualTo(1D);
        assertThat(candidate.price()).isEqualTo(440.1D);
        assertThat(candidate.warnings()).anyMatch(value -> value.contains("商品标题未完整显示"));
        assertThat(candidate.warnings()).anyMatch(value -> value.contains("确认实付金额对应整张卡"));
    }

    /** 展开后的正常商品主体标题必须保留，不能因包含完整促销前缀而清空。 */
    @Test
    void shouldKeepExpandedProductTitle() {
        var result = parse("商品名称：【李佳琦直播间爆品】原味植物猫砂\n规格：原味6次卡（2.5kg*4包*6次）\n×2\n实付：880.2元");
        assertThat(result.productName()).contains("原味植物猫砂");
        assertThat(result.purchaseQuantity()).isEqualTo(2D);
        assertThat(result.quantity()).isEqualTo(60D);
    }

    /** 仅有卡名、次数冲突或多个乘式时，不能默认为单次含量或把卡次数重复相乘。 */
    @ParameterizedTest
    @ValueSource(strings = {"6次卡（2.5kg*4包）", "6次卡（2.5kg*4包*5次）",
            "6次卡（2.5kg*4包*6次）另有5kg*1包*6次", "6次卡（2.5kg*0包*6次）"})
    void shouldRequireReviewForIncompleteOrConflictingCard(String sku) {
        var result = parse("商品名称：测试猫砂\n规格：" + sku + "\n×1\n实付：440.1元");
        assertThat(result.quantity()).isNull();
        assertThat(result.warnings()).anyMatch(value -> value.contains("次卡"));
    }

    /** 普通六包商品及仅出现使用次数说明的包装不能套用次卡权益规则。 */
    @Test
    void shouldPreserveOrdinaryMultipackQuantity() {
        var result = parse("商品名称：普通猫砂\n规格：2.5kg*6包\n×1\n实付：99元");
        assertThat(result.quantity()).isEqualTo(15D);
        assertThat(result.warnings()).noneMatch(value -> value.contains("次卡"));
    }

    /** 没有未闭合括号时不能跨过商品价误并后续乘数。 */
    @Test
    void shouldNotBridgePriceWithoutOpenBracket() {
        var result = parse("商品名称：普通猫砂\n规格：原味2.5kg*4包\n￥100\n*6次）\n×1\n实付：99元");
        assertThat(result.sku()).isEqualTo("原味2.5kg*4包");
        assertThat(result.quantity()).isEqualTo(10D);
    }

    /** 创建合成文本候选，不使用平台专属布局。 */
    private ParsedPurchaseCandidate parse(String text) {
        return parser.parse(text, "test-owner", null, null).get(0);
    }
}
