package com.campusflow.lab.concurrent.aqs;

import com.campusflow.lab.concurrent.common.LabKit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * AQS 实验：继承 AbstractQueuedSynchronizer 自定义一个【不可重入】独占锁，
 * 实现 tryAcquire/tryRelease/isHeldExclusively，并演示两个线程互斥排队。
 *
 * 运行：
 *   mvn -pl backend/concurrency-lab compile
 *   java -cp backend/concurrency-lab/target/classes com.campusflow.lab.concurrent.aqs.AqsLab
 */
public class AqsLab {

    /**
     * 基于 AQS 的不可重入独占锁。
     * 约定：state=0 未锁定，state=1 已锁定。
     * 不可重入的关键：tryAcquire 不检查持有者线程，只要 state!=0 就失败 ——
     * 即使是当前持有者再次 acquire 也会失败并排队（若调用 lock() 会永久阻塞造成死锁，请用 tryLock 演示）。
     */
    static class NonReentrantLock implements Lock {

        private final Sync sync = new Sync();

        private static final class Sync extends AbstractQueuedSynchronizer {

            /** 当前 state 是否表示"已锁定"（独占模式下被谁持有由 exclusiveOwnerThread 记录） */
            @Override
            protected boolean isHeldExclusively() {
                return getState() == 1;
            }

            /**
             * AQS 模板方法：acquire(1) 时被调用。
             * 仅当 state 从 0 CAS 到 1 成功才获得锁；不校验 owner => 不可重入。
             */
            @Override
            protected boolean tryAcquire(int acquires) {
                if (compareAndSetState(0, 1)) {
                    setExclusiveOwnerThread(Thread.currentThread());
                    return true;
                }
                return false; // 失败后由 AQS 框架把当前线程包装成 Node 加入 CLH 等待队列并 park
            }

            /** AQS 模板方法：release(1) 时被调用，state 1 -> 0 释放 */
            @Override
            protected boolean tryRelease(int releases) {
                if (getState() == 0) {
                    throw new IllegalMonitorStateException("锁未被持有，不能释放");
                }
                setExclusiveOwnerThread(null);
                setState(0); // volatile 写，随后 release 会 unpark 后继节点
                return true;
            }

            Condition newCondition() {
                return new ConditionObject();
            }
        }

        @Override
        public void lock() {
            sync.acquire(1);
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            sync.acquireInterruptibly(1);
        }

        @Override
        public boolean tryLock() {
            return sync.tryAcquire(1);
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(time));
        }

        @Override
        public void unlock() {
            sync.release(1);
        }

        @Override
        public Condition newCondition() {
            return sync.newCondition();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        demoNonReentrant();
        int[] stats = runMutualExclusion(2, 200);
        System.out.printf("互斥统计：受锁保护计数=%d（期望 400），临界区最大并发=%d（应为 1）%n", stats[0], stats[1]);
        runQueueDemo();

        System.out.println();
        System.out.println("========== 观察结论 ==========");
        System.out.println("AQS 的核心要素：");
        System.out.println("1) volatile int state —— 同步状态，全部通过 CAS 修改（getState/setState/compareAndSetState）；");
        System.out.println("2) CLH 变体双向等待队列 —— 获取失败的线程包装成 Node 入队，LockSupport.park 挂起，前驱释放时 unpark；");
        System.out.println("3) 模板方法模式 —— 排队/挂起/唤醒由 AQS 实现，子类只需实现 tryAcquire/tryRelease（独占）");
        System.out.println("   或 tryAcquireShared/tryReleaseShared（共享），本实验的不可重入锁就只改了 tryAcquire 的语义；");
        System.out.println("4) 独占/共享两种模式 + ConditionObject 提供 wait/notify 语义（Semaphore/CountDownLatch/ReentrantLock 都基于 AQS）。");
        System.out.println("不可重入锁的含义：state!=0 时任何线程（含持有者自身）tryAcquire 都失败；");
        System.out.println("若持有者再次调用 lock() 将永久阻塞（死锁），因此本实验用 tryLock() 演示。");
    }

    /** 1. 不可重入验证：持有者再次 tryLock 也失败 */
    static void demoNonReentrant() {
        LabKit.section("1. 自定义不可重入锁：持有者再次 tryLock 也会失败");
        NonReentrantLock lock = new NonReentrantLock();
        lock.lock();
        System.out.println("main 已 lock() 一次（state=1）");
        boolean again = lock.tryLock();
        System.out.println("同一线程再次 tryLock() = " + again
                + "  <- 不可重入：即使是持有者自己也会被拒绝；若此时调用 lock() 将永久阻塞（死锁）");
        lock.unlock();
        boolean afterRelease = lock.tryLock();
        System.out.println("释放后 tryLock() = " + afterRelease);
        lock.unlock();
    }

    /**
     * 2. 两线程互斥：受锁保护的非原子计数不丢失，临界区最大并发为 1。
     * 返回 {受保护计数, 最大并发数}。
     */
    static int[] runMutualExclusion(int threads, int iterationsPerThread) throws InterruptedException {
        LabKit.section("2. 两线程互斥排队：受锁保护的计数不丢失，临界区并发恒为 1");
        NonReentrantLock lock = new NonReentrantLock();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger inside = new AtomicInteger(0);
        AtomicInteger maxInside = new AtomicInteger(0);
        AtomicInteger guardCounter = new AtomicInteger(0);

        Runnable worker = () -> {
            for (int i = 0; i < iterationsPerThread; i++) {
                lock.lock(); // 竞争失败者进入 AQS 同步队列排队
                try {
                    int now = inside.incrementAndGet();
                    maxInside.accumulateAndGet(now, Math::max);
                    order.add(Thread.currentThread().getName());
                    inside.decrementAndGet();
                    guardCounter.incrementAndGet(); // 若互斥被破坏这里会丢更新
                    LabKit.sleep(1);
                } finally {
                    lock.unlock();
                }
            }
        };
        Thread t1 = new Thread(worker, "aqs-t1");
        Thread t2 = new Thread(worker, "aqs-t2");
        long start = System.nanoTime();
        t1.start();
        t2.start();
        LabKit.join(t1, 120_000);
        LabKit.join(t2, 120_000);
        System.out.printf("%d 线程 x %d 次临界区，总耗时 %d ms%n", threads, iterationsPerThread, LabKit.elapsedMillis(start));
        System.out.println("前 20 次持锁线程次序: " + order.subList(0, Math.min(20, order.size())));
        return new int[]{guardCounter.get(), maxInside.get()};
    }

    /** 3. 排队演示：main 持锁 300ms，t2/t3 依次阻塞排队，释放后按队列顺序获得。返回获得锁顺序 */
    static List<String> runQueueDemo() throws InterruptedException {
        LabKit.section("3. 互斥排队时间线：main 持锁 300ms，t2/t3 依次阻塞，释放后按队列顺序获得");
        NonReentrantLock lock = new NonReentrantLock();
        List<String> acquireOrder = Collections.synchronizedList(new ArrayList<>());
        lock.lock();
        System.out.println("main 持有锁 300ms ...");
        Thread t2 = new Thread(() -> {
            LabKit.log("开始 lock()（应阻塞排队）");
            lock.lock();
            try {
                acquireOrder.add("aqs-q2");
                LabKit.log("拿到锁（main 释放后才获得）");
                LabKit.sleep(100);
            } finally {
                lock.unlock();
            }
        }, "aqs-q2");
        Thread t3 = new Thread(() -> {
            LabKit.log("开始 lock()（应阻塞排队）");
            lock.lock();
            try {
                acquireOrder.add("aqs-q3");
                LabKit.log("拿到锁（在 q2 之后）");
                LabKit.sleep(100);
            } finally {
                lock.unlock();
            }
        }, "aqs-q3");
        t2.start();
        LabKit.sleep(50);
        t3.start();
        LabKit.sleep(50);
        System.out.println("main release 锁 ...");
        lock.unlock();
        LabKit.join(t2, 5000);
        LabKit.join(t3, 5000);
        System.out.println("获得锁顺序: " + acquireOrder + " —— 与排队顺序一致（AQS 队列 FIFO 唤醒）。");
        return acquireOrder;
    }

    /** 供测试使用：返回获得锁顺序 */
    static List<String> queueDemoForTest() throws InterruptedException {
        return runQueueDemo();
    }
}
