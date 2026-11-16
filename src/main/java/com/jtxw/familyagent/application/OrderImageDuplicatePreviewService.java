package com.jtxw.familyagent.application;

import com.jtxw.familyagent.domain.model.ParsedDuplicatePreview;
import com.jtxw.familyagent.domain.model.ParsedPurchaseCandidate;
import com.jtxw.familyagent.domain.model.PurchaseRecord;
import com.jtxw.familyagent.domain.policy.OrderImageDuplicatePolicy;
import com.jtxw.familyagent.infrastructure.persistence.PurchaseRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/11/11 19:34:39
 * @Description: 截图查重预览应用服务，只读协调历史记录查询与领域策略，不保存购买记录或修改统计状态
 */
@Service
public class OrderImageDuplicatePreviewService {
    /** 查重失败的后台日志，不向页面输出数据库异常或完整订单原文。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderImageDuplicatePreviewService.class);
    /** 购买记录只读查询仓储，兼容构造器未配置时为空。 */
    private final PurchaseRecordRepository purchaseRecordRepository;
    /** 商品及订单号核对策略，不允许为空。 */
    private final OrderImageDuplicatePolicy duplicatePolicy;

    /**
     * 创建截图查重预览服务，不触发数据库初始化或写入。
     *
     * @param purchaseRecordRepository 历史记录仓储，隔离测试未启用查重时允许为空
     * @param duplicatePolicy 领域查重策略，不允许为空
     */
    public OrderImageDuplicatePreviewService(PurchaseRecordRepository purchaseRecordRepository,
                                            OrderImageDuplicatePolicy duplicatePolicy) {
        this.purchaseRecordRepository = purchaseRecordRepository;
        this.duplicatePolicy = duplicatePolicy;
    }

    /**
     * 为每件商品独立补充查重状态，避免一件商品已保存就把整个多商品订单标成已上传。
     *
     * @param candidates 解析及归一化后的候选列表，不允许为空
     * @param rawText 完整 OCR 原文，仅用于提取明确订单号，不返回额外个人信息
     * @return 保持原顺序的只读查重候选列表；数据库异常降级为未核对，并记录后台日志
     */
    public List<ParsedPurchaseCandidate> enrich(List<ParsedPurchaseCandidate> candidates, String rawText) {
        String orderNumber = duplicatePolicy.orderNumber(rawText);
        return candidates.stream().map(candidate -> {
            ParsedDuplicatePreview preview;
            try {
                preview = check(candidate, orderNumber);
            } catch (DataAccessException exception) {
                // 查重不可用不应阻断截图识别，但必须明确不能判断已上传，详情留在后台。
                LOGGER.warn("订单截图历史查重失败，候选数量={}，异常类型={}",
                        candidates.size(), exception.getClass().getSimpleName(), exception);
                preview = new ParsedDuplicatePreview(ParsedDuplicatePreview.UNAVAILABLE, null);
            }
            return candidate.withDuplicatePreview(preview,
                    duplicatePolicy.preserveOrderNumber(candidate.sourceText(), orderNumber));
        }).toList();
    }

    /**
     * 查询并核对单件商品；只有明确订单号一致才标记已上传，缺少订单号时仅提示可能重复。
     *
     * @param candidate 截图候选
     * @param orderNumber 当前截图的唯一订单号，允许为空
     * @return 候选的历史匹配状态
     */
    private ParsedDuplicatePreview check(ParsedPurchaseCandidate candidate, String orderNumber) {
        if (purchaseRecordRepository == null || !duplicatePolicy.canCheck(candidate)) {
            return new ParsedDuplicatePreview(ParsedDuplicatePreview.UNAVAILABLE, null);
        }
        List<PurchaseRecord> history = purchaseRecordRepository.findOrderImageMatches(candidate.owner(),
                duplicatePolicy.purchaseDay(candidate.purchaseDate()), candidate.price());
        Long possibleRecordId = null;
        for (PurchaseRecord record : history) {
            if (!duplicatePolicy.sameItem(candidate, record)) {
                continue;
            }
            String savedOrderNumber = duplicatePolicy.orderNumber(record.sourceText());
            if (orderNumber != null && savedOrderNumber != null) {
                if (orderNumber.equals(savedOrderNumber)) {
                    return new ParsedDuplicatePreview(ParsedDuplicatePreview.SAVED, record.id());
                }
                // 同日同价同商品也可能是另一次购买，两个明确且不同的订单号不算重复。
                continue;
            }
            if (possibleRecordId == null) {
                possibleRecordId = record.id();
            }
        }
        return possibleRecordId == null ? new ParsedDuplicatePreview(ParsedDuplicatePreview.NONE, null)
                : new ParsedDuplicatePreview(ParsedDuplicatePreview.POSSIBLE, possibleRecordId);
    }
}
