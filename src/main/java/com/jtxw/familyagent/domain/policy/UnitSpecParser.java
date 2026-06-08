package com.jtxw.familyagent.domain.policy;

/**
 * @Author: jtxw
 * @Date: 2026/05/31/11:45
 * @Description: 按单位族解析商品规格的策略接口。
 */
public interface UnitSpecParser {

    /**
     * 判断当前解析器是否支持指定单位族的规格解析。
     *
     * @param unitFamily 目标单位族，例如 WEIGHT、VOLUME、COUNT、PIECE
     * @return {@code true} 表示当前解析器可以尝试解析，{@code false} 表示跳过该解析器
     */
    boolean supports(UnitFamily unitFamily);

    /**
     * 从商品名称和 SKU 中解析目标规格。
     *
     * <p>该方法只负责规格解析，不直接写数据库，不修改原始记录。
     * 返回 {@link ProductSpecParseResult}，由上层服务根据结果决定是否覆盖数量/单位/单价，或创建人工复核。</p>
     *
     * @param sku         商品规格或 SKU，允许为空
     * @param productName 原始商品名称或归一化后的商品名称
     * @return 规格解析结果，包含解析后的数量、单位和置信度等信息
     */
    ProductSpecParseResult parse(String sku, String productName);
}
