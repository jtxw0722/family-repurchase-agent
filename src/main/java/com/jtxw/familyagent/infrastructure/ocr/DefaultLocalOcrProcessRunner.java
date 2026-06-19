package com.jtxw.familyagent.infrastructure.ocr;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author: jtxw
 * @Date: 2026/06/18 19:44:00
 * @Description: 默认本地 OCR 进程执行器，使用 ProcessBuilder 安全传参并并发读取标准输出和错误输出
 */
@Component
@ConditionalOnProperty(
        name = "family-agent.parse-order-image.local-ocr.enabled",
        havingValue = "true"
)
public class DefaultLocalOcrProcessRunner implements LocalOcrProcessRunner {
    /**
     * 常驻的输出读取线程数，可同时服务一个 OCR 进程的 stdout 和 stderr。
     */
    private static final int CORE_OUTPUT_THREADS = 2;
    /**
     * 输出读取线程上限，限制并发 OCR 进程对 JVM 线程的占用。
     */
    private static final int MAX_OUTPUT_THREADS = 8;
    /**
     * 空闲输出读取线程的保留时间，单位秒。
     */
    private static final int OUTPUT_THREAD_KEEP_ALIVE_SECONDS = 60;

    /**
     * 受控的 OCR 进程输出读取线程池。
     */
    private final ExecutorService outputExecutor;

    /**
     * 创建默认本地 OCR 进程执行器，并初始化有上限的命名 daemon 线程池。
     */
    public DefaultLocalOcrProcessRunner() {
        outputExecutor = new ThreadPoolExecutor(
                CORE_OUTPUT_THREADS,
                MAX_OUTPUT_THREADS,
                OUTPUT_THREAD_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new LocalOcrOutputThreadFactory(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 执行本地 OCR 命令，并在超时后强制终止进程。
     *
     * <p>stdout 和 stderr 必须并发读取，避免任一操作系统管道写满后阻塞 OCR 进程。</p>
     *
     * @param command 可执行程序和独立参数组成的命令列表
     * @param timeout 最大执行时间
     * @param charset 输出解码字符集
     * @return 包含退出码、超时状态、stdout 和 stderr 的执行结果
     */
    @Override
    public LocalOcrProcessResult run(List<String> command, Duration timeout, Charset charset) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new IllegalStateException("本地 OCR 进程启动失败：" + exception.getMessage(), exception);
        }

        CompletableFuture<String> stdoutFuture = readAsync(process.getInputStream(), charset);
        CompletableFuture<String> stderrFuture = readAsync(process.getErrorStream(), charset);
        boolean timedOut;
        try {
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            timedOut = !completed;
            if (timedOut) {
                process.destroyForcibly();
                process.waitFor();
            }
            return new LocalOcrProcessResult(process.exitValue(), timedOut,
                    joinOutput(stdoutFuture), joinOutput(stderrFuture));
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("本地 OCR 执行被中断。", exception);
        }
    }

    private CompletableFuture<String> readAsync(InputStream inputStream, Charset charset) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream stream = inputStream) {
                return new String(stream.readAllBytes(), charset);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, outputExecutor);
    }

    private String joinOutput(CompletableFuture<String> outputFuture) {
        try {
            return outputFuture.join();
        } catch (CompletionException exception) {
            throw new IllegalStateException("读取本地 OCR 进程输出失败。", exception.getCause());
        }
    }

    /**
     * 关闭输出读取线程池，避免 Spring 应用停止后保留线程资源。
     */
    @PreDestroy
    public void shutdown() {
        outputExecutor.shutdownNow();
    }

    /**
     * @Author: jtxw
     * @Date: 2026/06/18 08:45:00
     * @Description: 本地 OCR 输出读取线程工厂，生成可识别且不阻止 JVM 退出的 daemon 线程
     */
    private static class LocalOcrOutputThreadFactory implements ThreadFactory {
        /**
         * OCR 输出读取线程的递增编号。
         */
        private final AtomicInteger threadSequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "local-ocr-output-" + threadSequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
