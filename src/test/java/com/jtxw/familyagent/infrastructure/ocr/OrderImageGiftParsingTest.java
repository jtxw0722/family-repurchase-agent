package com.jtxw.familyagent.infrastructure.ocr;

import com.jtxw.familyagent.domain.model.ParsedNormalizationPreview;
import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @Author: jtxw
 * @Date: 2026/11/10 12:55:53
 * @Description: 订单截图赠品场景回归测试，验证主商品隔离、天猫平台归属、预售标题和精简备注
 */
class OrderImageGiftParsingTest {
    /** 已移除订单编号的真实 OCR 主商品区块，保留包装图和预售徽标交错造成的文本噪声。 */
    private static final String MAIN_ORDER_TEXT = """
            交易成功
            天猫纯福旗舰店
            直播
            CHN.纯福·天港双
            【双11预售】纯福烘
            旗舰营养
            超凡守护
            焙全价全期猫粮鸡
            服货布
            预售
            ￥607>
            肉幼猫囤货装鲜肉
            烘焙
            鸡肉味；7.5kg[【5包囤货装】]
            ￥607
            88VIP退货包运费大促价保>
            x1
            闲鱼转卖
            申请售后
            赠品
            6件
            实付款（包含定金实付款）
            ￥453.65明细
            优惠解析
            Ai帮你一键解析优惠明细
            订单信息2026-10-15
            收起
            """;
    /** 赠品弹窗 OCR 区块，两个赠品的份数合计为 6 件，金额均为零。 */
    private static final String GIFT_POPUP_TEXT = """
            赠品
            【赠品】纯福全价主食猫条鸡肉羊奶配方￥0
            12g*4支
            x2
            全价主食强条
            【赠品】猫主食餐包50g*1包
            ￥0
            X4
            全价强用主食餐包
            112
            """;
    /** 仅解析规则，不调用真实模型、OCR 引擎或数据库。 */
    private final OrderImageTextParser parser = new OrderImageTextParser();

    /** 验证普通详情页的标题和规格不会被包装图文字、按钮和赠品汇总行替代。 */
    @Test
    void shouldParsePresaleTitleAndFlavorSpecification() {
        ParsedPurchaseCandidate candidate = parser.parse(MAIN_ORDER_TEXT, "jtxw", null, null).get(0);

        assertMainProduct(candidate);
        assertThat(candidate.giftDetails()).isEqualTo("共6件（详情未展开）");
        assertThat(candidate.note()).isEqualTo("【OCR解析】-【未归一化】；赠品：共6件（详情未展开）");
    }

    /** 验证弹窗赠品不会替代主商品，也不会改变主商品的金额、重量和购买数量。 */
    @Test
    void shouldKeepMainProductWhenGiftPopupIsExpanded() {
        var candidates = parser.parse(MAIN_ORDER_TEXT + GIFT_POPUP_TEXT, "jtxw", null, null);

        assertThat(candidates).hasSize(1);
        ParsedPurchaseCandidate candidate = candidates.get(0);
        assertMainProduct(candidate);
        assertThat(candidate.giftDetails()).isEqualTo(
                "纯福全价主食猫条鸡肉羊奶配方12g*4支 ×2；猫主食餐包50g*1包 ×4");
        assertThat(candidate.note()).isEqualTo("【OCR解析】-【未归一化】；赠品：" + candidate.giftDetails());
        assertThat(candidate.note()).doesNotContain("强条", "主食餐包112", "stderr", "归一化规则", "￥0");
    }

    /** 验证明确平台别名天猫归属淘宝，不能只在前端把显示文字改为淘宝。 */
    @ParameterizedTest
    @ValueSource(strings = {"tmall", "TMALL", "天猫", "淘宝"})
    void shouldNormalizeTmallAliasesToTaobao(String platform) {
        assertThat(parser.parse("商品名称：合成测试商品\n规格：5kg\nx1\n实付：10元",
                "jtxw", platform, null).get(0).platform()).isEqualTo("taobao");
    }

    /** 赠品可以在实付款之前展开，必须保留后续订单金额和日期。 */
    @ParameterizedTest
    @ValueSource(strings = {"pdd", "taobao", "jd", ""})
    void shouldKeepOrderFieldsAfterInlineGiftAcrossPlatforms(String platform) {
        ParsedPurchaseCandidate candidate = parser.parse("""
                商品名称：合成测试大米
                商品规格：5kg*1袋
                ￥49.9 ×2
                赠品：合成咖啡
                商品规格：12g*4支
                ￥0
                ×3
                实付款：79.8元
                下单时间：2026-11-08
                """, "jtxw", platform, null).get(0);

        assertThat(candidate.productName()).isEqualTo("合成测试大米");
        assertThat(candidate.sku()).isEqualTo("5kg*1袋");
        assertThat(candidate.quantity()).isEqualTo(5D);
        assertThat(candidate.purchaseQuantity()).isEqualTo(2D);
        assertThat(candidate.price()).isEqualTo(79.8D);
        assertThat(candidate.purchaseDate()).isEqualTo("2026-11-08");
        assertThat(candidate.giftDetails()).isEqualTo("合成咖啡12g*4支 ×3");
    }

    /** 截图只有赠品时应提示补充主订单，不得生成零元赠品对应的已付款价格样本。 */
    @Test
    void shouldNotTreatGiftOnlyScreenshotAsPaidProduct() {
        ParsedPurchaseCandidate candidate = parser.parse(GIFT_POPUP_TEXT, "jtxw", null, null).get(0);

        assertThat(candidate.productName()).isNull();
        assertThat(candidate.price()).isNull();
        assertThat(candidate.quantity()).isNull();
        assertThat(candidate.purchaseQuantity()).isNull();
        assertThat(candidate.giftDetails()).contains("12g*4支 ×2", "50g*1包 ×4");
    }

    /** 兼容赠品数量与价格同行的文本，数量之后的包装文字不拼入备注。 */
    @ParameterizedTest
    @ValueSource(strings = {"【赠品】合成猫条12g*4支 ￥0 ×2\n包装图广告文字",
            "商品名称：[赠品]合成猫条\n规格：12g*4支 ￥0 ×2\n包装图广告文字",
            "赠品：合成猫条12g*4支\n数量：2\n包装图广告文字"})
    void shouldReadGiftCountInlineWithoutCopyingPackagingText(String giftText) {
        ParsedPurchaseCandidate candidate = parser.parse("商品名称：合成猫粮\n规格：7.5kg\n×1\n实付：453.65元\n"
                + giftText, "jtxw", null, null).get(0);

        assertThat(candidate.giftDetails()).isEqualTo("合成猫条12g*4支 ×2");
        assertThat(candidate.price()).isEqualTo(453.65D);
        assertThat(candidate.purchaseQuantity()).isEqualTo(1D);
    }

    /** 无赠品时备注仅保留来源和状态，命中预览后只更新归一化状态，不追加技术解释。 */
    @Test
    void shouldKeepCompactNoteAndUpdateNormalizationStatus() {
        ParsedPurchaseCandidate candidate = parser.parse(
                "商品名称：合成猫粮\n规格：7.5kg\n×1\n实付：453.65元", "jtxw", null, null).get(0);

        assertThat(candidate.note()).isEqualTo("【OCR解析】-【未归一化】");
        assertThat(candidate.withNormalization(matchedPreview()).note()).isEqualTo("【OCR解析】-【已归一化】");
        ParsedPurchaseCandidate withGifts = parser.parse(MAIN_ORDER_TEXT + GIFT_POPUP_TEXT,
                "jtxw", null, null).get(0).withNormalization(matchedPreview());
        assertThat(withGifts.note()).isEqualTo("【OCR解析】-【已归一化】；赠品：" + withGifts.giftDetails());
        assertThat(withGifts.purchaseQuantity()).isEqualTo(1D);
    }

    /**
     * 校验主商品关键字段，数量与规格分别验证，避免赠品规格污染主商品。
     *
     * @param candidate 已解析的主商品候选
     */
    private void assertMainProduct(ParsedPurchaseCandidate candidate) {
        assertThat(candidate.productName()).isEqualTo("【双11预售】纯福烘焙全价全期猫粮鸡肉幼猫囤货装鲜肉烘焙");
        assertThat(candidate.sku()).isEqualTo("鸡肉味；7.5kg[【5包囤货装】]");
        assertThat(candidate.price()).isEqualTo(453.65D);
        assertThat(candidate.quantity()).isEqualTo(7.5D);
        assertThat(candidate.unit()).isEqualTo("kg");
        assertThat(candidate.purchaseQuantity()).isEqualTo(1D);
        assertThat(candidate.platform()).isEqualTo("taobao");
        assertThat(candidate.shopName()).isEqualTo("纯福旗舰店");
        assertThat(candidate.purchaseDate()).isEqualTo("2026-10-15");
    }

    /**
     * 创建已命中的合成归一化预览，不访问真实规则库。
     *
     * @return 用于备注状态更新的猫粮分类预览
     */
    private ParsedNormalizationPreview matchedPreview() {
        return new ParsedNormalizationPreview(true, "cat_food", "猫粮", "kg", "猫粮", "合成猫粮", null, null);
    }
}
