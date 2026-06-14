package com.jtxw.familyagent.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author: jtxw
 * @Date: 2026/06/15 05:37:36
 * @Description: 归一化 LLM 通用后台任务执行器配置，提供单线程执行能力以避免规则维护任务并发写库
 */
@Configuration
public class NormalizationLlmTaskExecutorConfig {
    /**
     * 核心线程数，归一化 LLM 后台任务串行执行，避免并发写库。
     */
    private static final int CORE_POOL_SIZE = 1;
    /**
     * 最大线程数，与核心线程数一致，保持单线程语义。
     */
    private static final int MAXIMUM_POOL_SIZE = 1;
    /**
     * 空闲线程存活时间，单位秒；0 表示核心线程不会因空闲而回收。
     */
    private static final long KEEP_ALIVE_TIME_SECONDS = 0L;
    /**
     * 归一化 LLM 后台任务线程名前缀，便于日志排查。
     */
    private static final String THREAD_NAME_PREFIX = "normalization-llm-task-";

    /**
     * 创建归一化 LLM 通用任务执行器。
     *
     * @return 单线程归一化 LLM 任务执行器，Spring 容器销毁时自动 shutdown
     */
    @Bean(name = "normalizationLlmTaskExecutor", destroyMethod = "shutdown")
    public ExecutorService normalizationLlmTaskExecutor() {
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAXIMUM_POOL_SIZE,
                KEEP_ALIVE_TIME_SECONDS,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                normalizationLlmTaskThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 创建归一化 LLM 任务线程工厂，为线程设置可读名称。
     *
     * @return 带有业务语义线程名的线程工厂
     */
    private ThreadFactory normalizationLlmTaskThreadFactory() {
        AtomicInteger threadIndex = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(THREAD_NAME_PREFIX + threadIndex.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }
}
