package com.campusflow.lab.concurrent.threadpool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ThreadPoolLab 的 JUnit 5 测试。
 * 所有用例均有 @Timeout 保护，避免线程池未关闭导致挂死。
 */
class ThreadPoolLabTest {

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @Timeout(30)
    void coreMaxQueueRejectFlow() throws Exception {
        // core=2, max=4, queue=2：提交 8 个 200ms 任务 => 2 核心执行 + 2 入队 + 2 扩容执行 + 2 拒绝
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 4, 10, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), r -> new Thread(r, "tpe-test"), new ThreadPoolExecutor.AbortPolicy());
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            try {
                pool.execute(() -> {
                    executed.incrementAndGet();
                    sleep(200);
                });
            } catch (RejectedExecutionException e) {
                rejected.incrementAndGet();
            }
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池应优雅终止");
        assertEquals(6, executed.get(), "核心2+队列2+扩容2 = 6 个被执行");
        assertEquals(2, rejected.get(), "队列满且线程数达 max 后 2 个被拒绝");
        assertEquals(4, pool.getLargestPoolSize(), "峰值线程数应为 max=4");
    }

    @Test
    @Timeout(30)
    void callerRunsPolicyRunsOnCallerThread() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.CallerRunsPolicy());
        Set<String> threads = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            pool.execute(() -> {
                threads.add(Thread.currentThread().getName());
                sleep(200);
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(2, threads.size(), "2 个任务在工作线程、1 个在调用者(main)线程");
        assertTrue(threads.contains("main"), "被拒绝的任务应由提交线程 main 自己执行");
    }

    @Test
    @Timeout(30)
    void discardPolicyDropsTaskSilently() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardPolicy());
        AtomicInteger executed = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            pool.execute(() -> {
                executed.incrementAndGet();
                sleep(150);
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(2, executed.get(), "DiscardPolicy 静默丢弃第 3 个任务，只执行 2 个");
    }

    @Test
    @Timeout(30)
    void gracefulShutdown() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            pool.execute(() -> {
                sleep(100);
                done.incrementAndGet();
            });
        }
        pool.shutdown();
        assertFalse(pool.isTerminated(), "shutdown 后等待中的瞬间尚未 terminated");
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(pool.isTerminated());
        assertEquals(3, done.get(), "已提交的任务应全部执行完再退出");
        assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }),
                "shutdown 后提交新任务应被拒绝");
    }

    @Test
    @Timeout(30)
    void dynamicCoreResizeConsumesQueue() throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 5, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10));
        Set<String> usedThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 6; i++) {
            pool.execute(() -> {
                usedThreads.add(Thread.currentThread().getName());
                sleep(150);
            });
        }
        sleep(50); // 此时只有 1 个核心线程在跑，其余排队
        assertEquals(1, pool.getPoolSize(), "扩容前只有 1 个工作线程");
        pool.setCorePoolSize(3); // 运行期扩容
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(usedThreads.size() >= 2,
                "setCorePoolSize 扩容后应创建新线程消化队列积压，实际使用线程: " + usedThreads);
    }
}
