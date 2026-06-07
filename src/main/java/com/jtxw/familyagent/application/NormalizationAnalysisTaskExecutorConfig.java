package com.jtxw.familyagent.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Author: jtxw
 * @Date: 2026/06/07 15:17:32
 * @Description: 商品归一化异步分析执行器配置，提供单线程后台执行能力以避免 LLM 分析任务并发写库。
 */
@Configuration
public class NormalizationAnalysisTaskExecutorConfig {
    /**
     * 创建商品归一化分析任务执行器。
     *
     * <p>业务上同一时间只允许一个归一化分析任务执行，因此这里固定使用单线程；
     * 线程设置为 daemon，避免应用退出时被后台任务线程阻塞。</p>
     *
     * @return 单线程归一化分析执行器，Spring 容器销毁时自动 shutdown
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService normalizationAnalysisExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "normalization-analysis-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
