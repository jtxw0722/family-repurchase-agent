package com.jtxw.familyagent.interfaces.rest.request;


import com.jtxw.familyagent.application.query.ComparePriceQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * @Author: jtxw
 * @Date: 2026/06/08/14:43
 * @Description: 当前价格比较请求参数，属于 interfaces.rest.request 层，对应 /compare-price 接口。
 */

@Schema(description = "当前价格比较请求")
public class ComparePriceRequest {
    /**
     * 原始商品名称
     */
    @Schema(description = "原始商品名称，会在服务端进行本地规则归一化", example = "猫砂", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String productName;
    /**
     * 当前总价
     */
    @Schema(description = "当前购买总价", example = "89", requiredMode = Schema.RequiredMode.REQUIRED)
    @Positive
    private double price;
    /**
     * 当前商品数量
     */
    @Schema(description = "当前购买数量", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
    @Positive
    private double quantity;
    /**
     * 数量单位
     */
    @Schema(description = "数量单位，用于计算单位价格", example = "kg", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String unit;

    public ComparePriceRequest() {
    }

    public String productName() {
        return productName;
    }

    public double price() {
        return price;
    }

    public double quantity() {
        return quantity;
    }

    public String unit() {
        return unit;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public double getQuantity() {
        return quantity;
    }

    public void setQuantity(double quantity) {
        this.quantity = quantity;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    /**
     * 将 REST 请求参数转换为应用层价格比较查询。
     *
     * @return 价格比较查询
     */
    public ComparePriceQuery toQuery() {
        return new ComparePriceQuery(productName, price, quantity, unit);
    }
}