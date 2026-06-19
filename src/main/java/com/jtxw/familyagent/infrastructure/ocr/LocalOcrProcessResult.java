package com.jtxw.familyagent.infrastructure.ocr;

/**
 * @Author: jtxw
 * @Date: 2026/06/18 18:28:00
 * @Description: 本地 OCR 进程执行结果，记录退出码、超时状态和标准输出内容
 *
 * @param exitCode 进程退出码；超时时使用强制终止后的退出码
 * @param timedOut 是否因超过配置时间而被终止
 * @param stdout 标准输出文本，不应为 null
 * @param stderr 标准错误文本，不应为 null
 */
public record LocalOcrProcessResult(
        int exitCode,
        boolean timedOut,
        String stdout,
        String stderr
) {
}
