package com.jtxw.familyagent.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * @Author: jtxw
 * @Date: 2026/11/19 19:39:30
 * @Description: 库存看板查询结果，描述一项已触发或即将触发的补货提醒
 *
 * @param normalizedName 归一化商品名称，非空
 * @param immediate 是否应立即处理；为 false 时将进入补货提醒窗口
 * @param status 库存模块计算的提醒状态，非空
 * @param remainingDays 剩余完整自然日数，不小于零，无可靠估算时为空
 * @param depletionDate 预计耗尽的上海自然日，无可靠估算时为空
 * @param reminderDate 按提前天数计算的提醒日期，无可靠估算时为空
 */
public record ReplenishmentOverviewItem(String normalizedName, boolean immediate, String status,
                                        BigDecimal remainingDays, LocalDate depletionDate, LocalDate reminderDate) {
}
