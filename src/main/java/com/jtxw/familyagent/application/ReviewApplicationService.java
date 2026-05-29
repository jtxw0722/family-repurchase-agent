package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.ReviewApplyResult;
import com.jtxw.familyagent.domain.model.ReviewItem;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import com.jtxw.familyagent.infrastructure.persistence.ReviewItemRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * @Author: jtxw
 * @Date: 2026/05/12/11:06
 * @Description: 复核应用服务，提供待复核记录查询能力。
 */
@Service
public class ReviewApplicationService {
    private final DatabaseInitializer databaseInitializer;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ReviewItemRepository reviewItemRepository;

    public ReviewApplicationService(DatabaseInitializer databaseInitializer,
                                    PurchaseRecordRepository purchaseRecordRepository,
                                    ReviewItemRepository reviewItemRepository) {
        this.databaseInitializer = databaseInitializer;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
    }

    /**
     * 查询当前待处理的人工复核项。
     *
     * <p>返回复核项基础信息和关联订单摘要，便于人工判断是否纳入统计。</p>
     *
     * @return 待复核详情列表
     */
    public List<ReviewItemDetail> listPending() {
        databaseInitializer.initialize();
        return reviewItemRepository.listPendingDetails();
    }

    /**
     * 应用人工复核结果并同步更新购买记录统计决策。
     *
     * <p>目前支持 include 和 exclude 两种动作。复核项只能处理一次，
     * 成功处理后状态会变为 resolved。</p>
     *
     * @param reviewId 复核项 ID
     * @param action   复核动作，取值 include 或 exclude
     * @param note     人工复核备注
     * @return 复核应用结果
     */
    public ReviewApplyResult apply(long reviewId, String action, String note) {
        databaseInitializer.initialize();
        String normalizedAction = normalizeAction(action);
        ReviewItem reviewItem = reviewItemRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("复核记录不存在：" + reviewId));
        if (!"pending".equals(reviewItem.status())) {
            throw new IllegalStateException("复核记录不是 pending 状态，不能重复处理：" + reviewId);
        }
        if (reviewItem.recordId() == null) {
            throw new IllegalStateException("复核记录没有关联订单记录：" + reviewId);
        }

        String decision = "include".equals(normalizedAction) ? "include" : "exclude";
        int updatedRecordCount;
        if ("DUPLICATE_ORDER".equals(reviewItem.reasonCode())) {
            // 重复订单被人工确认为 include 时，需要恢复去重状态，避免继续被统计查询过滤
            boolean duplicate = "exclude".equals(decision);
            updatedRecordCount = purchaseRecordRepository.updateDecisionAndDedupe(reviewItem.recordId(), decision,
                    duplicate, duplicate ? "duplicate" : "unique");
        } else {
            updatedRecordCount = purchaseRecordRepository.updateDecision(reviewItem.recordId(), decision);
        }
        if (updatedRecordCount == 0) {
            throw new IllegalStateException("关联订单记录不存在：" + reviewItem.recordId());
        }
        int updatedReviewCount = reviewItemRepository.resolve(reviewId, normalizedAction, note);
        if (updatedReviewCount == 0) {
            throw new IllegalStateException("复核记录状态已变化，请重新查询后再处理：" + reviewId);
        }

        return new ReviewApplyResult(reviewId, reviewItem.recordId(), normalizedAction, decision,
                "resolved", "复核已应用，订单统计决策更新为：" + decision);
    }

    private String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("复核动作不能为空，可选值：include / exclude");
        }
        String normalized = action.trim().toLowerCase(Locale.ROOT);
        if (!"include".equals(normalized) && !"exclude".equals(normalized)) {
            throw new IllegalArgumentException("不支持的复核动作：" + action + "，可选值：include / exclude");
        }
        return normalized;
    }
}
