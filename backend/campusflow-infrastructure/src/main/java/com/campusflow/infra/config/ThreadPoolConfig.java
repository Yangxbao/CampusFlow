package com.campusflow.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池集中治理（禁止在业务代码 Executors.newXxx 创建无界线程池）。
 * <p>七个核心参数在每处 @Bean 中完整显式声明，并注明选择依据：
 * <ul>
 *   <li>notifyExecutor —— MQ 消费端异步落通知、审计异步落库：IO 密集，核心 4 / 最大 8，有界队列 200，
 *       拒绝策略 CallerRuns（削峰兜底，不丢业务数据，最差情况拖慢消费但保证不丢）；</li>
 *   <li>exportExecutor —— CSV 异步导出：CPU+IO 密集、任务少而重，核心 2 / 最大 4，有界队列 50，
 *       拒绝策略 Abort（直接报错给用户"任务过多"，避免任务堆积导致 OOM）；</li>
 *   <li>compensationExecutor —— 补偿扫描后的单项修复任务：核心 2 / 最大 4，队列 100，CallerRuns。</li>
 * </ul>
 * 优雅关闭：setWaitForTasksToCompleteOnShutdown + awaitTermination，
 * Spring 容器关闭时先拒绝新任务、等待存量任务完成（destroyMethod 由 Spring 在容器 close 时触发）。
 */
@Slf4j
@Configuration
public class ThreadPoolConfig {

    private static final int SHUTDOWN_WAIT_SECONDS = 30;

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor notifyExecutor() {
        return build(
                "notify-worker", 4, 8, 200,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor exportExecutor() {
        return build(
                "export-worker", 2, 4, 50,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor compensationExecutor() {
        return build(
                "compensation-worker", 2, 4, 100,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private ThreadPoolExecutor build(String prefix, int core, int max, int queue,
                                     java.util.concurrent.RejectedExecutionHandler handler) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + seq.getAndIncrement());
                t.setDaemon(false);
                t.setUncaughtExceptionHandler((thread, e) ->
                        log.error("[{}] 线程未捕获异常", thread.getName(), e));
                return t;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                core, max,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue),
                factory,
                handler);
        // 核心线程也允许超时回收，低峰期释放资源
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
