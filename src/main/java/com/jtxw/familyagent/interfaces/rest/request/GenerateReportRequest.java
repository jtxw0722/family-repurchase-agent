package com.jtxw.familyagent.interfaces.rest.request;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * @Author: jtxw
 * @Date: 2026/06/08/14:41
 * @Description:
 */

@Schema(description = "复购品价格报告生成请求")
public class GenerateReportRequest {
    /**
     * 报告月份，格式为 yyyy-MM
     */
    @Schema(description = "报告月份，格式为 yyyy-MM", example = "2026-05", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String month;

    public GenerateReportRequest() {
    }

    public String month() {
        return month;
    }

    public String getMonth() {
        return month;
    }

    public void setMonth(String month) {
        this.month = month;
    }
}
