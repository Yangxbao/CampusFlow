package com.campusflow.lab.concurrent.visibility;

import com.campusflow.lab.concurrent.common.LabKit;

import java.util.concurrent.CountDownLatch;

/**
 * volatile 实验：可见性问题（读线程自旋看不到标志位变化）、volatile 不保证原子性（count++）、
 * happens-before 的"发布"用法。
 *
 * 运行：
 *   mvn -pl backend/concurrency-lab compile
 *   java -cp backend/concurrency-lab/target/classes com.campusflow.lab.concurrent.visibility.VolatileLab
 */
public class VolatileLab {

    private static boolean normalFlag = false;            // 普通布尔标志：无任何同步保障
    private static volatile boolean volatileFlag = false; // volatile 布尔标志
    private static volatile int counter = 0;              // 用于演示 volatile 不保证原子性

    private static volatile boolean published = false;    // happens-before 发布标志
    private static int payload = 0;                       // 被 volatile "发布"的普通变量

    public static void main(String[] args) throws InterruptedException {
        runVisibilityLost();
        runVolatileVisibility(300);
        runNonAtomicIncrement();
        int payloadSeen = runHappensBeforePublish();
        System.out.println("发布演示最终读到 payload = " + payloadSeen);

        System.out.println();
        System.out.println("========== 观察结论 ==========");
        System.out.println("1) 普通变量：写线程修改后，读线程在 JIT 把字段读提升出循环后可能永远看不到变化 —— 可见性问题；");
        System.out.println("   volatile 读禁止这种优化，并要求读最新值，所以读线程立即看到变化。");
        System.out.println("2) volatile 只保证可见性与有序性，不保证原子性：count++ 是\"读-改-写\"三步操作，");
        System.out.println("   两个线程交错执行仍会丢失更新，需要 AtomicInteger 或锁。");
        System.out.println("3) happens-before：volatile 写 happens-before 后续的 volatile 读，");
        System.out.println("   配合程序顺序规则可以把\"普通变量写\"安全地\"发布\"给看到 volatile 标志的线程。");
    }

    /**
     * 1. 可见性问题复现：普通标志位。返回"设置标志后读线程又空转了多少毫秒才退出"，
     * 若数值接近窗口剩余时间（约 1200ms）说明读线程始终没看到变化（问题复现）。
     */
    static long runVisibilityLost() throws InterruptedException {
        LabKit.section("1. 可见性问题复现：普通 boolean 标志位（读线程死循环等待）");
        normalFlag = false;
        long window = 1500;
        long deadline = System.currentTimeMillis() + window;
        long[] spinCount = {0};
        Thread reader = new Thread(() -> {
            // 经典写法 while (!flag) {}；这里加了 deadline 让实验可自动结束。
            // 外层每批 10 万次自旋检查一次 deadline；内层是纯自旋，
            // JIT（OSR 编译）会把 normalFlag 的读取提升出内层循环 —— 只在进入时读一次。
            while (System.currentTimeMillis() < deadline) {
                for (int i = 0; i < 100_000; i++) {
                    if (normalFlag) {
                        spinCount[0] = -1; // 用 -1 标记"观察到了"
                        return;
                    }
                    spinCount[0]++;
                }
            }
        }, "visibility-reader");
        reader.setDaemon(true);
        reader.start();
        LabKit.sleep(300); // 让读线程先空转一段时间，JIT 完成优化
        System.out.println("[main] 把 normalFlag 置为 true ...");
        long setAt = System.currentTimeMillis();
        normalFlag = true;
        LabKit.join(reader, window);
        long readerStopAt = System.currentTimeMillis();
        if (spinCount[0] == -1) {
            System.out.printf("读线程在标志设置后 %d ms 内观察到变化并退出（本次未复现：JIT 未提升该读取）%n", readerStopAt - setAt);
        } else {
            System.out.printf("读线程自旋了 %d 次，在标志设置后又空转约 %d ms 才因超时退出 —— 可见性问题复现！%n",
                    spinCount[0], readerStopAt - setAt);
        }
        return readerStopAt - setAt;
    }

    /** 2. volatile 修复可见性：返回读线程是否在 1 秒内观察到变化 */
    static boolean runVolatileVisibility(long writerDelayMillis) throws InterruptedException {
        LabKit.section("2. volatile 修复可见性：写线程改 volatile 标志，读线程立即看到");
        volatileFlag = false;
        CountDownLatch observed = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            while (!volatileFlag) { // volatile 读：不会被 JIT 提升出循环，必须读最新值
                Thread.yield();
            }
            observed.countDown();
            LabKit.log("读线程观察到 volatileFlag=true，立即退出循环");
        }, "volatile-reader");
        reader.setDaemon(true);
        reader.start();
        LabKit.sleep(writerDelayMillis);
        System.out.println("[main] 把 volatileFlag 置为 true ...");
        long start = System.nanoTime();
        volatileFlag = true;
        boolean saw = observed.await(2, java.util.concurrent.TimeUnit.SECONDS);
        System.out.printf("读线程 %d ms 内观察到变化 -> %s%n", LabKit.elapsedMillis(start), saw);
        LabKit.join(reader, 2000);
        return saw;
    }

    /** 3. volatile 不保证原子性：两个线程各 10 万次 count++。返回丢失的更新次数（期望-实际） */
    static int runNonAtomicIncrement() throws InterruptedException {
        LabKit.section("3. volatile 不保证原子性：2 线程 x 100000 次 count++");
        counter = 0;
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Runnable task = () -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < 100_000; i++) {
                counter++; // 读-改-写三步，volatile 无法使其原子
            }
            done.countDown();
        };
        new Thread(task, "inc-1").start();
        new Thread(task, "inc-2").start();
        gate.countDown();
        done.await();
        int expected = 200_000;
        int actual = counter;
        if (actual < expected) {
            System.out.printf("期望 %d，实际 %d，丢失 %d 次更新！volatile 保证了可见性，但 count++ 仍是三步非原子操作%n",
                    expected, actual, expected - actual);
        } else {
            System.out.printf("期望 %d，实际 %d（本次竞态恰好未发生，多跑几次几乎必然出现丢失）%n", expected, actual);
        }
        return expected - actual;
    }

    /** 4. happens-before 发布：写线程先写普通变量再写 volatile；读线程看到 volatile 后读普通变量。返回读到的 payload */
    static int runHappensBeforePublish() throws InterruptedException {
        LabKit.section("4. happens-before：用 volatile 安全\"发布\"普通变量");
        payload = 0;
        published = false;
        int[] seen = {-1};
        Thread reader = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 2000;
            while (!published && System.currentTimeMillis() < deadline) {
                Thread.yield();
            }
            seen[0] = payload; // 看到 published=true 后，payload 一定可见（happens-before 传递）
            LabKit.log("看到 published=true，读取 payload=" + seen[0] + "（保证是 42，不会读到 0）");
        }, "hb-reader");
        Thread writer = new Thread(() -> {
            payload = 42;                 // ① 普通写
            LabKit.log("payload=42 写入完成");
            published = true;             // ② volatile 写：① happens-before ②（程序顺序），② happens-before 读线程的 volatile 读
            LabKit.log("published=true（volatile 写）");
        }, "hb-writer");
        reader.start();
        LabKit.sleep(100);
        writer.start();
        LabKit.join(writer, 3000);
        LabKit.join(reader, 3000);
        return seen[0];
    }

    /** 供测试使用：返回当前 counter 值 */
    static int currentCounter() {
        return counter;
    }
}
