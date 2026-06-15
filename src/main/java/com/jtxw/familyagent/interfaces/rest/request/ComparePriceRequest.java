package com.jtxw.familyagent.interfaces.rest.request;


import com.jtxw.familyagent.application.query.ComparePriceQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 09:30:00
 * @Description: 价格分析请求参数，属于 interfaces.rest.request 层，对应 /compare-price 接口，支持历史基准线查询和当前价格比较。
 */

@Schema(description = "价格分析请求；只传 productName 时查询历史价格基准线，同时传 price、quantity、unit 时进行价格比较")
public class ComparePriceRequest {
    /**
     * 原始商品名称
     */
    @Schema(description = "原始商品名称，会在服务端进行本地规则归一化", example = "猫砂", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String productName;
    /**
     * 当前总价，单位为元；baseline-only 模式允许为空，compare 模式必须与 quantity、unit 同时提供且大于 0
     */
    @Schema(description = "当前购买总价；为空时仅查询历史价格基准线", example = "89", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Positive
    private Double price;
    /**
     * 当前商品数量；baseline-only 模式允许为空，compare 模式必须与 price、unit 同时提供且大于 0
     */
    @Schema(description = "当前购买数量；为空时仅查询历史价格基准线", example = "12", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Positive
    private Double quantity;
    /**
     * 数量单位；baseline-only 模式允许为空，compare 模式必须与 price、quantity 同时提供且不能为空
     */
    @Schema(description = "数量单位；为空时仅查询历史价格基准线", example = "kg", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String unit;

    public ComparePriceRequest() {
    }

    public String productName() {
        return productName;
    }

    public Double price() {
        return price;
    }

    public Double quantity() {
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

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public Double getQuantity() {
        return quantity;
    }

    public void setQuantity(Double quantity) {
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
