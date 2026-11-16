package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.OnlineOrderImportService;
import com.jtxw.familyagent.domain.model.OnlineImportRow;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * @Author: jtxw
 * @Date: 2026/11/15 12:51:46
 * @Description: 在线订单 REST 适配层，接收不含文件名的内存上传与确认行，禁用响应缓存并屏蔽可能带原文的异常
 */
@RestController
@RequestMapping("/api/tools/online-order-import")
public class OnlineOrderImportController {
    /** 在线订单应用编排服务，不在协议层直接访问仓储。 */
    private final OnlineOrderImportService service;

    /** 创建在线导入协议入口，不执行解析或写入。 */
    public OnlineOrderImportController(OnlineOrderImportService service) { this.service = service; }

    /**
     * 接收内存文件内容并只读生成预览；不使用 multipart，避免容器把上传内容暂存磁盘。
     * @param upload 内容、格式与归属人，不包含文件名
     * @param response HTTP 响应，禁止缓存业务预览
     * @return 字段白名单预览；读取失败仅返回通用提示
     */
    @PostMapping("/preview")
    public OnlineOrderImportService.Preview preview(@RequestBody Upload upload, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.preview(upload.contentBase64(), upload.format(), upload.owner());
    }

    /**
     * 确认用户选中的业务数据；事务保存前重新校验及查重。
     * @param rows 编辑后的业务行，不包含上传内容
     * @param response HTTP 响应，禁止缓存
     * @return 实际保存、跳过重复的行号
     */
    @PostMapping("/commit")
    public OnlineOrderImportService.CommitResult commit(@RequestBody List<OnlineImportRow> rows, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return service.commit(rows);
    }

    /**
     * 屏蔽底层异常原文，避免文件单元格、Base64 或数据库参数被响应或常规异常日志记录。
     * @param exception 请求解析、校验或数据库异常
     * @return 不包含异常原文的错误响应；业务字段校验错误允许返回固定的行号提示
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleFailure(Exception exception) {
        String message = "处理未完成，请检查文件格式、行数及勾选行的必填数据；没有原文件留存。";
        if (exception instanceof IllegalArgumentException && exception.getMessage() != null
                && exception.getMessage().matches("第 \\d+ 行：[\\s\\S]*")) {
            message = exception.getMessage();
        }
        return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(Map.of("error", message));
    }

    /**
     * @Author: jtxw
     * @Date: 2026/11/15 12:51:46
     * @Description: 在线内存上传协议对象，无原文件名或路径，不允许通过字符串表示泄露上传内容
     * @param contentBase64 文件内容，最多 10 MiB 解码字节，仅本次请求使用
     * @param format csv 或 xlsx
     * @param owner 用户指定的家庭归属人
     */
    public record Upload(String contentBase64, String format, String owner) {
        /** 返回固定标记，避免日志意外打印完整上传内容。 */
        @Override public String toString() { return "OnlineUpload[redacted]"; }
    }
}
