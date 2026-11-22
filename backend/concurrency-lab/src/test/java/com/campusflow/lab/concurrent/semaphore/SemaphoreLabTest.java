package com.campusflow.lab.concurrent.semaphore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SemaphoreLab 的 JUnit 5 测试：场馆限流峰值不超过许可数、tryAcquire 超时行为。
 */
class SemaphoreLabTest {

    @Test
    @Timeout(60)
    void venueCapacityNeverExceedsPermits() throws Exception {
        int[] stats = SemaphoreLab.runVenue(3, 10, 200, 10);
        assertEquals(3, stats[1], "在场峰值不得超过许可数 3");
        assertEquals(10, stats[0], "所有 10 名游客最终都应成功进入（排队不丢弃）");
    }

    @Test
    @Timeout(30)
    void tryAcquireTimesOutThenSucceedsAfterRelease() throws Exception {
        boolean[] results = SemaphoreLab.runTryAcquireDemo();
        assertFalse(results[0], "许可被占满时 tryAcquire(300ms) 应超时失败");
        assertTrue(results[1], "release 后 tryAcquire 应成功");
    }
}
