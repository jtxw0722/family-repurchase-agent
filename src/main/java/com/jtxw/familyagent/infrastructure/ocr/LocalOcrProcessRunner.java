package com.jtxw.familyagent.infrastructure.ocr;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;

/**
 * @Author: jtxw
 * @Date: 2026/06/18 19:03:00
 * @Description: 本地 OCR 进程执行抽象，负责按参数列表运行命令并返回标准输出、错误输出和终止状态
 */
public interface LocalOcrProcessRunner {
    /**
     * 在指定超时时间内执行本地 OCR 命令。
     *
     * @param command 可执行程序和独立参数组成的命令列表，不通过 shell 执行
     * @param timeout 最大执行时间
     * @param charset stdout 和 stderr 解码字符集
     * @return 本地进程执行结果
     * @throws IllegalStateException 进程无法启动、读取输出失败或线程被中断时抛出
     */
    LocalOcrProcessResult run(List<String> command, Duration timeout, Charset charset);
}
