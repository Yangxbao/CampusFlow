package com.campusflow.lab.concurrent.threadlocal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ThreadLocalLab 的 JUnit 5 测试：线程隔离、线程池污染复现、finally 清理范式。
 */
class ThreadLocalLabTest {

    @Test
    @Timeout(30)
    void threadLocalIsolatesPerThread() throws Exception {
        List<String> values = ThreadLocalLab.runIsolation();
        assertEquals(3, values.size());
        assertTrue(values.contains("用户-A") && values.contains("用户-B") && values.contains("用户-C"),
                "每个线程都应读到自己的值: " + values);
        assertEquals(3, values.stream().distinct().count(), "三个线程读到的值应各不相同（隔离性）");
    }

    @Test
    @Timeout(30)
    void missingRemovePollutesPooledThread() throws Exception {
        String leaked = ThreadLocalLab.runPollution(false);
        assertEquals("user-A", leaked, "任务2 在未 set 前应读到任务1 残留的 user-A（数据串线程）");
    }

    @Test
    @Timeout(30)
    void removeInFinallyPreventsPollution() throws Exception {
        String leaked = ThreadLocalLab.runPollution(true);
        assertNull(leaked, "finally 中 remove() 后，下一个任务应读到 null");
    }
}
