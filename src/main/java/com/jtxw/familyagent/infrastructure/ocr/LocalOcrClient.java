package com.jtxw.familyagent.infrastructure.ocr;

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
 * @Date: 2026/06/18 20:32:00
 * @Description: 本地 OCR 客户端，通过显式配置的本机命令读取图片并将 stdout 转换为 OCR 原始文本
 */
@Component
@ConditionalOnProperty(
        name = "family-agent.parse-order-image.local-ocr.enabled",
        havingValue = "true"
)
public class LocalOcrClient implements OcrClient {
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

    private Charset resolveCharset() {
        String charsetName = properties.getCharset();
        try {
            return Charset.forName(charsetName == null || charsetName.isBlank()
                    ? LocalOcrProperties.DEFAULT_CHARSET : charsetName.trim());
        } catch (IllegalCharsetNameException | UnsupportedCharsetException exception) {
            throw new IllegalStateException("本地 OCR charset 配置无效：" + charsetName + "。", exception);
        }
    }

    private String normalizeOutput(String output) {
        return output == null ? "" : output.trim();
    }

    private String truncateStderr(String stderr) {
        return stderr.substring(0, Math.min(stderr.length(), MAX_STDERR_LENGTH));
    }
}
