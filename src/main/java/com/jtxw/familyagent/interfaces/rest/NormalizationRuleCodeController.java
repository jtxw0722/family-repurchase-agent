package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.NormalizationRuleCodeService;
import com.jtxw.familyagent.application.NormalizationRuleCodeService.RuleCodeSuggestion;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author: jtxw
 * @Date: 2026/11/16 19:42:51
 * @Description: 规则编码建议 REST 适配器，接收名称并返回英文建议，不承担翻译或规则写入逻辑
 */
@RestController
public class NormalizationRuleCodeController {
    /** 编码建议应用服务，不允许为空，负责名称英译和重复检查。 */
    private final NormalizationRuleCodeService ruleCodeService;

    /**
     * 创建规则编码建议入口。
     * @param ruleCodeService 编码建议应用服务
     */
    public NormalizationRuleCodeController(NormalizationRuleCodeService ruleCodeService) {
        this.ruleCodeService = ruleCodeService;
    }

    /**
     * 为新增规则表单建议英文编码，使用离线词库，不保存或修改任何规则。
     * @param request 归一化名称请求，不允许为空
     * @return 英文规则编码和重复状态
     * @throws IllegalArgumentException 请求缺失、名称无效或完整词条未收录时抛出
     * @throws IllegalStateException 词库译文无法生成合法编码时抛出简洁的人工填写提示
     */
    @Operation(summary = "建议英文规则编码", description = "根据归一化名称生成以下划线分词的英文编码，并检查重复；不写入规则库。")
    @PostMapping("/api/tools/normalization-rule-code-suggestion")
    public RuleCodeSuggestion suggest(@RequestBody RuleCodeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请填写归一化名称。");
        }
        return ruleCodeService.suggest(request.normalizedName());
    }

    /**
     * @Author: jtxw
     * @Date: 2026/11/16 19:42:51
     * @Description: 编码建议入口请求，仅承载待翻译的归一化名称
     * @param normalizedName 商品归一化名称，去空白后为 1 到 120 个字符，不允许为空
     */
    public record RuleCodeRequest(String normalizedName) {
    }
}
