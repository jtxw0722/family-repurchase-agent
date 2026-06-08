package com.jtxw.familyagent.application;

import com.jtxw.familyagent.application.command.ApplyNormalizationReviewCommand;
import com.jtxw.familyagent.application.command.ApplyReviewCommand;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.model.ReviewApplyResult;
import com.jtxw.familyagent.domain.model.ReviewItem;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import com.jtxw.familyagent.domain.policy.ProductTitleCleaner;
import com.jtxw.familyagent.infrastructure.persistence.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * @Author: jtxw
 * @Date: 2026/05/12/11:06
 * @Description: 复核应用服务，负责待复核记录查询、人工复核应用和商品归一化复核处理。
 */
@Service
public class ReviewApplicationService {
    /**
     * 复核动作 / 统计决策：纳入价格基准。
     */
    private static final String ACTION_INCLUDE = "include";
    /**
     * 复核动作 / 统计决策：排除出价格基准。
     */
    private static final String ACTION_EXCLUDE = "exclude";
    /**
     * 复核项状态：待处理。
     */
    private static final String REVIEW_STATUS_PENDING = "pending";
    /**
     * 复核项状态：已处理。
     */
    private static final String REVIEW_STATUS_RESOLVED = "resolved";
    /**
     * 商品归一化复核动作：确认归一化结果。
     */
    private static final String NORMALIZATION_ACTION_CONFIRM = "confirm";
    /**
     * 商品归一化复核动作：拒绝归一化结果。
     */
    private static final String NORMALIZATION_ACTION_REJECT = "reject";
    /**
     * 商品归一化复核动作：忽略归一化结果。
     */
    private static final String NORMALIZATION_ACTION_IGNORE = "ignore";
    /**
     * 归一化复核结果动作：已确认归一化。
     */
    private static final String RESULT_ACTION_CONFIRM_NORMALIZATION = "confirm_normalization";
    /**
     * 归一化复核结果动作：已拒绝归一化。
     */
    private static final String RESULT_ACTION_REJECT_NORMALIZATION = "reject_normalization";
    /**
     * 归一化复核结果动作：已忽略归一化。
     */
    private static final String RESULT_ACTION_IGNORE_NORMALIZATION = "ignore_normalization";
    /**
     * 复核原因码：疑似重复订单。
     */
    private static final String REVIEW_REASON_DUPLICATE_ORDER = "DUPLICATE_ORDER";
    /**
     * 复核原因码：商品名称归一化置信度较低。
     */
    private static final String REVIEW_REASON_PRODUCT_NAME_NORMALIZATION = "PRODUCT_NAME_NORMALIZATION_REVIEW";
    /**
     * 去重状态：重复订单。
     */
    private static final String DEDUPE_STATUS_DUPLICATE = "duplicate";
    /**
     * 去重状态：唯一订单。
     */
    private static final String DEDUPE_STATUS_UNIQUE = "unique";

    private final DatabaseInitializer databaseInitializer;
    private final PurchaseRecordRepository purchaseRecordRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final ProductTitleCleaner productTitleCleaner;
    private final ProductAliasRepository productAliasRepository;
    private final ProductNegativeAliasRepository productNegativeAliasRepository;

    /**
     * 创建复核应用服务。
     *
     * @param databaseInitializer            数据库初始化组件
     * @param purchaseRecordRepository       购买记录仓储
     * @param reviewItemRepository           复核项仓储
     * @param productTitleCleaner            商品标题清洗器
     * @param productAliasRepository         正向别名仓储
     * @param productNegativeAliasRepository 负向别名仓储
     */
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
        if (!REVIEW_STATUS_PENDING.equals(reviewItem.status())) {
            throw new IllegalStateException("复核记录不是 pending 状态，不能重复处理：" + command.reviewId());
        }
        if (reviewItem.recordId() == null) {
            throw new IllegalStateException("复核记录没有关联订单记录：" + command.reviewId());
        }

        String decision = ACTION_INCLUDE.equals(normalizedAction) ? ACTION_INCLUDE : ACTION_EXCLUDE;
        int updatedRecordCount;
        if (REVIEW_REASON_DUPLICATE_ORDER.equals(reviewItem.reasonCode())) {
            // 重复订单被人工确认为 include 时，需要恢复去重状态，避免继续被统计查询过滤
            boolean duplicate = ACTION_EXCLUDE.equals(decision);
            updatedRecordCount = purchaseRecordRepository.updateDecisionAndDedupe(reviewItem.recordId(), decision,
                    duplicate, duplicate ? DEDUPE_STATUS_DUPLICATE : DEDUPE_STATUS_UNIQUE);
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
                REVIEW_STATUS_RESOLVED, "复核已应用，订单统计决策更新为：" + decision);
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

        String decision = includeInBaseline ? ACTION_INCLUDE : context.record().decision();
        int updatedRecordCount = purchaseRecordRepository.updateNormalizationAndDecision(context.record().id(),
                finalNormalizedName, decision);
        if (updatedRecordCount == 0) {
            throw new IllegalStateException("关联订单记录不存在：" + context.record().id());
        }
        resolveReviewItem(reviewId, RESULT_ACTION_CONFIRM_NORMALIZATION, note);
        return new ReviewApplyResult(reviewId, context.record().id(), RESULT_ACTION_CONFIRM_NORMALIZATION, decision,
                REVIEW_STATUS_RESOLVED, "归一化已确认，并写入商品别名：" + finalNormalizedName);
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
            case NORMALIZATION_ACTION_CONFIRM -> confirmNormalization(command.reviewId(), command.normalizedName(),
                    command.targetUnit(), command.includeInBaseline(), command.note());
            case NORMALIZATION_ACTION_REJECT ->
                    rejectNormalization(command.reviewId(), command.rejectedNormalizedName(), command.note());
            case NORMALIZATION_ACTION_IGNORE -> ignoreNormalization(command.reviewId(), command.note());
            default -> throw new IllegalArgumentException("不支持的商品归一化复核动作：" + command.action());
        };
    }

    /**
     * 校验归一化后是否允许直接纳入价格基准。
     *
     * <p>如果同一购买记录仍存在其他阻塞性复核风险，则不能直接 include。
     * 如果确认的 targetUnit 与购买记录当前 unit 不一致，则需要先完成规格/单位复核。
     * 该方法只做校验，不直接写数据库。</p>
     *
     * @param reviewId   当前复核项 ID
     * @param record     关联购买记录
     * @param targetUnit 确认归一化后的标准单位
     * @throws IllegalStateException 存在其他阻塞性复核风险或单位不一致
     */
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
        purchaseRecordRepository.updateDecision(context.record().id(), ACTION_EXCLUDE);
        resolveReviewItem(reviewId, RESULT_ACTION_REJECT_NORMALIZATION, note);
        return new ReviewApplyResult(reviewId, context.record().id(), RESULT_ACTION_REJECT_NORMALIZATION, ACTION_EXCLUDE,
                REVIEW_STATUS_RESOLVED, "归一化已拒绝，并写入商品负向别名：" + finalRejectedName);
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
        resolveReviewItem(reviewId, RESULT_ACTION_IGNORE_NORMALIZATION, note);
        return new ReviewApplyResult(reviewId, context.record().id(), RESULT_ACTION_IGNORE_NORMALIZATION,
                context.record().decision(), REVIEW_STATUS_RESOLVED, "归一化复核已忽略，未写入别名知识。");
    }

    /**
     * 加载商品归一化复核上下文。
     *
     * <p>会校验复核项存在、状态为 pending、reasonCode 为 {@code PRODUCT_NAME_NORMALIZATION_REVIEW}、
     * 存在关联购买记录。该方法只加载和校验上下文，不修改状态。</p>
     *
     * @param reviewId 复核项 ID
     * @return 归一化复核上下文，包含复核项和关联购买记录
     * @throws IllegalArgumentException 复核记录不存在
     * @throws IllegalStateException    复核状态不是 pending、reasonCode 不匹配或关联购买记录不存在
     */
    private ReviewContext loadNormalizationReviewContext(long reviewId) {
        ReviewItem reviewItem = reviewItemRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("复核记录不存在：" + reviewId));
        if (!REVIEW_STATUS_PENDING.equals(reviewItem.status())) {
            throw new IllegalStateException("复核记录不是 pending 状态，不能重复处理：" + reviewId);
        }
        if (!REVIEW_REASON_PRODUCT_NAME_NORMALIZATION.equals(reviewItem.reasonCode())) {
            throw new IllegalStateException("仅商品归一化复核项支持归一化学习：" + reviewItem.reasonCode());
        }
        if (reviewItem.recordId() == null) {
            throw new IllegalStateException("复核记录没有关联订单记录：" + reviewId);
        }
        PurchaseRecord record = purchaseRecordRepository.findById(reviewItem.recordId())
                .orElseThrow(() -> new IllegalStateException("关联订单记录不存在：" + reviewItem.recordId()));
        return new ReviewContext(reviewItem, record);
    }

    /**
     * 将复核项标记为已处理。
     *
     * <p>当仓储返回更新数量为 0 时，表示复核状态已变化，抛出异常要求重新查询。</p>
     *
     * @param reviewId 复核项 ID
     * @param action   复核结果动作
     * @param note     复核备注
     * @return 更新数量
     * @throws IllegalStateException 复核记录状态已变化，需要重新查询
     */
    private int resolveReviewItem(long reviewId, String action, String note) {
        int updatedReviewCount = reviewItemRepository.resolve(reviewId, action, note);
        if (updatedReviewCount == 0) {
            throw new IllegalStateException("复核记录状态已变化，请重新查询后再处理：" + reviewId);
        }
        return updatedReviewCount;
    }

    /**
     * 校验文本字段不能为空。
     *
     * <p>返回 trim 后的文本。校验失败时抛出 {@link IllegalArgumentException}，
     * 错误信息使用传入 message。</p>
     *
     * @param value   待校验文本
     * @param message 校验失败时的异常提示
     * @return trim 后的文本
     * @throws IllegalArgumentException 文本为空或空白
     */
    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /**
     * 判断两个单位字符串是否相同。
     *
     * <p>比较前会通过 {@link #normalizeUnit(String)} 统一处理 null、首尾空白和大小写。</p>
     *
     * @param left  左侧单位
     * @param right 右侧单位
     * @return {@code true} 表示归一化后相同，{@code false} 表示不同
     */
    private boolean sameUnit(String left, String right) {
        return normalizeUnit(left).equals(normalizeUnit(right));
    }

    /**
     * 归一化单位字符串。
     *
     * <p>null 视为空字符串；非 null 时 trim 并转小写。</p>
     *
     * @param unit 原始单位
     * @return 归一化后的单位
     */
    private String normalizeUnit(String unit) {
        return unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 归一化普通人工复核动作。
     *
     * <p>支持 include / exclude。会 trim 并转小写。
     * 空动作或不支持的动作会抛出 {@link IllegalArgumentException}。</p>
     *
     * @param action 原始复核动作
     * @return 归一化后的动作
     * @throws IllegalArgumentException 动作为空或不支持
     */
    private String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("复核动作不能为空，可选值：include / exclude");
        }
        String normalized = action.trim().toLowerCase(Locale.ROOT);
        if (!ACTION_INCLUDE.equals(normalized) && !ACTION_EXCLUDE.equals(normalized)) {
            throw new IllegalArgumentException("不支持的复核动作：" + action + "，可选值：include / exclude");
        }
        return normalized;
    }

    /**
     * 商品归一化复核上下文。
     *
     * <p>包含待处理复核项和关联购买记录，用于 confirm / reject / ignore 归一化复核流程。</p>
     *
     * @param reviewItem 待处理复核项
     * @param record     复核项关联的购买记录
     */
    private record ReviewContext(ReviewItem reviewItem, PurchaseRecord record) {
    }
}
