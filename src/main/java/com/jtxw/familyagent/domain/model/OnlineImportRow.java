package com.jtxw.familyagent.domain.model;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/11/15 12:51:46
 * @Description: 在线订单预览及确认的字段白名单，不包含文件名、订单号、地址、电话、物流或原始行文本
 * @param rowNumber 文件行号，仅用于预览定位，不保存
 * @param productName 商品名称，必填
 * @param normalizedName 归一化名称，允许用户修正
 * @param sku 商品规格，允许为空
 * @param amount 实付总金额，单位元，允许零元赠品
 * @param quantity 标准总含量，不是购买件数，必须大于零
 * @param unit 总含量计量单位，必填
 * @param platform 平台标识，必填
 * @param purchaseDate 购买日期或时间，必填
 * @param owner 用户指定的家庭归属人，不从收货人字段提取
 * @param shopName 店铺名，允许为空
 * @param selected 是否勾选，默认仅勾选无异常且非赠品、非重复行
 * @param warnings 当前校验提示，确认入库时不信任客户端的提示
 */
public record OnlineImportRow(int rowNumber, String productName, String normalizedName, String sku,
                             Double amount, Double quantity, String unit, String platform,
                             String purchaseDate, String owner, String shopName,
                             boolean selected, List<String> warnings) {
}
