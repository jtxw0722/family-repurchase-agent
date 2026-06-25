package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.ParseOrderImageApplicationService;
import com.jtxw.familyagent.domain.model.ParseOrderImageResult;
import com.jtxw.familyagent.interfaces.rest.request.ParseOrderImageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author: jtxw
 * @Date: 2026/06/19 21:02:00
 * @Description: 订单截图解析工具 Controller，负责 REST 参数校验并返回待用户确认的候选样本
 */
@Tag(name = "Agent Tool API", description = "家庭复购品价格决策工具接口")
@RestController
@RequestMapping("/api/tools")
public class ParseOrderImageToolController {
    /** 订单截图解析应用服务，不包含购买记录写入依赖。 */
    private final ParseOrderImageApplicationService parseOrderImageApplicationService;

    /**
     * 创建订单截图解析工具 Controller。
     *
     * @param parseOrderImageApplicationService 订单截图解析应用服务
     */
    public ParseOrderImageToolController(ParseOrderImageApplicationService parseOrderImageApplicationService) {
        this.parseOrderImageApplicationService = parseOrderImageApplicationService;
    }

    /**
     * 解析允许目录内的本地订单图片或前端 Base64 订单图片并返回候选样本。
     *
     * <p>该接口无论 dryRun 取值如何都不会写入 purchase_records。</p>
     *
     * @param request 订单截图解析请求，imageBase64 和 imagePath 至少一个不为空
     * @return OCR 原文、候选样本和解析警告
     */
    @Operation(summary = "解析订单截图候选样本", description = "解析本地路径或 Base64 订单图片，只返回候选样本，不写入购买记录。")
    @PostMapping("/parse-order-image")
    public ParseOrderImageResult parseOrderImage(@Valid @RequestBody ParseOrderImageRequest request) {
        return parseOrderImageApplicationService.parse(request.toCommand());
    }
}
