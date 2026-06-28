package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.NormalizationLibraryService;
import com.jtxw.familyagent.application.NormalizationLlmTaskService;
import com.jtxw.familyagent.application.NormalizationRuleSuggestionService;
import com.jtxw.familyagent.domain.model.NormalizationLibraryItem;
import com.jtxw.familyagent.domain.model.NormalizationLlmTask;
import com.jtxw.familyagent.domain.model.NormalizationLlmTaskCreateResult;
import com.jtxw.familyagent.interfaces.rest.request.NormalizationLibraryOperationRequest;
import com.jtxw.familyagent.interfaces.rest.request.NormalizationRuleSuggestionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/14 16:50:03
 * @Description: 商品归一化工具 Controller，暴露规则库维护、规则维护建议任务和归一化 LLM 通用任务查询接口。
 */
@Tag(name = "Agent Tool API", description = "家庭复购品价格决策工具接口")
@RestController
@RequestMapping("/api/tools")
public class NormalizationToolController {
    /**
     * 归一化 LLM 通用任务查询服务，负责查询 normalization_llm_tasks。
     */
    private final NormalizationLlmTaskService normalizationLlmTaskService;
    /**
     * 规则维护建议服务，负责创建 LLM 规则建议任务。
     */
    private final NormalizationRuleSuggestionService normalizationRuleSuggestionService;
    /**
     * 归一化名称库服务，负责查询 SQLite 规则库和动态样本数量。
     */
    private final NormalizationLibraryService normalizationLibraryService;

    /**
     * 创建商品归一化工具 Controller。
     *
     * @param normalizationLlmTaskService        归一化 LLM 通用任务查询服务
     * @param normalizationRuleSuggestionService 规则维护建议服务
     * @param normalizationLibraryService        归一化名称库服务
     */
    public NormalizationToolController(NormalizationLlmTaskService normalizationLlmTaskService,
                                       NormalizationRuleSuggestionService normalizationRuleSuggestionService,
                                       NormalizationLibraryService normalizationLibraryService) {
        this.normalizationLlmTaskService = normalizationLlmTaskService;
        this.normalizationRuleSuggestionService = normalizationRuleSuggestionService;
        this.normalizationLibraryService = normalizationLibraryService;
    }

    /**
     * 查询归一化名称库。
     *
     * @return 归一化名称库条目，包含标准单位、单位族、正负关键词和动态样本数量
     */
    @Operation(summary = "查询归一化名称库", description = "查询 SQLite normalization_rules 全家庭规则库，返回规则基础信息、关键词和动态历史样本数量。")
    @GetMapping("/normalization-library")
    public List<NormalizationLibraryItem> listNormalizationLibrary() {
        return normalizationLibraryService.listLibraryItems();
    }

    /**
     * 统一处理归一化规则库写操作。
     *
     * @param request 统一写操作请求，使用 action 区分规则维护、历史回填和历史样本重算
     * @return 统一写操作响应结果；apply_rule_to_records / recheck_rule_records 返回历史记录预览或执行结果
     */
    @Operation(summary = "维护归一化规则库", description = "通过 action 统一处理归一化规则新增、更新、禁用、关键词维护、受控历史记录回填和历史样本重算；apply_rule_to_records 与 recheck_rule_records 默认 dry-run。")
    @PostMapping("/normalization-library")
    public Object operateNormalizationLibrary(
            @RequestBody NormalizationLibraryOperationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("归一化规则库操作请求不能为空");
        }
        return normalizationLibraryService.operate(request.toCommand());
    }

    /**
     * 创建 LLM 归一化规则维护建议任务。
     *
     * @param request 规则维护建议请求，包含候选筛选条件和 apply 开关
     * @return 异步任务创建结果
     */
    @Operation(summary = "创建归一化规则维护建议任务", description = "默认从全家庭历史购买记录筛选候选商品，owner 仅为显式候选过滤条件；调用 LLM 建议新增规则或关键词，是否写库由 apply 控制。")
    @PostMapping("/normalization-rule-suggestions")
    public NormalizationLlmTaskCreateResult createNormalizationRuleSuggestionTask(
            @RequestBody NormalizationRuleSuggestionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("规则维护建议请求不能为空");
        }
        return normalizationRuleSuggestionService.create(request.toCommand());
    }

    /**
     * 查询归一化 LLM 通用异步任务状态。
     *
     * @param taskId 通用 LLM 任务 ID
     * @return 任务状态、筛选条件、统计计数和 JSON 结果
     */
    @Operation(summary = "查询归一化 LLM 通用任务", description = "查询规则维护建议任务的状态、筛选条件、统计计数和 JSON 结果。")
    @GetMapping("/normalization-llm-tasks/{taskId}")
    public NormalizationLlmTask getNormalizationLlmTask(@PathVariable long taskId) {
        return normalizationLlmTaskService.get(taskId);
    }
}
