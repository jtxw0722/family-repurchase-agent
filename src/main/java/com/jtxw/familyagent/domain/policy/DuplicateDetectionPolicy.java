package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.PurchaseRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * @Author: jtxw
 * @Date: 2026/05/17/09:49
 * @Description: 重复订单检测规则，基于确定性字段生成订单指纹。
 */
@Component
public class DuplicateDetectionPolicy {
    /**
     * 判断购买记录是否为重复订单。
     *
     * <p>重复判断采用保守的精确匹配口径：订单时间、平台、归属人、归一化商品名、SKU、
     * 数量、单位、当前统计金额和币种全部一致时，才认为是重复记录。</p>
     *
     * @param record                   待判断的购买记录
     * @param currentBatchFingerprints 当前导入批次内已出现的订单指纹
     * @param existsInHistory          本地数据库中是否已存在相同订单
     * @return 是否为重复订单
     */
    public boolean isDuplicate(PurchaseRecord record, Set<String> currentBatchFingerprints, boolean existsInHistory) {
        String fingerprint = fingerprint(record);
        // Set.add 返回 false 表示当前批次内已经出现过相同指纹
        boolean repeatedInCurrentBatch = !currentBatchFingerprints.add(fingerprint);
        return existsInHistory || repeatedInCurrentBatch;
    }

    /**
     * 生成订单去重指纹。
     *
     * @param record 购买记录
     * @return 订单去重指纹
     */
    public String fingerprint(PurchaseRecord record) {
        return String.join("|",
                normalizeText(record.orderTime()),
                normalizeText(record.platform()),
                normalizeText(record.owner()),
                normalizeText(record.normalizedName()),
                normalizeText(record.sku()),
                normalizeNumber(record.quantity()),
                normalizeText(record.unit()),
                normalizeNumber(record.totalAmount()),
                normalizeText(record.currency())
        );
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeNumber(Double value) {
        if (value == null) {
            return "";
        }
        // 去掉无意义的小数尾零，避免 12、12.0、12.00 生成不同指纹
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
