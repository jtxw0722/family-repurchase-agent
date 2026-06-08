package com.jtxw.familyagent.interfaces.rest.request;


import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 14:xx:xx
 * @Description: 本地订单文件导入请求参数。
 */
@Schema(description = "本地订单文件导入请求")
public class ImportFileRequest {
    /**
     * 本地订单文件路径。
     */
    @Schema(description = "本地 CSV 或 Excel 订单文件路径", example = "examples/sample_orders.csv", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String filePath;

    /**
     * 导入时指定的订单归属人。
     */
    @Schema(description = "导入时指定的订单归属人；为空时使用 CSV owner 字段或文件名后缀识别", example = "jtxw")
    private String owner;

    public ImportFileRequest() {
    }

    public String filePath() {
        return filePath;
    }

    public String owner() {
        return owner;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }
}