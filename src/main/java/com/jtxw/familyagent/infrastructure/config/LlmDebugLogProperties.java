package com.jtxw.familyagent.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @Author: jtxw
 * @Date: 2026/06/24 08:04:40
 * @Description: 统一 LLM debug 日志配置，负责承载所有大模型调用链路的本地 dump 开关和目录
 */
@ConfigurationProperties(prefix = "family-agent.llm")
public class LlmDebugLogProperties {
    /**
     * 是否生成 LLM debug dump 文件，默认 false，关闭时不会创建目录或写入本地文件。
     */
    private boolean debugLogEnabled = false;
    /**
     * LLM debug dump 文件目录，允许使用相对路径，默认写入项目运行目录下的 logs/llm-debug。
     */
    private String debugLogDir = "logs/llm-debug";

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
