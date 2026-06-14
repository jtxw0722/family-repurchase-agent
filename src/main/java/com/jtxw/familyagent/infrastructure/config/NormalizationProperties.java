package com.jtxw.familyagent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 11:45:16
 * @Description: 商品归一化配置，控制规则维护建议链路的通用 LLM 参数。
 */
@ConfigurationProperties(prefix = "family-agent.normalization")
public class NormalizationProperties {
    /**
     * LLM 调用配置。
     */
    private Llm llm = new Llm();

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm == null ? new Llm() : llm;
    }

    /**
     * 判断 legacy_fallback 是否应立即创建商品归一化复核项。
     *
     * @return 固定返回 true，表示低置信兜底样本立即进入人工复核
     */
    public boolean immediateFallbackReview() {
        return true;
    }

    /**
     * @Author: jtxw
     * @Date: 2026/06/07 21:07:05
     * @Description: Normalization LLM 调用配置，供规则维护建议链路构造请求和访问模型。
     */
    public static class Llm {
        /**
         * 是否启用 LLM 规则维护建议；关闭时规则建议任务返回明确错误。
         */
        private boolean enabled = false;
        /**
         * LLM 服务提供方，当前第一版支持 openai-compatible 和 openai。
         */
        private String provider = "openai-compatible";
        /**
         * LLM 服务基础地址，不包含具体 endpoint path。
         */
        private String baseUrl = "https://api.openai.com/v1";
        /**
         * LLM 模型名称。
         */
        private String model = "gpt-4.1-mini";
        /**
         * LLM 最大输出 token 数；0 表示不传 max_tokens，大于 0 时写入 OpenAI-compatible request body。
         */
        private int maxTokens = 0;
        /**
         * provider 专属 OpenAI-compatible request body 扩展 JSON；必须是对象，且不能包含鉴权或覆盖核心字段。
         */
        private String extraBodyJson = "";
        /**
         * LLM API Key，默认读取环境变量占位配置。
         */
        private String apiKey = "";
        /**
         * 请求超时时间，单位秒。
         */
        private int requestTimeoutSeconds = 30;
        /**
         * 是否写入 LLM 调试 dump 文件，默认关闭。
         */
        private boolean debugLogEnabled = false;
        /**
         * LLM 调试 dump 文件目录。
         */
        private String debugLogDir = "logs/llm-debug";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public String getExtraBodyJson() {
            return extraBodyJson;
        }

        public void setExtraBodyJson(String extraBodyJson) {
            this.extraBodyJson = extraBodyJson;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }

        public boolean isDebugLogEnabled() {
            return debugLogEnabled;
        }

        public void setDebugLogEnabled(boolean debugLogEnabled) {
            this.debugLogEnabled = debugLogEnabled;
        }

        public String getDebugLogDir() {
            return debugLogDir;
        }

        public void setDebugLogDir(String debugLogDir) {
            this.debugLogDir = debugLogDir;
        }

    }
}
