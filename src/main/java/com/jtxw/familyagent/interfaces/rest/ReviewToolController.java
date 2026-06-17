package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.ReviewApplicationService;
import com.jtxw.familyagent.application.query.ReviewItemQuery;
import com.jtxw.familyagent.domain.model.ReviewApplyResult;
import com.jtxw.familyagent.domain.model.ReviewItemDetail;
import com.jtxw.familyagent.interfaces.rest.request.ReviewApplyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/16 08:22:00
 * @Description: 人工复核工具 Controller，暴露待复核记录查询和复核结果应用接口。
 */
@Tag(name = "Agent Tool API", description = "家庭复购品价格决策工具接口")
@RestController
@RequestMapping("/api/tools")
public class ReviewToolController {
    /**
     * 人工复核应用服务，负责查询和应用复核结果。
     */
    private final ReviewApplicationService reviewApplicationService;

    /**
     * 创建人工复核工具 Controller。
     *
     * @param reviewApplicationService 人工复核应用服务
     */
    public ReviewToolController(ReviewApplicationService reviewApplicationService) {
        this.reviewApplicationService = reviewApplicationService;
    }

    /**
     * 查询复核记录，支持按状态、批次、归属人、复核原因码、统计决策和来源文件筛选。
     *
     * <p>不传任何参数时保持默认行为，默认查询 pending 状态的复核项。
     * 分页参数 page 默认 1，size 默认 100，最大 500。</p>
     *
     * @param status     复核项状态筛选，不传时默认 pending
     * @param batchId    导入批次 ID 筛选
     * @param owner      订单归属人筛选
     * @param reasonCode 复核原因码筛选
     * @param decision   购买记录统计决策筛选
     * @param sourceFile 来源文件模糊筛选
     * @param page       页码，默认 1
     * @param size       每页条数，默认 100，最大 500
     * @return 符合条件的复核详情列表
     */
    @Operation(summary = "查看待复核记录", description = "查询当前待人工复核的异常订单记录，支持按状态、批次、归属人、原因码、决策和来源文件筛选，并返回关联订单的商品、金额、单价和来源文件等信息。")
    @GetMapping("/review-items")
    public List<ReviewItemDetail> listReviewItems(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String reasonCode,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String sourceFile,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "100") int size) {
        ReviewItemQuery query = new ReviewItemQuery(status, batchId, owner, reasonCode, decision, sourceFile, page, size);
        return reviewApplicationService.listReviewItems(query);
    }

    /**
     * 应用人工复核结果。
     *
     * <p>该统一入口同时承载普通 include/exclude 统计复核和商品归一化
     * confirm_normalization/reject_normalization/ignore_normalization 复核。
     * Controller 只负责按 action 分发，具体业务规则继续由 ReviewApplicationService 处理。</p>
     *
     * @param id      复核项 ID
     * @param request 复核动作请求
     * @return 复核应用结果
     */
    @Operation(summary = "应用复核结果", description = "统一应用普通统计复核和商品归一化复核，并同步更新复核项状态。")
    @PostMapping("/review-items/{id}/apply")
    public ReviewApplyResult applyReview(@PathVariable long id, @Valid @RequestBody ReviewApplyRequest request) {
        if (request.isStatisticalDecisionAction()) {
            return reviewApplicationService.apply(request.toCommand(id));
        }
        return reviewApplicationService.applyNormalization(request.toNormalizationCommand(id));
    }
}
