package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.OnlineOrderImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @Author: jtxw
 * @Date: 2026/11/15 12:51:46
 * @Description: 在线导入 REST 隐私边界测试，验证禁用缓存、异常不回显原文、上传对象日志脱敏及确认接口
 */
@WebMvcTest(OnlineOrderImportController.class)
class OnlineOrderImportControllerTest {
    /** 隔离 MVC 请求客户端。 */
    @Autowired private MockMvc mvc;
    /** 应用服务替身，防止协议测试触发数据库写入。 */
    @MockitoBean private OnlineOrderImportService service;

    /** 预览只调用只读方法，响应必须禁止缓存。 */
    @Test void previewMustDisableCaching() throws Exception {
        when(service.preview(anyString(), anyString(), anyString())).thenReturn(new OnlineOrderImportService.Preview(List.of(), 1));
        mvc.perform(post("/api/tools/online-order-import/preview").contentType("application/json")
                .content("{\"contentBase64\":\"QQ==\",\"format\":\"csv\",\"owner\":\"tester\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.rows").isEmpty()).andExpect(jsonPath("$.skippedRows").value(1));
        verify(service, never()).commit(any());
        org.assertj.core.api.Assertions.assertThat(new OnlineOrderImportController.Upload("SECRET_BODY", "csv", "tester").toString()).doesNotContain("SECRET_BODY");
    }

    /** 底层解析异常中的个人信息不得进入 HTTP 响应。 */
    @Test void exceptionsMustNotExposeOriginalContent() throws Exception {
        when(service.preview(anyString(), anyString(), anyString())).thenThrow(new IllegalArgumentException("SECRET_ADDRESS 13800000000"));
        mvc.perform(post("/api/tools/online-order-import/preview").contentType("application/json")
                .content("{\"contentBase64\":\"QQ==\",\"format\":\"csv\",\"owner\":\"tester\"}"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET_ADDRESS"))));
    }

    /** 确认接口不接收原文件，结果仅返回保存或跳过的行号。 */
    @Test void commitMustOnlyReturnBusinessResult() throws Exception {
        when(service.commit(any())).thenReturn(new OnlineOrderImportService.CommitResult(List.of(2), List.of(3)));
        mvc.perform(post("/api/tools/online-order-import/commit").contentType("application/json").content("[]"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.savedRows[0]").value(2)).andExpect(jsonPath("$.duplicateRows[0]").value(3));
    }
}
