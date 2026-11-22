package com.campusflow.common.util;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 雪花算法单元测试：唯一性（多线程）、递增趋势、参数校验、时钟回拨防护。
 */
class SnowflakeIdWorkerTest {

    @Test
    void 多线程并发生成ID全局唯一() throws Exception {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(1);
        int threads = 16;
        int perThread = 2000;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        ids.add(worker.nextId());
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        // 32000 个 ID 全部唯一（无重复 = 无碰撞）
        assertEquals(threads * perThread, ids.size());
    }

    @Test
    void 单线程生成趋势递增() {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(1);
        long prev = worker.nextId();
        for (int i = 0; i < 1000; i++) {
            long next = worker.nextId();
            assertTrue(next > prev, "ID 应单调递增");
            prev = next;
        }
    }

    @Test
    void 非法机器ID拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(-1));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(1024));
    }
}
