package com.campusflow.lab.jvm.gc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** GcCompareLab 的 JUnit 测试：短负载验证测量口径与收集器识别。 */
class GcCompareLabTest {

    @Test
    @Timeout(120)
    void workloadMeasuresAllocationAndGc() {
        // 小堆(-Xmx256m)下 800MB 负载太久，这里用参数化小负载？runWorkload 无参数，
        // 800MB 分配对 256m 堆约需 3-6 秒，可接受（@Timeout 120s 保护）。
        GcCompareLab.Stats stats = GcCompareLab.runWorkload();
        assertTrue(stats.allocatedBytes > 0, "应测量到真实分配字节（com.sun.management.ThreadMXBean）");
        assertTrue(stats.gcCountDelta >= 0);
        assertTrue(stats.wallMillis > 0);
        assertTrue(stats.gcTimeRatio >= 0.0 && stats.gcTimeRatio < 1.0, "GC 时间占比应在 [0,1) 内");
    }

    @Test
    @Timeout(30)
    void detectCollectorReturnsKnownName() {
        String collector = GcCompareLab.detectCollector();
        assertTrue(collector != null && !collector.isEmpty());
        // JDK17 可用的收集器集合：G1(默认)/ZGC/Parallel/Serial/Shenandoah(非官方构建可能没有)
        assertTrue(collector.contains("G1") || collector.contains("ZGC")
                        || collector.contains("Parallel") || collector.contains("PS")
                        || collector.contains("Serial") || collector.contains("Shenandoah")
                        || collector.equals("unknown"),
                "应识别出已知收集器之一，实际: " + collector);
    }
}
