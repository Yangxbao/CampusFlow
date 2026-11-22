package com.campusflow.lab.concurrent.lock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReentrantLockLab 的 JUnit 5 测试：可重入、tryLock 超时、lockInterruptibly、Condition、公平/非公平完整性。
 */
class ReentrantLockLabTest {

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @Timeout(30)
    void lockIsReentrant() {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        lock.lock();
        try {
            assertEquals(2, lock.getHoldCount(), "同一线程重入两次 holdCount 应为 2");
        } finally {
            lock.unlock();
            lock.unlock();
        }
        assertEquals(0, lock.getHoldCount());
    }

    @Test
    @Timeout(30)
    void tryLockTimesOutWhileHeldThenSucceeds() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        AtomicBoolean quickFail = new AtomicBoolean(false);
        AtomicBoolean laterOk = new AtomicBoolean(false);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                sleep(400);
            } finally {
                lock.unlock();
            }
        }, "test-holder");
        Thread tryer = new Thread(() -> {
            try {
                quickFail.set(!lock.tryLock(100, TimeUnit.MILLISECONDS));
                laterOk.set(lock.tryLock(1500, TimeUnit.MILLISECONDS));
                if (laterOk.get()) {
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "test-tryer");
        holder.start();
        sleep(100);
        tryer.start();
        holder.join(5000);
        tryer.join(5000);
        assertFalse(lock.isLocked(), "全部结束后锁应被释放");
        assertTrue(quickFail.get(), "持有者未释放时 tryLock(100ms) 应超时失败");
        assertTrue(laterOk.get(), "tryLock(1500ms) 应等到持有者释放后成功");
    }

    @Test
    @Timeout(30)
    void lockInterruptiblyRespondsToInterrupt() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                sleep(1000);
            } finally {
                lock.unlock();
            }
        }, "test-intr-holder");
        holder.start();
        sleep(100);
        Thread waiter = new Thread(() -> {
            try {
                lock.lockInterruptibly();
                try {
                    lock.unlock();
                } catch (IllegalMonitorStateException ignored) {
                    // 理论上不会走到这里
                }
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        }, "test-intr-waiter");
        waiter.start();
        sleep(200);
        waiter.interrupt();
        waiter.join(5000);
        assertTrue(interrupted.get(), "lockInterruptibly 等待期间被 interrupt 应抛 InterruptedException");
        holder.join(5000);
    }

    @Test
    @Timeout(30)
    void plainLockKeepsWaitingAfterInterrupt() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                sleep(500);
            } finally {
                lock.unlock();
            }
        }, "test-plain-holder");
        holder.start();
        sleep(100);
        AtomicBoolean gotLock = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            lock.lock(); // 普通 lock 不响应中断
            try {
                gotLock.set(true);
            } finally {
                lock.unlock();
            }
        }, "test-plain-waiter");
        waiter.start();
        sleep(100);
        waiter.interrupt();
        sleep(100);
        assertTrue(waiter.isAlive(), "普通 lock() 被 interrupt 后仍应继续等待");
        holder.join(5000);
        waiter.join(5000);
        assertTrue(gotLock.get(), "普通 lock() 最终仍应拿到锁（中断标志被吞掉，锁语义不变）");
    }

    @Test
    @Timeout(30)
    void conditionAwaitIsWokenBySignal() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        AtomicBoolean signaled = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            lock.lock();
            try {
                boolean ok = condition.await(2, TimeUnit.SECONDS);
                signaled.set(ok);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }, "test-cond-waiter");
        Thread signaler = new Thread(() -> {
            sleep(200);
            lock.lock();
            try {
                condition.signal();
            } finally {
                lock.unlock();
            }
        }, "test-cond-signaler");
        waiter.start();
        signaler.start();
        waiter.join(5000);
        signaler.join(5000);
        assertTrue(signaled.get(), "signal 后 waiter 应在超时前被唤醒（await 返回 true）");
    }

    @Test
    @Timeout(60)
    void fairAndUnfairLocksBothCompleteAllIterations() throws Exception {
        String fair = ReentrantLockLab.race(new ReentrantLock(true), 300);
        String unfair = ReentrantLockLab.race(new ReentrantLock(false), 300);
        assertEquals(600, fair.length(), "公平锁场景所有轮次都应被记录");
        assertEquals(600, unfair.length(), "非公平锁场景所有轮次都应被记录");
        assertTrue(fair.chars().filter(c -> c == 'A').count() > 0);
        assertTrue(unfair.chars().filter(c -> c == 'B').count() > 0);
    }
}
