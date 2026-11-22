package com.campusflow.lab.concurrent.semaphore;

import com.campusflow.lab.concurrent.common.LabKit;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Semaphore 实验：模拟"活动场馆同时只允许 N 人进入"。
 * acquire/release、tryAcquire 超时快速失败、并发在场峰值统计。
 *
 * 运行：
 *   mvn -pl backend/concurrency-lab compile
 *   java -cp backend/concurrency-lab/target/classes com.campusflow.lab.concurrent.semaphore.SemaphoreLab
 */
public class SemaphoreLab {

    public static void main(String[] args) throws InterruptedException {
        int[] stats = runVenue(3, 10);
        System.out.printf("场馆统计：成功进入 %d 人，在场峰值 %d 人（许可数=3）%n", stats[0], stats[1]);
        boolean[] tryResults = runTryAcquireDemo();
        System.out.println("tryAcquire 演示：超时失败=" + !tryResults[0] + "，释放后成功=" + tryResults[1]);

        System.out.println();
        System.out.println("========== 观察结论 ==========");
        System.out.println("1) Semaphore(permits) 维护剩余许可数，acquire() 许可不足时阻塞进入 AQS 等待队列（共享模式）；");
        System.out.println("   release() 归还许可并唤醒队首等待线程 —— 实现了\"N 个门票\"式的并发限流。");
        System.out.println("2) 在场峰值始终不超过许可数 N，所有人最终都能进入（排队不丢弃）。");
        System.out.println("3) tryAcquire(timeout) 拿不到许可时限时放弃，适合\"快速失败/降级\"场景；");
        System.out.println("   acquire() 则无限等待。两者都应保证 finally 中 release，避免许可泄漏。");
        System.out.println("4) 原理：Semaphore 基于 AQS 共享模式 —— tryAcquireShared 对 state 做 CAS 减 1，");
        System.out.println("   state<=0 则入队挂起；releaseShared 把 state 加 1 并 unpark 后继节点。公平模式先检查队列。");
    }

    /**
     * 场馆限流：capacity 个许可，total 名游客依次尝试进入，每人停留 stayMillis。
     * 返回 {成功进入人数, 在场峰值}。
     */
    static int[] runVenue(int capacity, int total) throws InterruptedException {
        return runVenue(capacity, total, 400, 30);
    }

    static int[] runVenue(int capacity, int total, long stayMillis, long staggerMillis) throws InterruptedException {
        LabKit.section("1. 活动场馆限流：同时只允许 " + capacity + " 人进入，共 " + total + " 名游客");
        Semaphore venue = new Semaphore(capacity); // 默认非公平
        AtomicInteger inside = new AtomicInteger(0);
        AtomicInteger maxInside = new AtomicInteger(0);
        AtomicInteger entered = new AtomicInteger(0);

        Thread[] visitors = new Thread[total];
        for (int i = 0; i < total; i++) {
            final int person = i;
            visitors[i] = new Thread(() -> {
                try {
                    venue.acquire(); // 没有许可就阻塞排队
                    try {
                        int now = inside.incrementAndGet();
                        maxInside.accumulateAndGet(now, Math::max);
                        entered.incrementAndGet();
                        LabKit.log("游客 %d 进入场馆，在场人数=%d", person, now);
                        LabKit.sleep(stayMillis);
                        inside.decrementAndGet();
                        LabKit.log("游客 %d 离开", person);
                    } finally {
                        venue.release(); // 归还许可，唤醒排队者
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "visitor-" + i);
            visitors[i].setDaemon(true);
            visitors[i].start();
            LabKit.sleep(staggerMillis); // 错开到达时间，便于观察波次
        }
        for (Thread v : visitors) {
            LabKit.join(v, 10_000);
        }
        System.out.printf("结果：成功进入 %d 人，在场峰值 %d 人（许可数=%d，峰值始终<=许可数）%n",
                entered.get(), maxInside.get(), capacity);
        return new int[]{entered.get(), maxInside.get()};
    }

    /** tryAcquire 超时演示。返回 {超时尝试结果, 释放后的尝试结果} */
    static boolean[] runTryAcquireDemo() throws InterruptedException {
        LabKit.section("2. tryAcquire 超时：拿不到许可就快速失败（不无限排队）");
        Semaphore semaphore = new Semaphore(2);
        semaphore.acquire(2); // main 占满全部许可
        System.out.println("main 已占满 2 个许可");

        long start = System.nanoTime();
        boolean got = semaphore.tryAcquire(300, TimeUnit.MILLISECONDS);
        System.out.printf("tryAcquire(300ms) = %s，等待 %d ms（超时快速失败，可走降级逻辑）%n",
                got, LabKit.elapsedMillis(start));

        semaphore.release(); // 释放一个许可
        long start2 = System.nanoTime();
        boolean got2 = semaphore.tryAcquire(1, TimeUnit.SECONDS);
        System.out.printf("release 一个许可后 tryAcquire(1s) = %s，等待 %d ms%n", got2, LabKit.elapsedMillis(start2));
        return new boolean[]{got, got2};
    }
}
