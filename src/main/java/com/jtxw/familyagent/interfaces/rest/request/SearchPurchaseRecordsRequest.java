package com.jtxw.familyagent.interfaces.rest.request;

import com.jtxw.familyagent.application.query.SearchPurchaseRecordsQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * @Author: jtxw
 * @Date: 2026/06/12 13:09:27
 * @Description: 原始购买记录关键词检索请求，用于接收 REST Tool API 的只读历史订单样本查询参数。
 */
@Schema(description = "原始购买记录关键词检索请求")
public class SearchPurchaseRecordsRequest {
    /**
     * 查询关键词，按商品名称、SKU、分类和归一化名称做模糊匹配，trim 后不允许为空。
     */
    @Schema(description = "查询关键词，按商品名称、SKU、分类和归一化名称做模糊匹配", example = "猫砂",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String keyword;

    /**
     * 可选订单归属人；不传、null 或空字符串表示查询全家庭样本。
     */
    @Schema(description = "可选订单归属人；为空时查询全家庭样本", example = "jtxw")
    private String owner;

    /**
     * 可选返回条数；不传默认 20，超过 50 时由应用服务截断为 50。
     */
    @Schema(description = "可选返回条数；不传默认 20，最大 50", example = "20")
    private Integer limit;

    /**
     * 可选开始日期，格式 yyyy-MM-dd，按订单时间下界过滤。
     */
    @Schema(description = "可选开始日期，格式 yyyy-MM-dd", example = "2025-01-01")
    private String fromDate;

    /**
     * 可选结束日期，格式 yyyy-MM-dd，按订单时间上界过滤。
     */
    @Schema(description = "可选结束日期，格式 yyyy-MM-dd", example = "2026-06-12")
    private String toDate;

    public SearchPurchaseRecordsRequest() {
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public String getFromDate() {
        return fromDate;
    }

    public void setFromDate(String fromDate) {
        this.fromDate = fromDate;
    }

    public String getToDate() {
        return toDate;
    }

    public void setToDate(String toDate) {
        this.toDate = toDate;
    }

    /**
     * 将 REST 请求参数转换为应用层原始购买记录检索查询。
     *
     * @return 原始购买记录检索查询
     */
    public SearchPurchaseRecordsQuery toQuery() {
        return new SearchPurchaseRecordsQuery(keyword, owner, limit, fromDate, toDate);
    }
}
