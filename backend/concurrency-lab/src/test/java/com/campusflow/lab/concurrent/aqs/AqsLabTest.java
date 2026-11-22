package com.campusflow.lab.concurrent.aqs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AqsLab 的 JUnit 5 测试：自定义 AQS 不可重入独占锁的正确性验证。
 */
class AqsLabTest {

    @Test
    @Timeout(30)
    void nonReentrantOwnerTryLockFails() {
        AqsLab.NonReentrantLock lock = new AqsLab.NonReentrantLock();
        lock.lock();
        try {
            assertFalse(lock.tryLock(), "不可重入锁：持有者再次 tryLock 也应失败");
        } finally {
            lock.unlock();
        }
        assertTrue(lock.tryLock(), "释放后应可重新获取");
        lock.unlock();
    }

    @Test
    @Timeout(60)
    void mutualExclusionPreservesCounterAndMaxConcurrencyIsOne() throws Exception {
        int[] stats = AqsLab.runMutualExclusion(2, 200);
        assertEquals(400, stats[0], "受自定义锁保护的计数器不能丢失更新");
        assertEquals(1, stats[1], "临界区最大并发数必须为 1（互斥正确）");
    }

    @Test
    @Timeout(30)
    void queuedThreadsAcquireAfterReleaseInOrder() throws Exception {
        List<String> order = AqsLab.queueDemoForTest();
        assertEquals(2, order.size(), "两个排队线程都应先后获得锁");
        assertEquals("aqs-q2", order.get(0), "先排队者应先获得锁（AQS 队列 FIFO）");
        assertEquals("aqs-q3", order.get(1), "后排队者应后获得锁");
    }
}
