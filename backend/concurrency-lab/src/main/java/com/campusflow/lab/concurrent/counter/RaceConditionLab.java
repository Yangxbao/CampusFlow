package com.campusflow.lab.concurrent.counter;

import com.campusflow.lab.concurrent.common.LabKit;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 竞态条件实验：两线程同时 count++ 丢失更新的复现，以及
 * synchronized / AtomicLong / LongAdder 三种修复方案的正确性与耗时对比。
 *
 * 运行：
 *   mvn -pl backend/concurrency-lab compile
 *   java -cp backend/concurrency-lab/target/classes com.campusflow.lab.concurrent.counter.RaceConditionLab
 */
public class RaceConditionLab {

    private static int unsafeCount = 0;      // 无同步保护
    private static int syncedCount = 0;      // synchronized 保护
    private static final Object LOCK = new Object();

    public static void main(String[] args) throws InterruptedException {
        demoLostUpdate();
        demoFixComparison();

        System.out.println();
        System.out.println("========== 观察结论 ==========");
        System.out.println("1) count++ 在字节码层面是 getfield/iadd/putfield 三步，两线程交错执行必然丢失更新；");
        System.out.println("   即使在 x86 上底层看似单条指令的表象也改变不了\"读-改-写\"非原子的事实。");
        System.out.println("2) 三种修复都保证结果正确：synchronized 简单可靠但有锁开销与阻塞；");
        System.out.println("   AtomicLong 无锁 CAS，低-中竞争下性能好，但高竞争时大量自旋重试；");
        System.out.println("   LongAdder 分段累加（Cell[]），写时分散、读时 sum 汇总，高竞争写多读少场景最优。");
        System.out.println("3) 选型：简单低频用 synchronized；计数统计读少写多用 LongAdder；");
        System.out.println("   需要\"读改写后基于结果做决定\"（如 ID 生成器要拿到返回值）用 AtomicLong。");
    }

    /** 1. 复现丢失更新：2 线程 x 100000 次 count++，跑 3 轮 */
    static void demoLostUpdate() throws InterruptedException {
        LabKit.section("1. 竞态复现：2 线程同时 count++ 各 100000 次（无同步）");
        for (int round = 1; round <= 3; round++) {
            unsafeCount = 0;
            runTwoThreads(() -> {
                for (int i = 0; i < 100_000; i++) {
                    unsafeCount++;
                }
            });
            int lost = 200_000 - unsafeCount;
            System.out.printf("第 %d 轮: 期望 200000, 实际 %d, 丢失 %d 次%s%n",
                    round, unsafeCount, lost, lost > 0 ? " <-- 丢失更新！" : "（本次未丢失，竞态并非每次都发生）");
        }
    }

    /** 2. 三种修复方案对比：4 线程 x 500000 次 */
    static void demoFixComparison() throws InterruptedException {
        LabKit.section("2. 修复对比：4 线程 x 500,000 次（结果应恒为 2,000,000）");
        int threads = 4;
        int per = 500_000;
        int expected = threads * per;

        // (a) synchronized
        long start = System.nanoTime();
        syncedCount = 0;
        runThreads(threads, () -> {
            for (int i = 0; i < per; i++) {
                synchronized (LOCK) {
                    syncedCount++;
                }
            }
        });
        System.out.printf("synchronized: 结果=%d(%s), 耗时=%d ms%n",
                syncedCount, syncedCount == expected ? "正确" : "错误!", LabKit.elapsedMillis(start));

        // (b) AtomicLong
        AtomicLong atomic = new AtomicLong(0);
        start = System.nanoTime();
        runThreads(threads, () -> {
            for (int i = 0; i < per; i++) {
                atomic.incrementAndGet();
            }
        });
        System.out.printf("AtomicLong  : 结果=%d(%s), 耗时=%d ms%n",
                atomic.get(), atomic.get() == expected ? "正确" : "错误!", LabKit.elapsedMillis(start));

        // (c) LongAdder
        LongAdder adder = new LongAdder();
        start = System.nanoTime();
        runThreads(threads, () -> {
            for (int i = 0; i < per; i++) {
                adder.increment();
            }
        });
        System.out.printf("LongAdder   : 结果=%d(%s), 耗时=%d ms%n",
                adder.sum(), adder.sum() == expected ? "正确" : "错误!", LabKit.elapsedMillis(start));
    }

    /** 供测试使用：无同步竞态，返回第一轮丢失的更新数（连续 rounds 轮内出现即返回） */
    static int unsafeIncrementRace(int rounds) throws InterruptedException {
        for (int round = 0; round < rounds; round++) {
            unsafeCount = 0;
            runTwoThreads(() -> {
                for (int i = 0; i < 100_000; i++) {
                    unsafeCount++;
                }
            });
            if (unsafeCount < 200_000) {
                return 200_000 - unsafeCount;
            }
        }
        return 0;
    }

    /** 供测试使用：三种修复的正确性 */
    static int synchronizedFix(int threads, int per) throws InterruptedException {
        syncedCount = 0;
        runThreads(threads, () -> {
            for (int i = 0; i < per; i++) {
                synchronized (LOCK) {
                    syncedCount++;
                }
            }
        });
        return syncedCount;
    }

    static long atomicLongFix(int threads, int per) throws InterruptedException {
        AtomicLong v = new AtomicLong(0);
        runThreads(threads, () -> {
            for (int i = 0; i < per; i++) {
                v.incrementAndGet();
            }
        });
        return v.get();
    }

    static long longAdderFix(int threads, int per) throws InterruptedException {
        LongAdder v = new LongAdder();
        runThreads(threads, () -> {
            for (int i = 0; i < per; i++) {
                v.increment();
            }
        });
        return v.sum();
    }

    /** 两个线程（带启动闸门，确保真正并发） */
    private static void runTwoThreads(Runnable task) throws InterruptedException {
        runThreads(2, task);
    }

    /** N 个线程带启动闸门并发执行同一任务 */
    private static void runThreads(int n, Runnable task) throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                task.run();
                done.countDown();
            }, "racer-" + i);
            t.setDaemon(true);
            t.start();
        }
        gate.countDown();
        if (!done.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("工作线程超时未结束");
        }
    }
}
