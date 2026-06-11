package com.jtxw.familyagent.application.query;

/**
 * @Author: jtxw
 * @Date: 2026/06/11 22:10:00
 * @Description: 复核项查询条件，用于筛选和分页查询待复核记录。
 *
 * @param status     复核项状态；为空时由应用服务默认查询 pending
 * @param batchId    导入批次 ID；为空时不按批次筛选
 * @param owner      订单归属人；为空时不按归属人筛选
 * @param reasonCode 复核原因码；为空时不按原因码筛选
 * @param decision   购买记录统计决策；为空时不按 include / exclude 筛选
 * @param sourceFile 来源文件名或路径片段；为空时不按来源文件筛选
 * @param page       页码；小于 1 时归一化为 1
 * @param size       每页条数；小于 1 时使用默认值 100，大于 500 时截断为 500
 */
public record ReviewItemQuery(
        String status,
        Long batchId,
        String owner,
        String reasonCode,
        String decision,
        String sourceFile,
        int page,
        int size
) {
    /**
     * 默认每页条数。
     */
    private static final int DEFAULT_SIZE = 100;
    /**
     * 最大每页条数。
     */
    private static final int MAX_SIZE = 500;

    /**
     * 归一化分页参数，确保 page >= 1，size 不超过最大每页条数。
     *
     * <p>size 小于 1 时按默认值 100 处理，大于 500 时截断为 500；
     * page 小于 1 时归一化为 1。</p>
     *
     * @return 分页参数归一化后的查询条件
     */
    public ReviewItemQuery normalize() {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = size < 1 ? DEFAULT_SIZE : Math.min(MAX_SIZE, size);
        return new ReviewItemQuery(status, batchId, owner, reasonCode, decision, sourceFile,
                normalizedPage, normalizedSize);
    }
}
