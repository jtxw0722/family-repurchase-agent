package com.jtxw.familyagent.interfaces.rest;

import com.jtxw.familyagent.application.NormalizationRuleCodeService;
import com.jtxw.familyagent.application.NormalizationLibraryService;
import com.jtxw.familyagent.application.NormalizationRuleCodeService.RuleCodeSuggestion;
import com.jtxw.familyagent.infrastructure.translation.OfflineRuleCodeTranslator;
import com.jtxw.familyagent.interfaces.rest.advice.RestExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @Author: jtxw
 * @Date: 2026/11/16 19:42:51
 * @Description: 编码建议 REST 适配器测试，验证建议响应、真实离线词库调用及缺词错误的 HTTP 契约
 */
@WebMvcTest(NormalizationRuleCodeController.class)
class NormalizationRuleCodeControllerTest {
    /** MVC 测试客户端，不启动真实 HTTP 服务。 */
    @Autowired
    private MockMvc mockMvc;
    /** 编码建议应用服务替身，不触发模型请求或数据库访问。 */
    @MockitoBean
    private NormalizationRuleCodeService service;

    @Test
    void shouldReturnCodeAndDuplicateState() throws Exception {
        when(service.suggest("大米")).thenReturn(new RuleCodeSuggestion("rice", true));
        mockMvc.perform(post("/api/tools/normalization-rule-code-suggestion")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"normalizedName\":\"大米\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value("rice"))
                .andExpect(jsonPath("$.exists").value(true));
    }

    @Test
    void shouldTranslateAndReportMissingEntriesThroughRealOfflineService() throws Exception {
        NormalizationLibraryService library = mock(NormalizationLibraryService.class);
        when(library.listLibraryItems()).thenReturn(List.of());
        NormalizationRuleCodeService offlineService = new NormalizationRuleCodeService(
                library, new OfflineRuleCodeTranslator());
        MockMvc offlineMvc = MockMvcBuilders.standaloneSetup(new NormalizationRuleCodeController(offlineService))
                .setControllerAdvice(new RestExceptionHandler()).build();
        offlineMvc.perform(post("/api/tools/normalization-rule-code-suggestion")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"normalizedName\":\"橄榄油\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value("olive_oil"))
                .andExpect(jsonPath("$.exists").value(false));
        offlineMvc.perform(post("/api/tools/normalization-rule-code-suggestion")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"normalizedName\":\"未知商品词库测试\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("离线词库未收录该完整名称或译义不明确，请手动填写英文编码。"));
    }

    @Test
    void shouldReturnFriendlyFailureWithoutProviderDetails() throws Exception {
        when(service.suggest("大米")).thenThrow(
                new IllegalStateException("暂时无法生成英文编码，请手动填写或稍后重试。"));
        mockMvc.perform(post("/api/tools/normalization-rule-code-suggestion")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"normalizedName\":\"大米\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("暂时无法生成英文编码，请手动填写或稍后重试。"));
    }
}
