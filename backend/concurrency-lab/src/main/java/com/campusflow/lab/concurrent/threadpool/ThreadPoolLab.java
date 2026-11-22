package com.campusflow.lab.concurrent.threadpool;

import com.campusflow.lab.concurrent.common.LabKit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池实验：ThreadPoolExecutor 七参数、核心/最大线程数与有界队列的任务流转、
 * 四种拒绝策略、自定义 ThreadFactory、优雅关闭（shutdown/awaitTermination/shutdownNow）、
 * 运行期动态调参（setCorePoolSize / setMaximumPoolSize）。
 *
 * 运行：
 *   mvn -pl backend/concurrency-lab compile
 *   java -cp backend/concurrency-lab/target/classes com.campusflow.lab.concurrent.threadpool.ThreadPoolLab
 */
public class ThreadPoolLab {

    public static void main(String[] args) throws InterruptedException {
        demoSevenParamsAndFlow();
        demoRejectionPolicies();
        demoGracefulShutdown();
        demoDynamicTuning();

        System.out.println();
        System.out.println("========== 观察结论 ==========");
        System.out.println("1) 任务流转顺序：线程数 < core 直接建核心线程；core 满先入队；队列满才建非核心线程（到 max）；");
        System.out.println("   线程数达 max 且队列满才触发拒绝策略。注意是\"先入队、后扩容\"，与很多人以为的相反。");
        System.out.println("2) 四种拒绝策略：AbortPolicy 抛 RejectedExecutionException；CallerRunsPolicy 让提交线程自己执行（天然限流）；");
        System.out.println("   DiscardPolicy 静默丢弃新任务；DiscardOldestPolicy 丢弃队首最老任务再重试入队。");
        System.out.println("3) 优雅关闭：shutdown() 拒绝新任务但执行完已提交任务，配合 awaitTermination 等待退出；");
        System.out.println("   shutdownNow() 中断正在执行的任务并返回队列中未执行的任务列表。");
        System.out.println("4) setCorePoolSize 扩容会立即创建新工作线程去消化队列积压任务，运行期动态调参可用于应对流量波动。");
    }

    /** 1. 七参数构造 + 任务流转：core=2, max=4, 有界队列=2，提交 8 个任务 */
    static void demoSevenParamsAndFlow() throws InterruptedException {
        LabKit.section("1. 七参数构造与任务流转（core=2, max=4, ArrayBlockingQueue(2), AbortPolicy）");
        AtomicInteger threadSeq = new AtomicInteger(1);
        AtomicInteger executed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        // 自定义 ThreadFactory：统一命名 + 创建时打印（可观察线程何时被创建）
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "demo-worker-" + threadSeq.getAndIncrement());
            t.setDaemon(true);
            System.out.println("[threadFactory] 创建线程: " + t.getName());
            return t;
        };

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,                                   // 1.corePoolSize 核心线程数
                4,                                   // 2.maximumPoolSize 最大线程数
                10, TimeUnit.SECONDS,                // 3.4.keepAliveTime+unit 非核心线程空闲存活时间
                new ArrayBlockingQueue<>(2),         // 5.workQueue 有界阻塞队列
                factory,                             // 6.threadFactory 线程工厂
                new ThreadPoolExecutor.AbortPolicy() // 7.handler 拒绝策略
        );
        System.out.println("提交 8 个任务（每个 400ms），观察：核心2 -> 队列2 -> 扩容2 -> 拒绝2");

        for (int i = 0; i < 8; i++) {
            final int id = i;
            try {
                pool.execute(() -> {
                    executed.incrementAndGet();
                    LabKit.log("执行任务 %d（400ms）", id);
                    LabKit.sleep(400);
                });
                LabKit.log("提交任务 %d 成功 -> poolSize=%d, 队列=%d", id, pool.getPoolSize(), pool.getQueue().size());
            } catch (RejectedExecutionException e) {
                rejected.incrementAndGet();
                LabKit.log("提交任务 %d 被拒绝（AbortPolicy 抛出 %s）", id, e.getClass().getSimpleName());
            }
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.printf("结果：执行 %d 个 / 拒绝 %d 个 / 峰值线程数 = %d（largestPoolSize）%n",
                executed.get(), rejected.get(), pool.getLargestPoolSize());
    }

    /** 2. 四种拒绝策略对比：core=max=1, queue=1，连续提交 3 个 200ms 任务 */
    static void demoRejectionPolicies() throws InterruptedException {
        LabKit.section("2. 四种拒绝策略对比（core=max=1, queue=1, 提交 3 个任务）");
        String[] names = {
                "AbortPolicy（抛异常）",
                "CallerRunsPolicy（调用者线程执行）",
                "DiscardPolicy（静默丢弃新任务）",
                "DiscardOldestPolicy（丢弃队首最老任务）"
        };
        RejectedExecutionHandler[] policies = {
                new ThreadPoolExecutor.AbortPolicy(),
                new ThreadPoolExecutor.CallerRunsPolicy(),
                new ThreadPoolExecutor.DiscardPolicy(),
                new ThreadPoolExecutor.DiscardOldestPolicy()
        };
        for (int p = 0; p < policies.length; p++) {
            System.out.println();
            System.out.println("---- " + names[p] + " ----");
            Set<Integer> executedIds = Collections.synchronizedSet(new HashSet<>());
            List<String> executedOn = Collections.synchronizedList(new ArrayList<>());
            ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1), policies[p]);
            for (int i = 0; i < 3; i++) {
                final int id = i;
                try {
                    pool.execute(() -> {
                        executedIds.add(id);
                        executedOn.add(Thread.currentThread().getName());
                        LabKit.log("任务 %d 执行于 %s", id, Thread.currentThread().getName());
                        LabKit.sleep(200);
                    });
                } catch (RejectedExecutionException e) {
                    LabKit.log("任务 %d 提交被拒绝: %s", id, e.getClass().getSimpleName());
                }
            }
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            System.out.println("实际执行的任务 id: " + executedIds + "，执行线程: " + executedOn);
        }
    }

    /** 3. 优雅关闭：shutdown/awaitTermination 与 shutdownNow */
    static void demoGracefulShutdown() throws InterruptedException {
        LabKit.section("3. 优雅关闭：shutdown + awaitTermination；对比 shutdownNow");

        // (a) shutdown：不再接收新任务，已提交任务（含队列中）执行完才退出
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        for (int i = 0; i < 3; i++) {
            pool.execute(() -> {
                LabKit.log("常规任务执行中（150ms）");
                LabKit.sleep(150);
            });
        }
        pool.shutdown();
        System.out.println("shutdown() 后 isShutdown=" + pool.isShutdown());
        try {
            pool.execute(() -> System.out.println("这句话永远不该被打印"));
        } catch (RejectedExecutionException e) {
            System.out.println("shutdown() 后提交新任务 -> RejectedExecutionException（拒绝新任务）");
        }
        boolean terminated = pool.awaitTermination(3, TimeUnit.SECONDS);
        System.out.println("awaitTermination(3s)=" + terminated + "，isTerminated=" + pool.isTerminated());

        // (b) shutdownNow：中断正在执行的任务，返回队列中未执行的任务
        ThreadPoolExecutor pool2 = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        pool2.execute(() -> {
            LabKit.log("长任务开始（sleep 1s，shutdownNow 会中断我）");
            try {
                Thread.sleep(1000);
                LabKit.log("长任务完整跑完（未被中断）");
            } catch (InterruptedException e) {
                LabKit.log("长任务在 sleep 中收到中断 -> 立即收尾退出（响应中断）");
            }
        });
        pool2.execute(() -> LabKit.log("排队任务（不会被执行，应出现在 shutdownNow 返回列表中）"));
        LabKit.sleep(200);
        List<Runnable> notExecuted = pool2.shutdownNow();
        System.out.println("shutdownNow() 返回未执行任务数 = " + notExecuted.size());
        pool2.awaitTermination(3, TimeUnit.SECONDS);
        System.out.println("pool2 已终止 = " + pool2.isTerminated());
    }

    /** 4. 运行期动态调参：setCorePoolSize 扩容消化队列积压 */
    static void demoDynamicTuning() throws InterruptedException {
        LabKit.section("4. 动态调参：core=1 时 6 个任务排队，setCorePoolSize(3) 扩容加速");
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 5, 10, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10), LabKit.namedFactory("dyn-worker", true));
        System.out.println("初始参数: core=" + pool.getCorePoolSize() + ", max=" + pool.getMaximumPoolSize());
        long start = System.nanoTime();
        for (int i = 0; i < 6; i++) {
            final int id = i;
            pool.execute(() -> {
                LabKit.log("任务 %d 开始", id);
                LabKit.sleep(200);
                LabKit.log("任务 %d 结束", id);
            });
        }
        LabKit.sleep(100); // 任务 0 已在唯一核心线程上运行，其余 5 个在队列排队
        System.out.println("调整前: poolSize=" + pool.getPoolSize()
                + ", active=" + pool.getActiveCount() + ", 队列积压=" + pool.getQueue().size());
        pool.setCorePoolSize(3); // 扩容：立即创建新工作线程消化队列积压
        System.out.println("setCorePoolSize(3) 后: poolSize=" + pool.getPoolSize()
                + ", active=" + pool.getActiveCount());
        pool.setCorePoolSize(2);
        pool.setMaximumPoolSize(2);
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        System.out.printf("全部任务完成耗时 %d ms（若始终单线程串行约需 1200ms，扩容后应明显缩短）%n",
                LabKit.elapsedMillis(start));
    }
}
