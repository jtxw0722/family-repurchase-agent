package com.jtxw.familyagent.infrastructure.ocr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @Author: jtxw
 * @Date: 2026/06/18 23:15:00
 * @Description: 本地 OCR 客户端单元测试，通过 fake 进程执行器覆盖命令构建、配置校验和执行结果处理
 */
class LocalOcrClientTest {
    /** 合成图片路径，不要求文件真实存在。 */
    private static final Path IMAGE_PATH = Path.of("data/inbox/screenshots/order test.png").toAbsolutePath();

    /** 每个测试独立使用的本地 OCR 配置。 */
    private LocalOcrProperties properties;
    /** 捕获命令并返回预设结果的 fake 进程执行器。 */
    private CapturingRunner runner;
    /** 待测试的本地 OCR 客户端。 */
    private LocalOcrClient client;

    @BeforeEach
    void setUp() {
        properties = new LocalOcrProperties();
        properties.setExecutable("python");
        properties.setArguments(List.of("scripts/local-ocr.py", "--image", "{imagePath}"));
        runner = new CapturingRunner(new LocalOcrProcessResult(0, false, "合成 OCR 文本\n", ""));
        client = new LocalOcrClient(properties, runner);
    }

    @Test
    void shouldReplaceImagePathPlaceholder() {
        client.recognize(IMAGE_PATH);

        assertThat(runner.capturedCommand())
                .containsExactly("python", "scripts/local-ocr.py", "--image", IMAGE_PATH.toString());
        assertThat(runner.capturedTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(runner.capturedCharset()).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void shouldUseDefaultCommandWhenPropertiesNotOverridden() {
        LocalOcrProperties defaultProperties = new LocalOcrProperties();
        CapturingRunner defaultRunner = new CapturingRunner(
                new LocalOcrProcessResult(0, false, "合成 OCR 文本", ""));
        LocalOcrClient defaultClient = new LocalOcrClient(defaultProperties, defaultRunner);

        defaultClient.recognize(IMAGE_PATH);

        assertThat(defaultRunner.capturedCommand())
                .containsExactly("python", "scripts/local-ocr.py", "--image", IMAGE_PATH.toString());
        assertThat(defaultRunner.capturedTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaultRunner.capturedCharset()).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void shouldReturnRawTextFromStdout() {
        OcrResult result = client.recognize(IMAGE_PATH);

        assertThat(result.rawText()).isEqualTo("合成 OCR 文本");
        assertThat(result.confidence()).isNull();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void shouldAttachStderrAsWarning() {
        runner.setResult(new LocalOcrProcessResult(0, false, "合成 OCR 文本", "模型使用 CPU"));

        OcrResult result = client.recognize(IMAGE_PATH);

        assertThat(result.warnings()).containsExactly("本地 OCR stderr：模型使用 CPU");
    }

    @Test
    void shouldRejectMissingExecutable() {
        properties.setExecutable(" ");

        assertThatThrownBy(() -> client.recognize(IMAGE_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本地 OCR executable 未配置。");
    }

    @Test
    void shouldRejectMissingArguments() {
        properties.setArguments(List.of());

        assertThatThrownBy(() -> client.recognize(IMAGE_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本地 OCR arguments 未配置。");
    }

    @Test
    void shouldRejectArgumentsWithoutImagePathPlaceholder() {
        properties.setArguments(List.of("scripts/local-ocr.py", "--image", "fixed.png"));

        assertThatThrownBy(() -> client.recognize(IMAGE_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本地 OCR arguments 必须包含 {imagePath} 占位符。");
    }

    @Test
    void shouldRejectNullArgument() {
        List<String> arguments = new ArrayList<>();
        arguments.add("scripts/local-ocr.py");
        arguments.add(null);
        arguments.add("{imagePath}");
        properties.setArguments(arguments);

        assertThatThrownBy(() -> client.recognize(IMAGE_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本地 OCR arguments 不允许包含空参数。");
    }

    @Test
    void shouldRejectBlankArgument() {
        properties.setArguments(List.of("scripts/local-ocr.py", " ", "{imagePath}"));

        assertThatThrownBy(() -> client.recognize(IMAGE_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本地 OCR arguments 不允许包含空参数。");
    }

    @Test
    void shouldRejectTimeout() {
        properties.setTimeoutSeconds(5);
        runner.setResult(new LocalOcrProcessResult(-1, true, "", ""));

        assertThatThrownBy(() -> client.recognize(IMAGE_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本地 OCR 执行超时：5 秒。");
    }

    @Test
    void shouldRejectNonZeroExitCode() {
        runner.setResult(new LocalOcrProcessResult(2, false, "", "识别引擎失败"));

        assertThatThrownBy(() -> client.recognize(IMAGE_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本地 OCR 执行失败，exitCode=2，stderr=识别引擎失败。");
    }

    @Test
    void shouldRejectBlankStdout() {
        runner.setResult(new LocalOcrProcessResult(0, false, "  ", ""));

        assertThatThrownBy(() -> client.recognize(IMAGE_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本地 OCR 未返回任何文本。");
    }

    @Test
    void shouldRejectInvalidCharset() {
        properties.setCharset("invalid charset name");

        assertThatThrownBy(() -> client.recognize(IMAGE_PATH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("本地 OCR charset 配置无效：invalid charset name。");
    }

    /**
     * 捕获 LocalOcrClient 构建的命令和执行参数，并返回测试预设结果。
     */
    private static class CapturingRunner implements LocalOcrProcessRunner {
        /** 当前测试预设的进程执行结果。 */
        private LocalOcrProcessResult result;
        /** LocalOcrClient 传入的最终命令列表。 */
        private List<String> capturedCommand;
        /** LocalOcrClient 传入的超时时间。 */
        private Duration capturedTimeout;
        /** LocalOcrClient 传入的输出字符集。 */
        private Charset capturedCharset;

        private CapturingRunner(LocalOcrProcessResult result) {
            this.result = result;
        }

        @Override
        public LocalOcrProcessResult run(List<String> command, Duration timeout, Charset charset) {
            capturedCommand = command;
            capturedTimeout = timeout;
            capturedCharset = charset;
            return result;
        }

        private void setResult(LocalOcrProcessResult result) {
            this.result = result;
        }

        private List<String> capturedCommand() {
            return capturedCommand;
        }

        private Duration capturedTimeout() {
            return capturedTimeout;
        }

        private Charset capturedCharset() {
            return capturedCharset;
        }
    }
}
