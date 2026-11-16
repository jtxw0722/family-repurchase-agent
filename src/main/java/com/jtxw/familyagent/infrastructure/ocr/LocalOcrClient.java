package com.jtxw.familyagent.infrastructure.ocr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/11/10 12:10:15
 * @Description: 本地 OCR 客户端，通过显式配置的本机命令读取图片并将 stdout 转换为 OCR 原始文本
 */
@Component
@ConditionalOnProperty(
        name = "family-agent.parse-order-image.local-ocr.enabled",
        havingValue = "true"
)
public class LocalOcrClient implements OcrClient {
    /**
     * OCR 进程诊断日志，不记录图片内容或标准输出中的订单原文。
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalOcrClient.class);
    /**
     * 图片真实路径参数占位符。
     */
    private static final String IMAGE_PATH_PLACEHOLDER = "{imagePath}";
    /**
     * 返回到 warning 或错误消息中的 stderr 最大字符数。
     */
    private static final int MAX_STDERR_LENGTH = 1000;

    /**
     * 本地 OCR 配置。
     */
    private final LocalOcrProperties properties;
    /**
     * 本地 OCR 进程执行器。
     */
    private final LocalOcrProcessRunner processRunner;

    /**
     * 创建本地 OCR 客户端。
     *
     * @param properties    本地 OCR 配置
     * @param processRunner 本地 OCR 进程执行器
     */
    public LocalOcrClient(LocalOcrProperties properties, LocalOcrProcessRunner processRunner) {
        this.properties = properties;
        this.processRunner = processRunner;
    }

    /**
     * 调用本机 OCR 命令并将标准输出转换为 OCR 原始文本。
     *
     * <p>命令通过 ProcessBuilder 参数列表执行，不经过 cmd、PowerShell 或其他 shell。
     * 该方法只读取已由应用服务完成安全校验的图片，不产生数据库副作用。</p>
     *
     * @param imagePath 已完成安全校验的图片真实路径
     * @return OCR 原始文本及 stderr 警告
     * @throws IllegalStateException 配置错误、执行超时、退出码非零或 stdout 为空时抛出
     */
    @Override
    public OcrResult recognize(Path imagePath) {
        List<String> command = buildCommand(imagePath);
        int timeoutSeconds = properties.getTimeoutSeconds() > 0
                ? properties.getTimeoutSeconds() : LocalOcrProperties.DEFAULT_TIMEOUT_SECONDS;
        Charset charset = resolveCharset();
        LocalOcrProcessResult processResult = processRunner.run(
                command, Duration.ofSeconds(timeoutSeconds), charset);
        String stderr = normalizeOutput(processResult.stderr());
        if (!stderr.isBlank()) {
            // 识别成功时也可能出现引擎诊断；记录在后台供排查，页面不直接展示此类技术信息。
            LOGGER.warn("订单截图本地 OCR 进程诊断，executable={}，exitCode={}，stderr={}",
                    command.get(0), processResult.exitCode(), truncateStderr(stderr));
        }
        if (processResult.timedOut()) {
            throw new IllegalStateException("本地 OCR 执行超时：" + timeoutSeconds + " 秒。");
        }
        if (processResult.exitCode() != 0) {
            throw new IllegalStateException("本地 OCR 执行失败，exitCode=" + processResult.exitCode()
                    + "，stderr=" + truncateStderr(stderr) + "。");
        }
        String rawText = normalizeOutput(processResult.stdout());
        if (rawText.isBlank()) {
            throw new IllegalStateException("本地 OCR 未返回任何文本。");
        }
        List<String> warnings = stderr.isBlank()
                ? List.of() : List.of("本地 OCR stderr：" + truncateStderr(stderr));
        return new OcrResult(rawText, null, warnings);
    }

    /**
     * 校验命令配置并替换图片路径占位符，保留独立参数以避免经过 shell 解释。
     *
     * @param imagePath 已完成安全校验的图片路径
     * @return 可直接用于进程执行的命令参数列表
     * @throws IllegalStateException 必需配置缺失或参数无效时抛出
     */
    private List<String> buildCommand(Path imagePath) {
        String executable = properties.getExecutable();
        if (executable == null || executable.isBlank()) {
            throw new IllegalStateException("本地 OCR executable 未配置。");
        }
        List<String> arguments = properties.getArguments();
        if (arguments == null || arguments.isEmpty()) {
            throw new IllegalStateException("本地 OCR arguments 未配置。");
        }
        boolean containsEmptyArgument = arguments.stream()
                .anyMatch(argument -> argument == null || argument.isBlank());
        if (containsEmptyArgument) {
            throw new IllegalStateException("本地 OCR arguments 不允许包含空参数。");
        }
        boolean containsImagePathPlaceholder = arguments.stream()
                .anyMatch(argument -> argument.contains(IMAGE_PATH_PLACEHOLDER));
        if (!containsImagePathPlaceholder) {
            throw new IllegalStateException("本地 OCR arguments 必须包含 {imagePath} 占位符。");
        }
        List<String> command = new ArrayList<>();
        command.add(executable.trim());
        for (String argument : arguments) {
            command.add(argument.replace(IMAGE_PATH_PLACEHOLDER, imagePath.toString()));
        }
        return List.copyOf(command);
    }

    /**
     * 解析进程输出字符集，未配置时使用 UTF-8。
     *
     * @return 有效的输出字符集
     * @throws IllegalStateException 配置的字符集不可用时抛出
     */
    private Charset resolveCharset() {
        String charsetName = properties.getCharset();
        try {
            return Charset.forName(charsetName == null || charsetName.isBlank()
                    ? LocalOcrProperties.DEFAULT_CHARSET : charsetName.trim());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException exception) {
            throw new IllegalStateException("本地 OCR charset 配置无效：" + charsetName + "。", exception);
        }
    }

    /**
     * 去除进程输出首尾空白，空输出统一为空文本。
     *
     * @param output 进程原始输出，允许为空
     * @return 可用于判空的规范文本
     */
    private String normalizeOutput(String output) {
        return output == null ? "" : output.trim();
    }

    /**
     * 限制进程诊断摘要长度，避免日志及接口被大量错误输出占满。
     *
     * @param stderr 已完成空值处理的错误输出
     * @return 不超过配置常量长度的诊断摘要
     */
    private String truncateStderr(String stderr) {
        return stderr.substring(0, Math.min(stderr.length(), MAX_STDERR_LENGTH));
    }
}
