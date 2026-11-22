package com.campusflow.lab.jvm.gc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** GcLogLab 的 JUnit 测试：短负载，验证统计可用、不挂死。 */
class GcLogLabTest {

    @Test
    @Timeout(120)
    void workloadRunsAndProducesStats() {
        // 只跑 3 轮（约 60MB 分配），足够观察若干次 Young GC，又不拖慢测试
        GcLogLab.Stats stats = GcLogLab.runWorkload(3);
        assertTrue(stats.allocatedApproxBytes > 0, "应记录分配总量");
        assertTrue(stats.youngCollectionsAfter >= stats.youngCollectionsBefore);
        assertTrue(stats.wallMillis >= 0);
        // 60MB 分配在默认测试堆下通常触发至少 0 次 GC 也合法（GC 是异步的），只验证口径自洽
        assertTrue(stats.youngCollectionsAfter - stats.youngCollectionsBefore >= 0);
    }

    @Test
    @Timeout(30)
    void unifiedLoggingGuidePrintable() {
        // printUnifiedLoggingGuide 只输出说明文字，验证不抛异常
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream capture = new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8);
        java.io.PrintStream old = System.out;
        try {
            System.setOut(capture);
            GcLogLab.printUnifiedLoggingGuide();
        } finally {
            System.setOut(old);
        }
        String text = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("-Xlog:gc*"), "说明中应包含 -Xlog:gc* 用法");
    }
}
