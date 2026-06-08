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
 * @Date: 2026/06/07 15:17:32
 * @Description: 商品归一化异步分析执行器配置，提供单线程后台执行能力以避免 LLM 分析任务并发写库。
 *
 * <p>该线程池只服务于商品归一化异步分析任务，使用单线程 + SynchronousQueue + 显式拒绝策略，
 * 不在线程池层面排队任务，确保同一时间只有一个归一化任务在后台执行。</p>
 */
@Configuration
public class NormalizationAnalysisTaskExecutorConfig {
    /**
     * 核心线程数，归一化任务为单线程串行执行，避免并发写库
     */
    private static final int CORE_POOL_SIZE = 1;
    /**
     * 最大线程数，与核心线程数一致，保持单线程语义
     */
    private static final int MAXIMUM_POOL_SIZE = 1;
    /**
     * 空闲线程存活时间，单位为秒；0 表示核心线程不会因空闲而回收
     */
    private static final long KEEP_ALIVE_TIME_SECONDS = 0L;
    /**
     * 归一化分析线程名前缀，便于日志和排障
     */
    private static final String THREAD_NAME_PREFIX = "normalization-analysis-";

    /**
     * 创建商品归一化分析任务执行器。
     *
     * <p>使用显式 ThreadPoolExecutor 替代 Executors 快捷方法，满足 Alibaba 编码规范。
     * 业务上同一时间只允许一个归一化分析任务执行，因此 corePoolSize 和 maximumPoolSize 均为 1；
     * 使用 SynchronousQueue 不在线程池层面排队任务，配合 AbortPolicy 显式拒绝无法立即交给工作线程执行的任务提交。</p>
     *
     * @return 单线程归一化分析执行器，Spring 容器销毁时自动 shutdown
     */
    @Bean(name = "normalizationAnalysisExecutor", destroyMethod = "shutdown")
    public ExecutorService normalizationAnalysisExecutor() {
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAXIMUM_POOL_SIZE,
                KEEP_ALIVE_TIME_SECONDS,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                normalizationAnalysisThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 创建归一化分析线程工厂，为线程设置可读名称。
     *
     * @return 带有业务语义线程名的线程工厂
     */
    private ThreadFactory normalizationAnalysisThreadFactory() {
        AtomicInteger threadIndex = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(THREAD_NAME_PREFIX + threadIndex.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }
}
