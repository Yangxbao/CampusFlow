package com.campusflow.lab.concurrent.lock;

import com.campusflow.lab.concurrent.common.LabKit;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ReentrantLock 实验：可重入、公平/非公平对比、tryLock 超时、lockInterruptibly 可中断获取、
 * Condition await/signal 等待通知。
 *
 * 运行：
 *   mvn -pl backend/concurrency-lab compile
 *   java -cp backend/concurrency-lab/target/classes com.campusflow.lab.concurrent.lock.ReentrantLockLab
 */
public class ReentrantLockLab {

    public static void main(String[] args) throws InterruptedException {
        demoReentrancy();
        demoFairVsUnfair();
        demoTryLockTimeout();
        demoLockInterruptibly();
        demoCondition();

        System.out.println();
        System.out.println("========== 观察结论 ==========");
        System.out.println("1) ReentrantLock 可重入：同一线程可 lock 多次，unlock 等次数释放；计数为 0 才真正释放锁。");
        System.out.println("2) 非公平锁（默认）：释放后刚解锁的线程可能立刻再次抢到（插队/ barging），吞吐高；");
        System.out.println("   公平锁：严格按 AQS 等待队列 FIFO 顺序获取，无饥饿但吞吐较低。");
        System.out.println("3) tryLock(timeout)：拿不到锁限时放弃，避免无限阻塞；tryLock() 立即返回。");
        System.out.println("4) lockInterruptibly()：等待锁的过程中可被中断（抛 InterruptedException）；普通 lock() 不响应中断。");
        System.out.println("5) Condition.await/signal 必须持有锁时调用，等待时自动释放锁；可创建多个 Condition 实现\"分组唤醒\"，");
        System.out.println("   这是优于 Object.wait/notify（只能随机/全部唤醒）的关键能力。");
    }

    /** 1. 可重入：连续 lock 两次再 unlock 两次 */
    static void demoReentrancy() throws InterruptedException {
        LabKit.section("1. 可重入：lock() 两次、unlock() 两次");
        ReentrantLock lock = new ReentrantLock();
        AtomicBoolean done = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            lock.lock();
            try {
                LabKit.log("第一次 lock 成功, holdCount=" + lock.getHoldCount());
                lock.lock(); // 同一线程再次获取
                try {
                    LabKit.log("第二次 lock 成功, holdCount=" + lock.getHoldCount() + "（可重入计数 +1）");
                    done.set(true);
                } finally {
                    lock.unlock(); // 计数 -1
                    LabKit.log("第一次 unlock, holdCount=" + lock.getHoldCount() + "（锁仍被本线程持有）");
                }
            } finally {
                lock.unlock();
                LabKit.log("第二次 unlock, holdCount=" + lock.getHoldCount() + "（真正释放）");
            }
        }, "reentrant-worker");
        t.start();
        LabKit.join(t, 3000);
        System.out.println("执行完成 = " + done.get() + " —— 重入计数 holdCount 递增/递减，只有归零才释放。");
    }

    /** 2. 公平/非公平对比：两个线程各快速加锁 rounds 次，记录获取序列 */
    static void demoFairVsUnfair() throws InterruptedException {
        LabKit.section("2. 公平锁 vs 非公平锁：两个线程各 500 次快速 lock/unlock 的获取序列");
        String fairSeq = race(new ReentrantLock(true), 500);
        String unfairSeq = race(new ReentrantLock(false), 500);
        System.out.println("公平锁前 60 次获取: " + fairSeq.substring(0, Math.min(60, fairSeq.length())));
        System.out.println("非公平锁前 60 次获取: " + unfairSeq.substring(0, Math.min(60, unfairSeq.length())));
        System.out.println("公平锁交替次数 = " + transitions(fairSeq) + "/" + (fairSeq.length() - 1)
                + "，非公平锁交替次数 = " + transitions(unfairSeq) + "/" + (unfairSeq.length() - 1));
        System.out.println("公平锁近似严格轮流（FIFO 队列）；非公平锁出现\"同一线程连续抢到\"（释放后立即插队 re-CAS 成功）。");
    }

    /** 两个线程竞争同一把锁各 rounds 次，返回获取顺序序列（A/B 字符串） */
    static String race(ReentrantLock lock, int rounds) throws InterruptedException {
        StringBuilder seq = new StringBuilder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Runnable worker = () -> {
            char me = Thread.currentThread().getName().endsWith("A") ? 'A' : 'B';
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < rounds; i++) {
                lock.lock();
                try {
                    seq.append(me);
                } finally {
                    lock.unlock();
                }
            }
            done.countDown();
        };
        Thread a = new Thread(worker, "racer-A");
        Thread b = new Thread(worker, "racer-B");
        a.start();
        b.start();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        LabKit.join(a, 5000);
        LabKit.join(b, 5000);
        return seq.toString();
    }

    private static int transitions(String seq) {
        int count = 0;
        for (int i = 1; i < seq.length(); i++) {
            if (seq.charAt(i) != seq.charAt(i - 1)) {
                count++;
            }
        }
        return count;
    }

    /** 3. tryLock 超时：持有者持锁 400ms，尝试者 tryLock(100ms) 失败、tryLock(1500ms) 成功 */
    static void demoTryLockTimeout() throws InterruptedException {
        LabKit.section("3. tryLock(timeout)：限时获取，拿不到就放弃");
        ReentrantLock lock = new ReentrantLock();
        AtomicBoolean quickFail = new AtomicBoolean(false);
        AtomicBoolean laterOk = new AtomicBoolean(false);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                LabKit.log("holder 持锁 400ms");
                LabKit.sleep(400);
            } finally {
                lock.unlock();
            }
        }, "trylock-holder");
        Thread tryer = new Thread(() -> {
            try {
                boolean quick = lock.tryLock(100, TimeUnit.MILLISECONDS);
                quickFail.set(!quick);
                LabKit.log("tryLock(100ms) = %s（持有者还没释放 -> 超时失败，不傻等）", quick);
                boolean later = lock.tryLock(1500, TimeUnit.MILLISECONDS);
                laterOk.set(later);
                LabKit.log("tryLock(1500ms) = %s（等到持有者释放 -> 成功）", later);
                if (later) {
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "trylock-tryer");
        holder.start();
        LabKit.sleep(100); // 确保 holder 已持锁
        tryer.start();
        LabKit.join(holder, 5000);
        LabKit.join(tryer, 5000);
        System.out.println("限时失败=" + quickFail.get() + "，限时成功=" + laterOk.get());
    }

    /** 4. lockInterruptibly 可中断等待；对比普通 lock() 不响应中断 */
    static void demoLockInterruptibly() throws InterruptedException {
        LabKit.section("4. lockInterruptibly：等待锁时可被中断；普通 lock() 不响应中断");
        ReentrantLock lock = new ReentrantLock();
        AtomicBoolean interrupted = new AtomicBoolean(false);

        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                LabKit.log("holder 持锁 800ms");
                LabKit.sleep(800);
            } finally {
                lock.unlock();
            }
        }, "intr-holder");
        holder.start();
        LabKit.sleep(100);

        Thread t2 = new Thread(() -> {
            try {
                lock.lockInterruptibly(); // 等待期间可被中断
                try {
                    LabKit.log("t2 通过 lockInterruptibly 拿到了锁");
                } finally {
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                interrupted.set(true);
                LabKit.log("t2 在等待锁的过程中被 interrupt -> 抛出 InterruptedException，优雅放弃");
            }
        }, "intr-waiter");
        t2.start();
        LabKit.sleep(200);
        t2.interrupt();
        LabKit.join(t2, 3000);
        System.out.println("t2 被中断退出 = " + interrupted.get());

        // 对比：普通 lock() 即使被 interrupt 也继续等待
        AtomicBoolean t3GotLock = new AtomicBoolean(false);
        Thread t3 = new Thread(() -> {
            lock.lock(); // 不响应中断
            try {
                LabKit.log("t3 用普通 lock() 拿到了锁（等待期间被 interrupt 过: " + Thread.currentThread().isInterrupted() + "，但 lock() 继续等到了锁）");
                t3GotLock.set(true);
                LabKit.sleep(50);
            } finally {
                lock.unlock();
            }
        }, "plain-lock-waiter");
        t3.start();
        LabKit.sleep(200);
        t3.interrupt();
        LabKit.sleep(100);
        System.out.println("t3 被 interrupt 后 100ms 仍在等待锁 = " + t3.isAlive() + " —— 普通 lock() 不响应中断");
        LabKit.join(holder, 5000);
        LabKit.join(t3, 5000);
        System.out.println("t3 最终拿到锁 = " + t3GotLock.get());
    }

    /** 5. Condition await/signal 等待通知 */
    static void demoCondition() throws InterruptedException {
        LabKit.section("5. Condition await/signal：可中断、可限时、可分组的等待/通知");
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        AtomicBoolean signaled = new AtomicBoolean(false);

        Thread waiter = new Thread(() -> {
            lock.lock();
            try {
                LabKit.log("waiter 持锁后调用 await（释放锁进入等待队列）");
                long start = System.nanoTime();
                boolean ok = condition.await(2, TimeUnit.SECONDS); // 带超时保证实验可结束
                LabKit.log("waiter 被唤醒, await 返回 %s, 等待了 %d ms, 唤醒后继续持锁执行", ok, LabKit.elapsedMillis(start));
                signaled.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "condition-waiter");
        Thread signaler = new Thread(() -> {
            LabKit.sleep(300); // 让 waiter 先进入 await
            lock.lock(); // await 期间锁已释放，signaler 才能拿到
            try {
                LabKit.log("signaler 拿到锁（说明 waiter await 时确实释放了锁）, 执行 signal");
                condition.signal();
            } finally {
                lock.unlock();
            }
        }, "condition-signaler");
        waiter.start();
        signaler.start();
        LabKit.join(waiter, 5000);
        LabKit.join(signaler, 5000);
        System.out.println("waiter 被成功唤醒 = " + signaled.get());
        System.out.println("要点：signal 不是立即唤醒 —— waiter 要等 signaler 释放锁（unlock）后才能从 await 返回并重新拿锁。");
    }
}
