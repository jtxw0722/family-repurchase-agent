package com.jtxw.familyagent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @Author: jtxw
 * @Date: 2026/06/06 11:45:16
 * @Description: 商品归一化配置，控制 legacy_fallback 复核模式和 LLM Advisor 参数。
 */
@ConfigurationProperties(prefix = "family-agent.normalization")
public class NormalizationProperties {
    /**
     * legacy_fallback 复核模式，默认进入 LLM suggestion 后处理链路。
     */
    private String fallbackReviewMode = "llm_suggestion";
    /**
     * LLM Advisor 配置。
     */
    private Llm llm = new Llm();

    public String getFallbackReviewMode() {
        return fallbackReviewMode;
    }

    public void setFallbackReviewMode(String fallbackReviewMode) {
        this.fallbackReviewMode = fallbackReviewMode;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm == null ? new Llm() : llm;
    }

    /**
     * 判断 legacy_fallback 是否应立即创建商品归一化复核项。
     *
     * @return true 表示保持旧行为立即创建 PRODUCT_NAME_NORMALIZATION_REVIEW
     */
    public boolean immediateFallbackReview() {
        return "immediate_review".equalsIgnoreCase(safeMode());
    }

    private String safeMode() {
        return fallbackReviewMode == null || fallbackReviewMode.isBlank()
                ? "llm_suggestion"
                : fallbackReviewMode.trim();
    }

    /**
     * @Author: jtxw
     * @Date: 2026/06/07 21:07:05
     * @Description: Normalization LLM Advisor 调用和阈值配置。
     */
    public static class Llm {
        /**
         * 是否启用 LLM Advisor；关闭时导入不受影响，分析接口返回明确错误。
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
         * 提示词版本，用于审计和回放。
         */
        private String promptVersion = "normalization-v1";
        /**
         * 请求超时时间，单位秒。
         */
        private int requestTimeoutSeconds = 30;
        /**
         * 单次 LLM 请求最多处理的商品数量，默认 10，服务层会限制最大 20。
         */
        private int batchSize = 10;
        /**
         * 高置信 EXCLUDE 阈值，默认 0.9。
         */
        private double excludeConfidenceThreshold = 0.9D;
        /**
         * 高置信 NORMALIZE 阈值，默认 0.9。
         */
        private double normalizeConfidenceThreshold = 0.9D;
        /**
         * REVIEW 参考阈值，默认 0.85。
         */
        private double reviewConfidenceThreshold = 0.85D;
        /**
         * 是否写入 LLM 调试 dump 文件，默认关闭。
         */
        private boolean debugLogEnabled = false;
        /**
         * 是否在调试 dump 中写入完整 prompt / request body。
         */
        private boolean debugLogFullPrompt = false;
        /**
         * 是否在调试 dump 中写入完整 response body 和提取后的模型内容。
         */
        private boolean debugLogFullResponse = false;
        /**
         * LLM 调试 dump 文件目录。
         */
        private String debugLogDir = "logs/llm-debug";
        /**
         * 调试 dump 中 response body 最大保留字符数。
         */
        private int debugMaxResponseChars = 8000;

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

        public String getPromptVersion() {
            return promptVersion;
        }

        public void setPromptVersion(String promptVersion) {
            this.promptVersion = promptVersion;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public double getExcludeConfidenceThreshold() {
            return excludeConfidenceThreshold;
        }

        public void setExcludeConfidenceThreshold(double excludeConfidenceThreshold) {
            this.excludeConfidenceThreshold = excludeConfidenceThreshold;
        }

        public double getNormalizeConfidenceThreshold() {
            return normalizeConfidenceThreshold;
        }

        public void setNormalizeConfidenceThreshold(double normalizeConfidenceThreshold) {
            this.normalizeConfidenceThreshold = normalizeConfidenceThreshold;
        }

        public double getReviewConfidenceThreshold() {
            return reviewConfidenceThreshold;
        }

        public void setReviewConfidenceThreshold(double reviewConfidenceThreshold) {
            this.reviewConfidenceThreshold = reviewConfidenceThreshold;
        }

        public boolean isDebugLogEnabled() {
            return debugLogEnabled;
        }

        public void setDebugLogEnabled(boolean debugLogEnabled) {
            this.debugLogEnabled = debugLogEnabled;
        }

        public boolean isDebugLogFullPrompt() {
            return debugLogFullPrompt;
        }

        public void setDebugLogFullPrompt(boolean debugLogFullPrompt) {
            this.debugLogFullPrompt = debugLogFullPrompt;
        }

        public boolean isDebugLogFullResponse() {
            return debugLogFullResponse;
        }

        public void setDebugLogFullResponse(boolean debugLogFullResponse) {
            this.debugLogFullResponse = debugLogFullResponse;
        }

        public String getDebugLogDir() {
            return debugLogDir;
        }

        public void setDebugLogDir(String debugLogDir) {
            this.debugLogDir = debugLogDir;
        }

        public int getDebugMaxResponseChars() {
            return debugMaxResponseChars;
        }

        public void setDebugMaxResponseChars(int debugMaxResponseChars) {
            this.debugMaxResponseChars = debugMaxResponseChars;
        }
    }
}
