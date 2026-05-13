package com.jtxw.familyagent.domain.model;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/00:28
 * @Description: 订单导入结果对象，返回批次、导入数量和复核数量。
 */
public record ImportResult(
        long batchId,
        int totalCount,
        int importedCount,
        int reviewCount,
        String message
) {
}
