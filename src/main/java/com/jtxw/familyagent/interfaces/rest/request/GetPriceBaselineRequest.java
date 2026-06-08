package com.jtxw.familyagent.interfaces.rest.request;


import com.jtxw.familyagent.application.query.GetPriceBaselineQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * @Author: jtxw
 * @Date: 2026/06/08/14:40
 * @Description: 历史价格基准线查询请求参数，属于 interfaces.rest.request 层，对应 /get-price-baseline 接口。
 */

@Schema(description = "历史价格基准线查询请求")
public class GetPriceBaselineRequest {
    /**
     * 原始商品名称
     */
    @Schema(description = "原始商品名称，会在服务端进行本地规则归一化", example = "纸巾", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String productName;

    /**
     * 可选统计单位
     */
    @Schema(description = "可选统计单位，例如 kg、抽、L；为空时使用商品规则中的标准单位", example = "抽")
    private String unit;

    public GetPriceBaselineRequest() {
    }

    public String productName() {
        return productName;
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

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    /**
     * 将 REST 请求参数转换为应用层基准线查询。
     *
     * @return 历史价格基准线查询
     */
    public GetPriceBaselineQuery toQuery() {
        return new GetPriceBaselineQuery(productName, unit);
    }
}
