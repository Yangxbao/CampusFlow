package com.campusflow.lab.concurrent.sync;

import com.campusflow.lab.concurrent.common.LabKit;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * synchronized 实验：对象锁/类锁、可重入、失效场景（锁对象被更换 / 各自 new 锁对象）、
 * 锁升级（无锁→偏向锁→轻量级锁→重量级锁）说明。
 *
 * 运行：
 *   mvn -pl backend/concurrency-lab compile
 *   java -cp backend/concurrency-lab/target/classes com.campusflow.lab.concurrent.sync.SynchronizedLab
 */
public class SynchronizedLab {

    /** 锁对象字段 —— 用于演示"锁对象被更换"导致同步失效 */
    private static Object swapLock = new Object();

    /** 临界区计数器：统计最大并发进入数 */
    private static final AtomicInteger INSIDE = new AtomicInteger(0);
    private static final AtomicInteger MAX_INSIDE = new AtomicInteger(0);

    private static void enter() {
        int now = INSIDE.incrementAndGet();
        MAX_INSIDE.accumulateAndGet(now, Math::max);
    }

    private static void exit() {
        INSIDE.decrementAndGet();
    }

    private static void resetCounters() {
        INSIDE.set(0);
        MAX_INSIDE.set(0);
    }

    /** 静态方法 synchronized —— 锁的是 Class 对象（类锁） */
    private static synchronized void staticSyncMethod(String who) {
        LabKit.log("%s 持有【类锁 SynchronizedLab.class】执行静态同步方法", who);
    }

    /** 供测试/演示使用：显式 synchronized(SynchronizedLab.class) 持有类锁 100ms */
    static void staticTestHook() {
        synchronized (SynchronizedLab.class) {
            LabKit.log("钩子持有【类锁 SynchronizedLab.class】100ms");
            LabKit.sleep(100);
        }
    }

    /** 实例方法 synchronized —— 锁的是当前实例（对象锁），持锁期间休眠指定毫秒 */
    synchronized void instanceSyncMethod(String who, long holdMillis) {
        LabKit.log("%s 拿到【对象锁 this】，持锁 %d ms", who, holdMillis);
        LabKit.sleep(holdMillis);
        LabKit.log("%s 释放对象锁", who);
    }

    // ========== 可重入演示用的嵌套方法 ==========
    private final Object reentrantLock = new Object();

    /** 嵌套三层 synchronized（同一把锁），返回到达的最深层级 */
    public int nestedSynchronized() {
        int depth = 0;
        synchronized (reentrantLock) {
            depth++;
            synchronized (reentrantLock) {
                depth++;
                synchronized (reentrantLock) {
                    depth++;
                    LabKit.log("第 3 层 synchronized 成功进入 —— 同一线程重复获取同一把锁不会阻塞（可重入）");
                }
            }
        }
        return depth;
    }

    public static void main(String[] args) throws InterruptedException {
        demoObjectLockVsClassLock();
        demoReentrant();
        runLockSwapDemo();
        runDifferentLockDemo();
        explainLockEscalation();

        System.out.println();
        System.out.println("========== 观察结论 ==========");
        System.out.println("1) synchronized(实例方法/this) 与 synchronized(静态方法/Class) 是两把不同的锁，互不阻塞；");
        System.out.println("   同一把锁（同一对象）上的同步块才互斥。");
        System.out.println("2) synchronized 可重入：同一线程对同一锁对象反复加锁不会死锁，monitor 拥有者计数累加，退出等次数才释放。");
        System.out.println("3) 两大失效场景：a) 锁对象引用在运行期被更换（旧持有者锁旧对象、新线程锁新对象）；");
        System.out.println("   b) 每个线程各自 new 锁对象。synchronized 锁的是\"某个对象的监视器\"而不是\"这段代码\"，");
        System.out.println("   锁对象必须是被所有竞争线程共享且不被替换的同一个实例（实践上声明为 private final）。");
        System.out.println("4) JVM 内 synchronized 会经历 无锁→偏向锁→轻量级锁→重量级锁 的升级（JDK17 默认禁用偏向锁），");
        System.out.println("   竞争激烈时膨胀为依赖操作系统 mutex 的重量级锁。");
    }

    /** 1. 对象锁 vs 类锁：互不阻塞；同一对象锁才互斥 */
    static void demoObjectLockVsClassLock() throws InterruptedException {
        LabKit.section("1. 对象锁 vs 类锁：两把不同的锁互不阻塞；同一把对象锁才互斥");
        SynchronizedLab lab = new SynchronizedLab();

        Thread t1 = new Thread(() -> lab.instanceSyncMethod("t1", 300), "obj-lock-thread");
        Thread t2 = new Thread(() -> {
            LabKit.log("尝试获取【类锁】...");
            staticSyncMethod("t2");
        }, "class-lock-thread");

        t1.start();
        LabKit.sleep(50); // 确保 t1 已持有对象锁
        t2.start();
        LabKit.join(t1, 3000);
        LabKit.join(t2, 3000);
        System.out.println("现象：t1 持对象锁 300ms 期间，t2 用类锁照常进入 —— 对象锁与类锁是两把不同的锁。");
        System.out.println();

        // 同一把对象锁上的互斥
        Thread t3 = new Thread(() -> lab.instanceSyncMethod("t3", 200), "same-lock-1");
        Thread t4 = new Thread(() -> {
            LabKit.log("t4 尝试获取同一个实例的对象锁（应等待 t3 释放）...");
            lab.instanceSyncMethod("t4", 100);
        }, "same-lock-2");
        t3.start();
        LabKit.sleep(50);
        t4.start();
        LabKit.join(t3, 3000);
        LabKit.join(t4, 3000);
        System.out.println("现象：t4 必须等 t3 释放【同一实例的对象锁】后才能进入 —— 同一把锁互斥。");
    }

    /** 2. 可重入：嵌套对同一对象加锁 */
    static void demoReentrant() throws InterruptedException {
        LabKit.section("2. 可重入：同一方法链上嵌套获取同一把锁");
        SynchronizedLab lab = new SynchronizedLab();
        int[] depth = new int[1];
        Thread t = new Thread(() -> depth[0] = lab.nestedSynchronized(), "reentrant-thread");
        t.start();
        LabKit.join(t, 3000);
        System.out.println("嵌套 " + depth[0] + " 层 synchronized 全部进入，没有死锁 —— monitor 拥有者+计数机制保证可重入。");
    }

    /** 3. 失效场景一：锁对象被更换（返回最大并发进入数） */
    static int runLockSwapDemo() throws InterruptedException {
        LabKit.section("3. synchronized 失效场景一：锁对象在运行期被更换");
        resetCounters();
        Thread t1 = new Thread(() -> {
            synchronized (swapLock) { // 捕获的是"当时"的旧对象
                enter();
                LabKit.log("t1 持有【旧锁对象】进入临界区（300ms）");
                LabKit.sleep(300);
                exit();
            }
            LabKit.log("t1 退出临界区");
        }, "swap-t1");
        t1.start();
        LabKit.sleep(100);
        swapLock = new Object(); // 更换锁对象引用！
        System.out.println("main 已将 swapLock 指向新对象（t1 仍持有旧对象的锁）");
        Thread t2 = new Thread(() -> {
            synchronized (swapLock) { // 新对象，与 t1 的旧锁不是同一把锁
                enter();
                LabKit.log("t2 拿到【新锁对象】，在 t1 还未退出时就进入了\"同一逻辑临界区\"！");
                exit();
            }
        }, "swap-t2");
        t2.start();
        LabKit.join(t1, 3000);
        LabKit.join(t2, 3000);
        System.out.printf("现象：临界区最大并发进入数 = %d（=2 说明同步失效，正确实现应为 1）%n", MAX_INSIDE.get());
        return MAX_INSIDE.get();
    }

    /** 4. 失效场景二：每个线程各自 new 锁对象（返回最大并发进入数） */
    static int runDifferentLockDemo() throws InterruptedException {
        LabKit.section("4. synchronized 失效场景二：每个线程各自 new 一个锁对象");
        resetCounters();
        Thread t1 = new Thread(() -> {
            synchronized (new Object()) { // 锁的是自己 new 的对象
                enter();
                LabKit.log("t1 进入临界区（锁的是自己 new 的对象）");
                LabKit.sleep(200);
                exit();
            }
        }, "diff-t1");
        Thread t2 = new Thread(() -> {
            synchronized (new Object()) { // 又是另一个对象
                enter();
                LabKit.log("t2 进入临界区（锁的是自己 new 的对象）");
                LabKit.sleep(200);
                exit();
            }
        }, "diff-t2");
        t1.start();
        t2.start();
        LabKit.join(t1, 3000);
        LabKit.join(t2, 3000);
        System.out.printf("现象：临界区最大并发进入数 = %d（=2，两把锁互不相干，等于没加锁）%n", MAX_INSIDE.get());
        return MAX_INSIDE.get();
    }

    /** 5. 锁升级说明 */
    static void explainLockEscalation() {
        LabKit.section("5. 锁升级（无锁→偏向锁→轻量级锁→重量级锁）说明");
        System.out.println("· synchronized 的锁状态记录在对象头 Mark Word 中，随竞争情况逐级升级：");
        System.out.println("   无锁 -> 偏向锁（仅一个线程访问，Mark Word 记录线程 ID，加解锁无需 CAS）");
        System.out.println("        -> 轻量级锁（出现交替竞争，栈帧建 Lock Record，CAS 自旋获取）");
        System.out.println("        -> 重量级锁（竞争激烈，膨胀为 ObjectMonitor，依赖操作系统 mutex，线程挂起/唤醒有内核态开销）");
        System.out.println("· 升级由 Mark Word 的锁标志位驱动，HotSpot 常规路径上锁只升不降。");
        System.out.println("· 注意：JDK15 起偏向锁被废弃（JEP 374），JDK17 默认 -XX:-UseBiasedLocking，");
        System.out.println("  即默认不再走偏向锁，轻度无竞争直接用轻量级锁（CAS）。");
        System.out.println("· 字节码层面：同步代码块编译为 monitorenter/monitorexit 指令；同步方法带 ACC_SYNCHRONIZED 标志，");
        System.out.println("  由 JVM 在方法调用时自动加解锁（可用 javap -v 观察）。");
    }
}
