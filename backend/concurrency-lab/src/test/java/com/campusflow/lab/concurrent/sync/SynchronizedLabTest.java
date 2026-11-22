package com.campusflow.lab.concurrent.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SynchronizedLab 的 JUnit 5 测试。
 */
class SynchronizedLabTest {

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @Timeout(30)
    void nestedSynchronizedIsReentrant() {
        SynchronizedLab lab = new SynchronizedLab();
        int depth = lab.nestedSynchronized();
        assertEquals(3, depth, "同一线程嵌套 3 层 synchronized 应全部进入（可重入）");
    }

    @Test
    @Timeout(30)
    void objectLockAndClassLockAreIndependent() throws Exception {
        SynchronizedLab lab = new SynchronizedLab();
        AtomicBoolean t2FinishedWhileT1Holding = new AtomicBoolean(false);
        Thread t1 = new Thread(() -> lab.instanceSyncMethod("t1", 300), "test-obj-lock");
        Thread t2 = new Thread(() -> {
            SynchronizedLab.staticTestHook();
            if (t1.isAlive()) { // t1 还在持有对象锁，t2 却完成了类锁操作
                t2FinishedWhileT1Holding.set(true);
            }
        }, "test-class-lock");
        t1.start();
        sleep(50);
        t2.start();
        t1.join(5000);
        t2.join(5000);
        assertTrue(t2FinishedWhileT1Holding.get(), "类锁与对象锁互不阻塞：t2 应在 t1 持对象锁期间完成");
    }

    @Test
    @Timeout(30)
    void swappedLockObjectBreaksMutualExclusion() throws Exception {
        int maxInside = SynchronizedLab.runLockSwapDemo();
        assertEquals(2, maxInside, "锁对象被更换后，两个线程应同时进入临界区（同步失效）");
    }

    @Test
    @Timeout(30)
    void differentLockObjectsBreakMutualExclusion() throws Exception {
        int maxInside = SynchronizedLab.runDifferentLockDemo();
        assertEquals(2, maxInside, "各自 new 锁对象时两个线程应同时进入临界区（同步失效）");
    }
}
