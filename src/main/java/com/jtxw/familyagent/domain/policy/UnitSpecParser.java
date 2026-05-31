package com.jtxw.familyagent.domain.policy;


/**
 * @Author: jtxw
 * @Date: 2026/05/31/11:45
 * @Description: 按单位族解析商品规格的策略接口。
 */
public interface UnitSpecParser {
    boolean supports(UnitFamily unitFamily);

    ProductSpecParseResult parse(String sku, String productName);
}
