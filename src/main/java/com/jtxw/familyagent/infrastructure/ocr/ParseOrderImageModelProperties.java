package com.jtxw.familyagent.infrastructure.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Author: jtxw
 * @Date: 2026/06/20 09:47:26
 * @Description: 订单截图视觉模型配置，定义外部模型开关、连接参数、超时和本地 OCR 兜底策略
 */
@Component
@ConfigurationProperties(prefix = "family-agent.parse-order-image.model")
public class ParseOrderImageModelProperties {
    /**
     * 默认 OpenAI-compatible 服务提供方标识。
     */
    static final String DEFAULT_PROVIDER = "openai-compatible";
    /**
     * 默认视觉模型请求超时时间，单位秒。
     */
    static final int DEFAULT_TIMEOUT_SECONDS = 60;
    /**
     * 默认允许上传的图片大小，单位字节，默认 10 MiB。
     */
    static final long DEFAULT_MAX_IMAGE_BYTES = 10L * 1024L * 1024L;

    /**
     * 是否启用视觉模型识别，默认 false，关闭时不会向外部服务发送图片。
     */
    private boolean enabled;
    /**
     * 视觉模型服务提供方，当前仅支持 openai-compatible。
     */
    private String provider = DEFAULT_PROVIDER;
    /**
     * OpenAI-compatible 服务基础地址，启用模型时不允许为空。
     */
    private String baseUrl;
    /**
     * 模型服务 API Key，启用模型时不允许为空，禁止输出到日志或异常。
     */
    private String apiKey;
    /**
     * 支持图片输入的模型名称，启用模型时不允许为空。
     */
    private String modelName;
    /**
     * 模型连接和读取超时时间，单位秒，非正数按默认 60 秒处理。
     */
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    /**
     * 模型识别失败时是否回退现有本地 OCR，默认 true。
     */
    private boolean fallbackToLocalOcr = true;
    /**
     * 允许发送给模型的最大图片大小，单位字节，非正数按默认 10 MiB 处理。
     */
    private long maxImageBytes = DEFAULT_MAX_IMAGE_BYTES;

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

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean isFallbackToLocalOcr() {
        return fallbackToLocalOcr;
    }

    public void setFallbackToLocalOcr(boolean fallbackToLocalOcr) {
        this.fallbackToLocalOcr = fallbackToLocalOcr;
    }

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(long maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }
}
