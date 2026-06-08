package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.ImportApplicationService;
import com.jtxw.familyagent.domain.model.ImportResult;
import com.jtxw.familyagent.interfaces.rest.request.ImportFileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Author: jtxw
 * @Date: 2026/06/08 17:45:00
 * @Description: 订单文件导入工具 Controller，暴露 CSV/Excel 订单文件导入接口。
 */
@Tag(name = "Agent Tool API", description = "家庭复购品价格决策工具接口")
@RestController
@RequestMapping("/api/tools")
public class ImportToolController {
    /**
     * 文件导入应用服务，负责 CSV/Excel 订单导入。
     */
    private final ImportApplicationService importApplicationService;

    /**
     * 创建订单文件导入工具 Controller。
     *
     * @param importApplicationService 文件导入应用服务
     */
    public ImportToolController(ImportApplicationService importApplicationService) {
        this.importApplicationService = importApplicationService;
    }

    /**
     * 导入本地订单文件。
     *
     * <p>该接口只读取用户提供的本地文件路径，不会访问电商平台、不会读取浏览器 Cookie，
     * 也不会上传订单数据。导入过程会写入本地 SQLite，并可能生成待复核记录。</p>
     *
     * @param request 文件导入请求
     * @return 导入结果，包括导入记录数和待复核记录数
     */
    @Operation(summary = "导入订单文件", description = "导入本地 CSV 或 Excel 订单文件，并生成购买记录和待复核记录。")
    @PostMapping("/import-file")
    public ImportResult importFile(@Valid @RequestBody ImportFileRequest request) {
        return importApplicationService.importFile(request.toCommand());
    }
}
