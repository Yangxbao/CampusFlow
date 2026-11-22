package com.campusflow.lab.jvm.oom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OomDiagnosisLab 的 JUnit 测试。
 *
 * <p>安全策略：测试<b>绝不真正触发 OOM</b>（只读参数 + 预算 32MB 的有界触发），保证测试 JVM 安全。
 * 不调用 main（main 里会真实 OOM）。
 */
class OomDiagnosisLabTest {

    @Test
    @Timeout(60)
    void heapOptionsReadableViaHotSpotDiagnosticMXBean() {
        java.util.Map<String, String> options = OomDiagnosisLab.printHeapOptions();
        assertNotNull(options);
        assertTrue(options.containsKey("MaxHeapSize"));
        // MaxHeapSize 应为正数字（字节）
        long maxHeap = Long.parseLong(options.get("MaxHeapSize"));
        assertTrue(maxHeap > 0, "MaxHeapSize 应大于 0，实际: " + maxHeap);
        // HeapDumpOnOutOfMemoryError 是布尔值
        assertTrue(options.get("HeapDumpOnOutOfMemoryError").equals("true")
                        || options.get("HeapDumpOnOutOfMemoryError").equals("false"),
                "HeapDumpOnOutOfMemoryError 应为 true/false");
    }

    @Test
    @Timeout(60)
    void heapDumpFlagRuntimeSettable() throws Exception {
        // HeapDumpOnOutOfMemoryError 是 manageable 标志，运行时可写
        String before = OomDiagnosisLab.getVMOption("HeapDumpOnOutOfMemoryError");
        OomDiagnosisLab.setHeapDumpOnOom(true);
        String after = OomDiagnosisLab.getVMOption("HeapDumpOnOutOfMemoryError");
        assertTrue("true".equals(after), "动态设置后应回读为 true，实际: " + after);
        // 恢复原值，避免影响其他测试（保持现场）
        OomDiagnosisLab.setHeapDumpOnOom("true".equals(before));
    }

    @Test
    @Timeout(60)
    void boundedTriggerDoesNotOomTestJvm() {
        // 预算 32MB << 测试 JVM 堆(256m)：不会真正 OOM，只验证触发器逻辑可控
        OomDiagnosisLab.OomTriggerResult result = OomDiagnosisLab.triggerOom(32L * 1024 * 1024);
        assertFalse(result.oomCaught, "32MB 预算不应触发 OOM");
        assertTrue(result.bytesHeld >= 32L * 1024 * 1024);
        // 触发器返回后堆已释放
        System.gc();
    }
}
