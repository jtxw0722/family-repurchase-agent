package com.jtxw.familyagent.domain.policy;

import com.jtxw.familyagent.domain.model.PriceDecisionResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/05/11/11:07
 * @Description: 价格决策规则，根据历史单价样本判断当前价格水平。
 */
@Component
public class PriceDecisionPolicy {
    public PriceDecisionResult decide(String productName, String normalizedName, double price, double quantity, String unit, List<Double> history) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于 0");
        }
        double currentUnitPrice = price / quantity;
        if (history == null || history.isEmpty()) {
            return new PriceDecisionResult(productName, normalizedName, currentUnitPrice, unit,
                    null, null, null, 0,
                    "insufficient_data", "数据不足", "暂无可用历史记录，无法判断是否值得买。");
        }

        List<Double> sorted = new ArrayList<>(history);
        Collections.sort(sorted);
        double min = sorted.get(0);
        double avg = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        double median = median(sorted);

        String decision;
        String decisionText;
        String reason;
        if (currentUnitPrice <= avg * 0.92) {
            decision = "good_price";
            decisionText = "好价";
            reason = "当前单价明显低于历史平均单价。";
        } else if (currentUnitPrice >= avg * 1.12) {
            decision = "expensive";
            decisionText = "偏贵";
            reason = "当前单价明显高于历史平均单价。";
        } else {
            decision = "normal_price";
            decisionText = "正常价格";
            reason = "当前单价处于正常区间。";
        }
        if (history.size() < 3) {
            reason = reason + " 当前历史样本较少，结论仅供参考。";
        }
        return new PriceDecisionResult(productName, normalizedName, currentUnitPrice, unit,
                min, median, avg, history.size(), decision, decisionText, reason);
    }

    private double median(List<Double> sorted) {
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
