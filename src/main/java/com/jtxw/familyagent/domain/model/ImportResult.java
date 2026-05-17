package com.jtxw.familyagent.domain.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/00:28
 * @Description: 订单导入结果对象，返回批次、导入数量和复核数量。
 */
@Schema(description = "订单导入结果")
public class ImportResult {
    /**
     * 导入批次 ID
     */
    @Schema(description = "导入批次 ID", example = "1")
    private final long batchId;
    /**
     * 文件中解析到的总记录数
     */
    @Schema(description = "文件中解析到的总记录数", example = "10")
    private final int totalCount;
    /**
     * 成功写入数据库的记录数
     */
    @Schema(description = "成功写入本地数据库的记录数", example = "8")
    private final int importedCount;
    /**
     * 导入后生成的待复核记录数
     */
    @Schema(description = "导入后生成的待人工复核记录数", example = "2")
    private final int reviewCount;
    /**
     * 面向调用方展示的导入结果说明
     */
    @Schema(description = "面向调用方展示的导入结果说明", example = "导入完成")
    private final String message;

    public ImportResult(long batchId, int totalCount, int importedCount, int reviewCount, String message) {
        this.batchId = batchId;
        this.totalCount = totalCount;
        this.importedCount = importedCount;
        this.reviewCount = reviewCount;
        this.message = message;
    }

    public long batchId() {
        return batchId;
    }

    public int totalCount() {
        return totalCount;
    }

    public int importedCount() {
        return importedCount;
    }

    public int reviewCount() {
        return reviewCount;
    }

    public String message() {
        return message;
    }

    public long getBatchId() {
        return batchId;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getImportedCount() {
        return importedCount;
    }

    public int getReviewCount() {
        return reviewCount;
    }

    public String getMessage() {
        return message;
    }
}
