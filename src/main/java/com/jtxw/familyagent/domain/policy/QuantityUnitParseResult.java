package com.jtxw.familyagent.domain.policy;

/**
 * @Author: jtxw
 * @Date: 2026/06/04/19:39
 * @Description: 商品规格数量解析结果，携带标准数量、单位、单价、证据和复核标记。
 */
public record QuantityUnitParseResult(
        /**
         * 按标准单位折算后的总数量，例如 4条*10包+2条*1包 => 42。
         */
        Double quantity,
        /**
         * 价格比较使用的单位，例如“条”“片”。
         */
        String unit,
        /**
         * 当前实付金额除以解析后数量得到的单位价格。
         */
        Double unitPrice,
        /**
         * 解析置信度，主要用于复核解释。
         */
        double confidence,
        /**
         * 命中的规格文本证据，例如 sku:4条*10包+2条*1包。
         */
        String parseEvidence,
        /**
         * 是否需要人工复核。
         */
        boolean needReview
) {
}
