package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.ApplyNormalizationReviewCommand;
import com.jtxw.familyagent.application.command.ApplyReviewCommand;
import com.jtxw.familyagent.domain.model.ReviewApplyResult;
import com.jtxw.familyagent.domain.model.ReviewItem;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.ProductTitleCleaner;
import com.jtxw.familyagent.infrastructure.persistence.DatabaseInitializer;
import com.jtxw.familyagent.infrastructure.persistence.ProductAliasRepository;
import com.jtxw.familyagent.infrastructure.persistence.ProductNegativeAliasRepository;
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
    private final ProductTitleCleaner productTitleCleaner;
    private final ProductAliasRepository productAliasRepository;
    private final ProductNegativeAliasRepository productNegativeAliasRepository;

    public ReviewApplicationService(DatabaseInitializer databaseInitializer,
                                    PurchaseRecordRepository purchaseRecordRepository,
                                    ReviewItemRepository reviewItemRepository,
                                    ProductTitleCleaner productTitleCleaner,
                                    ProductAliasRepository productAliasRepository,
                                    ProductNegativeAliasRepository productNegativeAliasRepository) {
        this.databaseInitializer = databaseInitializer;
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.reviewItemRepository = reviewItemRepository;
        this.productTitleCleaner = productTitleCleaner;
        this.productAliasRepository = productAliasRepository;
        this.productNegativeAliasRepository = productNegativeAliasRepository;
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
        return apply(new ApplyReviewCommand(reviewId, action, note));
    }

    /**
     * 应用人工复核结果并同步更新购买记录统计决策。
     *
     * <p>目前支持 include 和 exclude 两种动作。复核项只能处理一次，
     * 成功处理后状态会变为 resolved。</p>
     *
     * @param command 人工复核应用命令
     * @return 复核应用结果
     */
    public ReviewApplyResult apply(ApplyReviewCommand command) {
        databaseInitializer.initialize();
        String normalizedAction = normalizeAction(command.action());
        ReviewItem reviewItem = reviewItemRepository.findById(command.reviewId())
                .orElseThrow(() -> new IllegalArgumentException("复核记录不存在：" + command.reviewId()));
        if (!"pending".equals(reviewItem.status())) {
            throw new IllegalStateException("复核记录不是 pending 状态，不能重复处理：" + command.reviewId());
        }
        if (reviewItem.recordId() == null) {
            throw new IllegalStateException("复核记录没有关联订单记录：" + command.reviewId());
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
        int updatedReviewCount = reviewItemRepository.resolve(command.reviewId(), normalizedAction, command.note());
        if (updatedReviewCount == 0) {
            throw new IllegalStateException("复核记录状态已变化，请重新查询后再处理：" + command.reviewId());
        }

        return new ReviewApplyResult(command.reviewId(), reviewItem.recordId(), normalizedAction, decision,
                "resolved", "复核已应用，订单统计决策更新为：" + decision);
    }

    /**
     * 确认商品归一化结果，并将人工确认沉淀为正向别名。
     *
     * @param reviewId          复核项 ID
     * @param normalizedName    人工确认的标准品类
     * @param targetUnit        标准单位；为空时使用购买记录现有单位
     * @param includeInBaseline 是否同步纳入价格基准
     * @param note              复核备注
     * @return 复核应用结果
     */
    public ReviewApplyResult confirmNormalization(long reviewId,
                                                  String normalizedName,
                                                  String targetUnit,
                                                  boolean includeInBaseline,
                                                  String note) {
        databaseInitializer.initialize();
        ReviewContext context = loadNormalizationReviewContext(reviewId);
        String finalNormalizedName = requireText(normalizedName, "normalizedName 不能为空");
        String finalTargetUnit = targetUnit == null || targetUnit.isBlank() ? context.record().unit() : targetUnit.trim();
        if (includeInBaseline) {
            validateCanIncludeAfterNormalization(reviewId, context.record(), finalTargetUnit);
        }
        String aliasKey = productTitleCleaner.aliasKey(context.record().productName(), context.record().sku());
        productAliasRepository.upsert(context.record().productName(), aliasKey, finalNormalizedName, finalTargetUnit,
                context.record().category());

        String decision = includeInBaseline ? "include" : context.record().decision();
        int updatedRecordCount = purchaseRecordRepository.updateNormalizationAndDecision(context.record().id(),
                finalNormalizedName, decision);
        if (updatedRecordCount == 0) {
            throw new IllegalStateException("关联订单记录不存在：" + context.record().id());
        }
        resolveReviewItem(reviewId, "confirm_normalization", note);
        return new ReviewApplyResult(reviewId, context.record().id(), "confirm_normalization", decision,
                "resolved", "归一化已确认，并写入商品别名：" + finalNormalizedName);
    }

    /**
     * 统一应用商品归一化复核动作。
     *
     * @param reviewId               复核项 ID
     * @param action                 归一化复核动作，支持 confirm/reject/ignore
     * @param normalizedName         confirm 时人工确认的标准品类
     * @param targetUnit             confirm 时标准单位；为空时使用购买记录现有单位
     * @param includeInBaseline      confirm 时是否同步纳入价格基准
     * @param rejectedNormalizedName reject 时被拒绝的标准品类
     * @param note                   复核备注
     * @return 复核应用结果
     */
    public ReviewApplyResult applyNormalization(long reviewId,
                                                String action,
                                                String normalizedName,
                                                String targetUnit,
                                                boolean includeInBaseline,
                                                String rejectedNormalizedName,
                                                String note) {
        return applyNormalization(new ApplyNormalizationReviewCommand(reviewId, action, normalizedName, targetUnit,
                includeInBaseline, rejectedNormalizedName, note));
    }

    /**
     * 统一应用商品归一化复核动作。
     *
     * <p>该方法只处理 PRODUCT_NAME_NORMALIZATION_REVIEW 的确认、拒绝和忽略动作，
     * 普通 include/exclude 统计决策复核仍由 apply(ApplyReviewCommand) 处理。</p>
     *
     * @param command 商品归一化复核应用命令
     * @return 复核应用结果
     */
    public ReviewApplyResult applyNormalization(ApplyNormalizationReviewCommand command) {
        String normalizedAction = requireText(command.action(), "action 不能为空").toLowerCase(Locale.ROOT);
        // 统一入口只负责动作分发，核心确认/拒绝/忽略逻辑继续复用原有方法。
        return switch (normalizedAction) {
            case "confirm" -> confirmNormalization(command.reviewId(), command.normalizedName(),
                    command.targetUnit(), command.includeInBaseline(), command.note());
            case "reject" -> rejectNormalization(command.reviewId(), command.rejectedNormalizedName(), command.note());
            case "ignore" -> ignoreNormalization(command.reviewId(), command.note());
            default -> throw new IllegalArgumentException("不支持的商品归一化复核动作：" + command.action());
        };
    }

    private void validateCanIncludeAfterNormalization(long reviewId, PurchaseRecord record, String targetUnit) {
        if (reviewItemRepository.existsOtherBlockingReviewByRecordId(record.id(), reviewId)) {
            throw new IllegalStateException("同一购买记录仍存在其他待处理或已排除的复核风险，不能直接纳入价格基准。");
        }
        if (!sameUnit(targetUnit, record.unit())) {
            throw new IllegalStateException("确认归一化的 targetUnit 与购买记录当前 unit 不一致，需先完成规格/单位复核。");
        }
    }

    /**
     * 拒绝当前归一化结果，并沉淀为负向别名。
     *
     * @param reviewId               复核项 ID
     * @param rejectedNormalizedName 被拒绝的标准品类；为空时使用购买记录当前 normalized_name
     * @param note                   复核备注
     * @return 复核应用结果
     */
    public ReviewApplyResult rejectNormalization(long reviewId, String rejectedNormalizedName, String note) {
        databaseInitializer.initialize();
        ReviewContext context = loadNormalizationReviewContext(reviewId);
        String finalRejectedName = rejectedNormalizedName == null || rejectedNormalizedName.isBlank()
                ? context.record().normalizedName()
                : rejectedNormalizedName.trim();
        String aliasKey = productTitleCleaner.aliasKey(context.record().productName(), context.record().sku());
        productNegativeAliasRepository.upsert(context.record().productName(), aliasKey, finalRejectedName, note);
        purchaseRecordRepository.updateDecision(context.record().id(), "exclude");
        resolveReviewItem(reviewId, "reject_normalization", note);
        return new ReviewApplyResult(reviewId, context.record().id(), "reject_normalization", "exclude",
                "resolved", "归一化已拒绝，并写入商品负向别名：" + finalRejectedName);
    }

    /**
     * 忽略归一化复核项，不沉淀别名知识。
     *
     * @param reviewId 复核项 ID
     * @param note     复核备注
     * @return 复核应用结果
     */
    public ReviewApplyResult ignoreNormalization(long reviewId, String note) {
        databaseInitializer.initialize();
        ReviewContext context = loadNormalizationReviewContext(reviewId);
        resolveReviewItem(reviewId, "ignore_normalization", note);
        return new ReviewApplyResult(reviewId, context.record().id(), "ignore_normalization",
                context.record().decision(), "resolved", "归一化复核已忽略，未写入别名知识。");
    }

    private ReviewContext loadNormalizationReviewContext(long reviewId) {
        ReviewItem reviewItem = reviewItemRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("复核记录不存在：" + reviewId));
        if (!"pending".equals(reviewItem.status())) {
            throw new IllegalStateException("复核记录不是 pending 状态，不能重复处理：" + reviewId);
        }
        if (!"PRODUCT_NAME_NORMALIZATION_REVIEW".equals(reviewItem.reasonCode())) {
            throw new IllegalStateException("仅商品归一化复核项支持归一化学习：" + reviewItem.reasonCode());
        }
        if (reviewItem.recordId() == null) {
            throw new IllegalStateException("复核记录没有关联订单记录：" + reviewId);
        }
        PurchaseRecord record = purchaseRecordRepository.findById(reviewItem.recordId())
                .orElseThrow(() -> new IllegalStateException("关联订单记录不存在：" + reviewItem.recordId()));
        return new ReviewContext(reviewItem, record);
    }

    private int resolveReviewItem(long reviewId, String action, String note) {
        int updatedReviewCount = reviewItemRepository.resolve(reviewId, action, note);
        if (updatedReviewCount == 0) {
            throw new IllegalStateException("复核记录状态已变化，请重新查询后再处理：" + reviewId);
        }
        return updatedReviewCount;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private boolean sameUnit(String left, String right) {
        return normalizeUnit(left).equals(normalizeUnit(right));
    }

    private String normalizeUnit(String unit) {
        return unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
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

    private record ReviewContext(ReviewItem reviewItem, PurchaseRecord record) {
    }
}
