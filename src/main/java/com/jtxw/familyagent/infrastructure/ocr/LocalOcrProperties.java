package com.jtxw.familyagent.infrastructure.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/18 19:21:00
 * @Description: 本地 OCR 命令行配置，定义启用开关、可执行程序、参数、超时和输出字符集
 */
@Component
@ConfigurationProperties(prefix = "family-agent.parse-order-image.local-ocr")
public class LocalOcrProperties {
    /**
     * 默认本地 OCR 可执行程序。
     */
    static final String DEFAULT_EXECUTABLE = "python";
    /**
     * 默认本地 OCR 参数，约定由用户自行提供 scripts/local-ocr.py。
     */
    static final List<String> DEFAULT_ARGUMENTS = List.of(
            "scripts/local-ocr.py", "--image", "{imagePath}"
    );
    /**
     * 默认本地 OCR 执行超时时间，单位秒。
     */
    static final int DEFAULT_TIMEOUT_SECONDS = 30;
    /**
     * 默认本地 OCR 输出字符集。
     */
    static final String DEFAULT_CHARSET = "UTF-8";

    /**
     * 是否启用本地 OCR 命令行适配器，默认关闭。
     */
    private boolean enabled = false;
    /**
     * 本地 OCR 可执行程序名称或绝对路径，启用时不允许为空。
     */
    private String executable = DEFAULT_EXECUTABLE;
    /**
     * 本地 OCR 参数列表，必须包含 {imagePath} 占位符。
     */
    private List<String> arguments = new ArrayList<>(DEFAULT_ARGUMENTS);
    /**
     * 本地 OCR 最大执行时间，单位秒，非正数按默认 30 秒处理。
     */
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    /**
     * stdout 和 stderr 解码字符集，默认 UTF-8。
     */
    private String charset = DEFAULT_CHARSET;

    /**
     * @return 是否启用本地 OCR
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled 是否启用本地 OCR
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return 本地 OCR 可执行程序
     */
    public String getExecutable() {
        return executable;
    }

    /**
     * @param executable 本地 OCR 可执行程序名称或路径
     */
    public void setExecutable(String executable) {
        this.executable = executable;
    }

    /**
     * @return 本地 OCR 参数列表
     */
    public List<String> getArguments() {
        return arguments;
    }

    /**
     * 设置本地 OCR 参数列表。
     *
     * @param arguments 参数列表；null 转换为空列表
     */
    public void setArguments(List<String> arguments) {
        this.arguments = arguments == null ? new ArrayList<>() : new ArrayList<>(arguments);
    }

    /**
     * @return 最大执行时间，单位秒
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * @param timeoutSeconds 最大执行时间，单位秒
     */
    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * @return stdout 和 stderr 解码字符集名称
     */
    public String getCharset() {
        return charset;
    }

    /**
     * @param charset stdout 和 stderr 解码字符集名称
     */
    public void setCharset(String charset) {
        this.charset = charset;
    }
}
