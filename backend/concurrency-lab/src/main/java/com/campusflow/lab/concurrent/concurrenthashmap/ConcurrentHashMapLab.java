package com.campusflow.lab.concurrent.concurrenthashmap;

import com.campusflow.lab.concurrent.common.LabKit;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ConcurrentHashMap 实验：并发 put 安全性、computeIfAbsent 的原子性、
 * 与 HashMap 并发使用丢数据（JDK8 现象）的对比；JDK7 扩展环（CPU 100%）仅文字说明，不实际复现。
 *
 * 运行：
 *   mvn -pl backend/concurrency-lab compile
 *   java -cp backend/concurrency-lab/target/classes com.campusflow.lab.concurrent.concurrenthashmap.ConcurrentHashMapLab
 */
public class ConcurrentHashMapLab {

    static final int THREADS = 8;
    static final int PER_THREAD = 2000;
    static final int TOTAL = THREADS * PER_THREAD;

    public static void main(String[] args) throws Exception {
        concurrentPut(new ConcurrentHashMap<>());
        computeIfAbsentRace();
        demoHashMapConcurrentPut();

        System.out.println();
        System.out.println("========== 观察结论 ==========");
        System.out.println("1) ConcurrentHashMap 并发 put 所有数据都存在：size() = " + TOTAL);
        System.out.println("   （JDK8 结构：CAS 无冲突快速插入 + synchronized 锁桶头，锁粒度比 JDK7 分段锁更细）；");
        System.out.println("2) computeIfAbsent 对同一个 key 原子：mappingFunction 只被一个线程执行一次，其余线程等待其结果；");
        System.out.println("   但注意 mappingFunction 内不要再次修改同一个 map（可能死锁或 IllegalStateException）。");
        System.out.println("3) HashMap 并发 put 丢数据：本实验 8 线程 x 2000 条，每轮都低于 " + TOTAL + " 条。");
        System.out.println("   原因：多线程同时 putVal 互相覆盖 / 并发 resize 搬移丢节点。");
        System.out.println("4) JDK7 HashMap 扩容用头插法，并发 resize 会把链表转移成环，后续 get 命中该桶时死循环 CPU 100%；");
        System.out.println("   JDK8 改为尾插法不再成环，但仍会丢数据/数据覆盖 —— 无论如何 HashMap 禁止并发写入。");
    }

    /** 1. ConcurrentHashMap 并发 put 安全性。返回最终 size */
    static int concurrentPut(Map<Integer, Integer> map) throws InterruptedException {
        LabKit.section("1. " + map.getClass().getSimpleName() + " 并发 put：" + THREADS + " 线程 x " + PER_THREAD + " 条不重复 key");
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int t = 0; t < THREADS; t++) {
            final int base = t * PER_THREAD;
            Thread worker = new Thread(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < PER_THREAD; i++) {
                    map.put(base + i, i);
                }
                done.countDown();
            }, "putter-" + t);
            worker.setDaemon(true);
            worker.start();
        }
        gate.countDown();
        done.await(60, TimeUnit.SECONDS);
        System.out.println("期望条数 = " + TOTAL + "，实际 size = " + map.size());
        return map.size();
    }

    /** 2. computeIfAbsent 原子性：8 线程并发对同一 key 调用，mappingFunction 只执行一次。返回调用次数 */
    static int computeIfAbsentRace() throws InterruptedException {
        LabKit.section("2. computeIfAbsent 原子性：" + THREADS + " 线程并发竞争同一个缓存 key");
        ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
        AtomicInteger invocations = new AtomicInteger(0);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int t = 0; t < THREADS; t++) {
            Thread worker = new Thread(() -> {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String v = cache.computeIfAbsent("hot-key", k -> {
                    invocations.incrementAndGet();
                    LabKit.log("mappingFunction 开始执行（昂贵的构建过程 100ms）");
                    LabKit.sleep(100);
                    return "expensive-result";
                });
                LabKit.log("拿到缓存值 %s", v);
                done.countDown();
            }, "cacher-" + t);
            worker.setDaemon(true);
            worker.start();
        }
        gate.countDown();
        done.await(60, TimeUnit.SECONDS);
        System.out.println("mappingFunction 调用次数 = " + invocations.get()
                + "（期望 1 —— computeIfAbsent 对同一 key 的加载是原子的，天然防缓存击穿）");
        return invocations.get();
    }

    /** 3. HashMap 并发 put 丢数据对比（JDK8 现象）。返回是否观察到丢数据或异常 */
    static boolean demoHashMapConcurrentPut() {
        LabKit.section("3. 对比：HashMap 并发 put（" + THREADS + " 线程 x " + PER_THREAD + " 条，共 3 轮）");
        boolean lostSeen = false;
        for (int round = 1; round <= 3; round++) {
            HashMap<Integer, Integer> map = new HashMap<>();
            int size;
            String abnormal = null;
            try {
                CountDownLatch gate = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(THREADS);
                for (int t = 0; t < THREADS; t++) {
                    final int base = t * PER_THREAD;
                    Thread worker = new Thread(() -> {
                        try {
                            gate.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        for (int i = 0; i < PER_THREAD; i++) {
                            map.put(base + i, i);
                        }
                        done.countDown();
                    }, "hm-putter-" + t);
                    worker.setDaemon(true);
                    worker.start();
                }
                gate.countDown();
                done.await(60, TimeUnit.SECONDS);
                size = map.size();
            } catch (Throwable e) { // 并发损坏还可能抛出其它运行时异常（如树化竞态）
                abnormal = e.getClass().getSimpleName() + ": " + e.getMessage();
                size = -1;
            }
            if (abnormal != null) {
                System.out.printf("第 %d 轮: 并发损坏抛出异常 %s%n", round, abnormal);
                lostSeen = true;
            } else {
                System.out.printf("第 %d 轮: 期望 %d 条，实际 %d 条，丢失 %d 条%s%n",
                        round, TOTAL, size, TOTAL - size, size < TOTAL ? " <-- 丢数据！" : "");
                lostSeen |= size < TOTAL;
            }
        }
        System.out.println("说明：JDK7 的并发扩容链表成环导致 CPU 100% 的死循环，JDK8 改尾插后不再成环，");
        System.out.println("      但并发 put 仍会丢数据；两者都说明 HashMap 绝不能被并发写入，需要 ConcurrentHashMap。");
        return lostSeen;
    }

    /** 供测试使用：最多 rounds 轮，返回是否观察到 HashMap 丢数据/异常 */
    static boolean hashMapLosesDataUnderConcurrency(int rounds) throws InterruptedException {
        for (int round = 0; round < rounds; round++) {
            HashMap<Integer, Integer> map = new HashMap<>();
            int size;
            try {
                size = concurrentPut(map);
            } catch (Throwable e) {
                return true;
            }
            if (size < TOTAL) {
                return true;
            }
        }
        return false;
    }
}
