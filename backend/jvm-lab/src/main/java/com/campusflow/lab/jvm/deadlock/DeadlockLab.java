package com.campusflow.lab.jvm.deadlock;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

/**
 * 实验：制造死锁 -> 用 {@link ThreadMXBean#findDeadlockedThreads()} 自动检测 -> 打印死锁线程栈 -> System.exit 退出。
 *
 * <p>运行方式：
 * <pre>
 *   java -cp target/classes com.campusflow.lab.jvm.deadlock.DeadlockLab
 * </pre>
 *
 * <p>死锁四要素：互斥、请求与保持、不可剥夺、循环等待。两个线程交叉获取两把锁即形成循环等待。
 *
 * <p>注意：main 结束前调用 {@code System.exit(0)}，避免非 daemon 的死锁线程把进程永久挂住；
 * 生产环境里遇到死锁进程只能重启（或 jstack 找到锁链修复代码），JVM 不会自动解开 synchronized 死锁。
 */
public class DeadlockLab {

    /** 线程一：先拿 A，再要 B。 */
    public static final String THREAD_ONE = "Deadlock-T1-HolderA";
    /** 线程二：先拿 B，再要 A。 */
    public static final String THREAD_TWO = "Deadlock-T2-HolderB";

    public static void main(String[] args) throws Exception {
        System.out.println("== DeadlockLab：死锁制造、自动检测与栈打印 ==");
        System.out.println("提示：另开终端可用 jstack <pid> / jcmd <pid> Thread.print 看到 \"Found one Java-level deadlock\"");

        DetectionResult result = runDeadlockScenario(System.out, 15);

        System.out.println();
        System.out.println("---- 分析要点 ----");
        System.out.println("1) 检测结果: " + (result.detected ? "检测到死锁: " + result.deadlockedThreadNames : "未检测到死锁(异常)"));
        System.out.println("2) ThreadMXBean.findDeadlockedThreads() 返回处于 monitor 死锁或 ownable synchronizer 死锁的线程 id；");
        System.out.println("    另有 findMonitorDeadlockedThreads()（仅 monitor，JDK6 前的老接口）；");
        System.out.println("3) synchronized 死锁 JVM 不会自动恢复；java.util.concurrent.locks.Lock 支持 tryLock(timeout) 主动避免；");
        System.out.println("4) 预防手段：全局锁顺序（按对象 hash/编号排序加锁）、tryLock + 超时回退、缩小锁粒度、无锁结构；");
        System.out.println("5) 线上排查：jstack <pid> 直接输出 \"Found one Java-level deadlock\"；jconsole/jvisualvm 也可一键检测。");

        // 必须退出：死锁的两个线程若为非 daemon 会永久挂起 JVM
        System.out.println();
        System.out.println("实验结束，System.exit(0) 退出（避免死锁线程挂住进程）。");
        System.exit(0);
    }

    /** 死锁检测的结果摘要（供 main 与 JUnit 共用）。 */
    public static final class DetectionResult {
        public final boolean detected;
        public final String deadlockedThreadNames;
        public final long waitedMillis;

        public DetectionResult(boolean detected, String deadlockedThreadNames, long waitedMillis) {
            this.detected = detected;
            this.deadlockedThreadNames = deadlockedThreadNames;
            this.waitedMillis = waitedMillis;
        }
    }

    /**
     * 制造死锁并轮询检测，把死锁线程栈打印到 out，返回检测结果。
     * 死锁线程均为 daemon，方法返回后不会阻碍 JVM 退出。
     *
     * @param maxWaitSeconds 最长等待检测成功的时间（秒）
     */
    public static DetectionResult runDeadlockScenario(PrintStream out, int maxWaitSeconds) throws InterruptedException {
        out.println();
        out.println("[1] 启动两个交叉持锁的线程...");
        // 每次场景使用全新锁对象：避免与其他场景（如重复调用/并发测试）共享锁而相互干扰
        Object lockA = new Object();
        Object lockB = new Object();
        Thread t1 = startHolderAThenB(lockA, lockB, THREAD_ONE);
        Thread t2 = startHolderBThenA(lockA, lockB, THREAD_TWO);

        // 等两个线程都进入死锁状态（各自持有一把锁并在另一把锁上 BLOCKED）
        t1.join(300);
        t2.join(300);
        out.println("[2] 线程已就位: t1 state=" + t1.getState() + ", t2 state=" + t2.getState());

        out.println("[3] 用 ThreadMXBean 轮询 findDeadlockedThreads()（最长 " + maxWaitSeconds + "s）...");
        ThreadMXBean tmxb = ManagementFactory.getThreadMXBean();
        long t0 = System.currentTimeMillis();
        long[] deadlockedIds = null;
        long deadline = t0 + maxWaitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            deadlockedIds = tmxb.findDeadlockedThreads();
            if (deadlockedIds != null && deadlockedIds.length > 0) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        long waited = System.currentTimeMillis() - t0;

        if (deadlockedIds == null || deadlockedIds.length == 0) {
            out.println("[4] 超时未检测到死锁（异常情况，请检查线程是否真的互相阻塞）");
            return new DetectionResult(false, "", waited);
        }

        out.println("[4] 检测到死锁！等待耗时 " + waited + "ms，死锁线程 " + deadlockedIds.length + " 个：");
        StringBuilder names = new StringBuilder();
        for (long id : deadlockedIds) {
            ThreadInfo info = tmxb.getThreadInfo(id, 25); // 含锁持有者与 25 帧栈（getLockName/getLockOwnerName 内置）
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(info.getThreadName());
            out.println();
            out.println("    ---- 线程 \"" + info.getThreadName() + "\" (id=" + info.getThreadId()
                    + ") state=" + info.getThreadState() + " ----");
            out.println("    等待的锁 lockName=" + info.getLockName()
                    + "  持有者=" + info.getLockOwnerName() + "(id=" + info.getLockOwnerId() + ")");
            StackTraceElement[] stack = info.getStackTrace();
            for (int i = 0; i < stack.length && i < 12; i++) {
                out.println("        at " + stack[i]);
            }
        }
        out.println();
        out.println("    死锁环: " + names + "  （t1 持 A 等 B，t2 持 B 等 A -> 循环等待）");

        // jstack 输出同款信息（脱机演示用）
        out.println();
        out.println("    等价命令: jstack <pid>  或  jcmd <pid> Thread.print");
        out.println("    输出尾部会有: Found one Java-level deadlock: ... \"Deadlock-T1-HolderA\" ... \"Deadlock-T2-HolderB\"");

        return new DetectionResult(true, names.toString(), waited);
    }

    /** 启动"先拿 A 后要 B"的死锁线程（daemon），返回该线程。 */
    static Thread startHolderAThenB(Object lockA, Object lockB, String name) {
        Thread t = new Thread(() -> holdALockThenB(lockA, lockB), name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 启动"先拿 B 后要 A"的死锁线程（daemon），返回该线程。 */
    static Thread startHolderBThenA(Object lockA, Object lockB, String name) {
        Thread t = new Thread(() -> holdBLockThenA(lockA, lockB), name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void holdALockThenB(Object lockA, Object lockB) {
        synchronized (lockA) {
            sleepQuietly(200); // 确保另一线程先拿到 lockB，形成交叉
            synchronized (lockB) { // 在这里永远 BLOCKED：lockB 被对方持有
                // 永远到不了这里
            }
        }
    }

    static void holdBLockThenA(Object lockA, Object lockB) {
        synchronized (lockB) {
            synchronized (lockA) { // 在这里永远 BLOCKED：lockA 被对方持有
                // 永远到不了这里
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
