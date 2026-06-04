package com.jtxw.familyagent.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/04
 * @Description: 手动购买记录录入请求，用于接收 Claude 已抽取好的结构化购买字段。
 */
@Schema(description = "手动购买记录录入请求")
public record RecordPurchaseRequest(
        @Schema(description = "是否只预览不写入数据库", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        Boolean dryRun,

        @Schema(description = "待录入的购买记录列表", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty
        List<@Valid Record> records
) {
    /**
     * @Author: jtxw
     * @Date: 2026/06/04
     * @Description: 单条手动购买记录。
     */
    public record Record(
            @Schema(description = "原始商品名称", example = "猫砂", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank
            String productName,

            @Schema(description = "购买总价", example = "109.9", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull
            @Positive
            Double price,

            @Schema(description = "购买数量", example = "24", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull
            @Positive
            Double quantity,

            @Schema(description = "数量单位", example = "kg", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank
            String unit,

            @Schema(description = "购买平台，缺省为 MANUAL", example = "JD")
            String platform,

            @Schema(description = "购买日期，yyyy-MM-dd；缺省为当前日期", example = "2026-06-04")
            String purchaseDate,

            @Schema(description = "订单归属人，缺省为 jtxw", example = "jtxw")
            String owner,

            @Schema(description = "店铺名称", example = "京东自营")
            String shopName,

            @Schema(description = "商品规格或 SKU", example = "6kg*4包")
            String sku,

            @Schema(description = "人工备注", example = "手动录入")
            String note,

            @Schema(description = "Claude 抽取前的原始自然语言文本")
            String sourceText
    ) {
    }
}
