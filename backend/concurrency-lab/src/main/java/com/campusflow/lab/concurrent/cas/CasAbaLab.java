package com.campusflow.lab.concurrent.cas;

import com.campusflow.lab.concurrent.common.LabKit;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * CAS 实验：AtomicInteger CAS、AtomicReference CAS 自旋、ABA 问题复现与
 * AtomicStampedReference（版本戳）解决、LongAdder 与 AtomicLong 性能对比。
 *
 * 运行：
 *   mvn -pl backend/concurrency-lab compile
 *   java -cp backend/concurrency-lab/target/classes com.campusflow.lab.concurrent.cas.CasAbaLab
 */
public class CasAbaLab {

    public static void main(String[] args) throws InterruptedException {
        demoAtomicInteger();
        demoAbaProblem();
        demoStampedSolution();
        long spinResult = casSpinIncrement(2, 1000);
        System.out.println("CAS 自旋最终值 = " + spinResult + "（2 线程 x 1000 次，期望 2000）");
        demoLongAdderVsAtomicLong();

        System.out.println();
        System.out.println("========== 观察结论 ==========");
        System.out.println("1) CAS = Compare-And-Swap，底层是 CPU 的 cmpxchg 原子指令（Unsafe/VarHandle），失败则重试（自旋）。");
        System.out.println("2) CAS 只比较\"值是否等于期望值\"，无法感知 A→B→A 的中间变化 —— ABA 问题；");
        System.out.println("   需要感知变化时用 AtomicStampedReference（版本戳）或 AtomicMarkableReference。");
        System.out.println("3) 高竞争下 LongAdder（分段 Cell 累加，sum 时汇总）明显快于 AtomicLong（所有线程挤在一个变量上自旋重试）。");
    }

    /** 1. AtomicInteger：incrementAndGet 与 compareAndSet */
    static void demoAtomicInteger() {
        LabKit.section("1. AtomicInteger：CAS 的基本用法");
        AtomicInteger value = new AtomicInteger(100);
        System.out.println("初始值 = " + value.get());
        System.out.println("incrementAndGet() = " + value.incrementAndGet() + "（CAS 自旋实现，线程安全）");
        boolean ok = value.compareAndSet(101, 200); // 期望 101，改成 200
        System.out.println("compareAndSet(101, 200) = " + ok + "，当前值 = " + value.get());
        boolean bad = value.compareAndSet(101, 999); // 期望值不对，失败
        System.out.println("compareAndSet(101, 999) = " + bad + "（期望值不匹配，CAS 失败），当前值 = " + value.get());
    }

    /** 2. ABA 问题复现：t1 离开期间 t2 完成 A→B→A，t1 的 CAS 仍成功 */
    static void demoAbaProblem() throws InterruptedException {
        LabKit.section("2. ABA 复现：值经历 A→B→A，普通 CAS 照样成功");
        AtomicReference<String> ref = new AtomicReference<>("A");
        AtomicBoolean t1CasResult = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            String seen = ref.get();
            LabKit.log("t1 读到当前值 %s，随后去忙别的事 300ms...", seen);
            LabKit.sleep(300);
            boolean ok = ref.compareAndSet("A", "C");
            t1CasResult.set(ok);
            LabKit.log("t1 回来执行 CAS(A→C) = %s，当前值 = %s —— 值其实经历过 A→B→A，但 CAS 仍然成功！", ok, ref.get());
        }, "aba-t1");
        Thread t2 = new Thread(() -> {
            LabKit.sleep(100);
            ref.compareAndSet("A", "B");
            LabKit.log("t2 完成 A→B");
            LabKit.sleep(50);
            ref.compareAndSet("B", "A");
            LabKit.log("t2 完成 B→A（对 t1 来说\"好像什么都没发生\"）");
        }, "aba-t2");
        t1.start();
        t2.start();
        LabKit.join(t1, 5000);
        LabKit.join(t2, 5000);
        if (t1CasResult.get()) {
            System.out.println("现象：普通 CAS 无法感知中间变化 —— 这就是 ABA 问题（对大多数场景无害，但涉及资金/链表指针时危险）。");
        }
    }

    /** 3. AtomicStampedReference：用版本戳解决 ABA */
    static void demoStampedSolution() throws InterruptedException {
        LabKit.section("3. AtomicStampedReference：引用+版本戳，正确感知 ABA");
        AtomicStampedReference<String> sref = new AtomicStampedReference<>("A", 1);

        Thread t1 = new Thread(() -> {
            int[] stampHolder = new int[1];
            String seen = sref.get(stampHolder);
            LabKit.log("t1 读到 %s（版本=%d），去忙别的事 300ms...", seen, stampHolder[0]);
            int expectedStamp = stampHolder[0];
            LabKit.sleep(300);
            // t1 按自己离开前的版本号做 CAS
            boolean ok = sref.compareAndSet("A", "C", expectedStamp, expectedStamp + 1);
            LabKit.log("t1 用旧版本 %d 执行 CAS(A→C) = %s（版本已变，正确失败！）当前 = %s（版本=%d）",
                    expectedStamp, ok, sref.getReference(), sref.getStamp());
        }, "stamped-t1");
        Thread t2 = new Thread(() -> {
            LabKit.sleep(100);
            sref.compareAndSet("A", "B", 1, 2);
            LabKit.log("t2 完成 A/1 → B/2");
            LabKit.sleep(50);
            sref.compareAndSet("B", "A", 2, 3);
            LabKit.log("t2 完成 B/2 → A/3（值回到 A，但版本戳已变为 3）");
        }, "stamped-t2");
        t1.start();
        t2.start();
        LabKit.join(t1, 5000);
        LabKit.join(t2, 5000);
        boolean ok2 = sref.compareAndSet("A", "D", 3, 4);
        System.out.println("持最新版本 3 再 CAS(A→D) = " + ok2 + " —— 只有拿着最新版本戳才能修改成功。");
    }

    /**
     * 4. AtomicReference CAS 自旋：两个线程各 increments 次，用 CAS 循环累加。
     * 返回最终值。
     */
    static long casSpinIncrement(int threads, int increments) throws InterruptedException {
        LabKit.section("4. AtomicReference CAS 自旋：手写\"原子累加器\"");
        AtomicReference<Long> holder = new AtomicReference<>(0L);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < increments; i++) {
                    while (true) { // 经典 CAS 自旋：失败就重读重试
                        Long cur = holder.get();
                        if (holder.compareAndSet(cur, cur + 1)) {
                            break;
                        }
                    }
                }
                done.countDown();
            }, "spin-" + t);
            worker.setDaemon(true);
            worker.start();
        }
        gate.countDown();
        done.await(30, TimeUnit.SECONDS);
        System.out.println(threads + " 线程 x " + increments + " 次 CAS 自旋累加，最终 = " + holder.get());
        return holder.get();
    }

    /** 5. LongAdder 与 AtomicLong 对比：4 线程 x 100 万次累加，各跑 3 轮取最好成绩 */
    static void demoLongAdderVsAtomicLong() throws InterruptedException {
        LabKit.section("5. LongAdder vs AtomicLong：4 线程 x 1,000,000 次累加（3 轮取最好）");
        int threads = 4;
        int per = 1_000_000;
        long bestAtomic = Long.MAX_VALUE;
        long bestAdder = Long.MAX_VALUE;
        for (int round = 1; round <= 3; round++) {
            long t = benchAtomicLong(threads, per);
            bestAtomic = Math.min(bestAtomic, t);
            t = benchLongAdder(threads, per);
            bestAdder = Math.min(bestAdder, t);
        }
        System.out.printf("最好成绩对比: AtomicLong=%d ms, LongAdder=%d ms —— 高竞争下 LongAdder 通过\"分段累加+sum 汇总\"避免单点自旋竞争%n",
                bestAtomic, bestAdder);
    }

    static long benchAtomicLong(int threads, int per) throws InterruptedException {
        AtomicLong v = new AtomicLong();
        long start = System.nanoTime();
        runContention(threads, () -> {
            for (int i = 0; i < per; i++) {
                v.incrementAndGet();
            }
        });
        long cost = LabKit.elapsedMillis(start);
        System.out.printf("AtomicLong: 结果=%d, 耗时=%d ms%n", v.get(), cost);
        return cost;
    }

    static long benchLongAdder(int threads, int per) throws InterruptedException {
        LongAdder v = new LongAdder();
        long start = System.nanoTime();
        runContention(threads, () -> {
            for (int i = 0; i < per; i++) {
                v.increment();
            }
        });
        long cost = LabKit.elapsedMillis(start);
        System.out.printf("LongAdder : 结果=%d, 耗时=%d ms%n", v.sum(), cost);
        return cost;
    }

    private static void runContention(int threads, Runnable task) throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                task.run();
                done.countDown();
            }, "bench-" + i);
            t.setDaemon(true);
            t.start();
        }
        gate.countDown();
        done.await(60, TimeUnit.SECONDS);
    }

    /** 供测试使用：AtomicLong 累加正确性 */
    static long atomicLongResult(int threads, int per) throws InterruptedException {
        AtomicLong v = new AtomicLong();
        runContention(threads, () -> {
            for (int i = 0; i < per; i++) {
                v.incrementAndGet();
            }
        });
        return v.get();
    }

    /** 供测试使用：LongAdder 累加正确性 */
    static long longAdderResult(int threads, int per) throws InterruptedException {
        LongAdder v = new LongAdder();
        runContention(threads, () -> {
            for (int i = 0; i < per; i++) {
                v.increment();
            }
        });
        return v.sum();
    }
}
