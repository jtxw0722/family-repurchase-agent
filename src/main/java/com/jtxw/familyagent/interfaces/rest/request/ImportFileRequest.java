package com.jtxw.familyagent.interfaces.rest.request;


import com.jtxw.familyagent.application.command.ImportFileCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.nio.file.Path;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 14:48:11
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
    @Schema(description = "导入时指定的订单归属人，用于溯源和重复检测辅助；为空时使用 CSV owner 字段或文件名后缀识别", example = "jtxw")
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

    /**
     * 将 REST 请求参数转换为应用层导入命令。
     *
     * @return 文件导入命令，filePath 会被转换为 java.nio.file.Path
     */
    public ImportFileCommand toCommand() {
        return new ImportFileCommand(Path.of(filePath), owner);
    }
}