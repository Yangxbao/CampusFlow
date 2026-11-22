package com.campusflow.lab.jvm.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HeapOverflowLab 的 JUnit 测试。
 *
 * <p>安全策略：测试 JVM 由 surefire argLine 限制为 -Xmx256m（见模块 pom.xml），
 * 填充逻辑在<b>子线程</b>中执行并在子线程内 catch OutOfMemoryError，
 * 保证 OOM 不会逃逸到测试线程，也绝不会挂死测试进程。
 */
class HeapOverflowLabTest {

    @Test
    @Timeout(120)
    void heapOverflowTriggeredAndCaughtInChildThread() throws Exception {
        AtomicReference<HeapOverflowLab.FillResult> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                // 不设预算：一路填到真实 OOM（测试 JVM 堆被限制为 256m，几秒内触发）
                resultRef.set(HeapOverflowLab.fillUntilOom(Long.MAX_VALUE));
            } catch (Throwable t) {
                unexpected.set(t);
            }
        }, "heap-overflow-worker");
        worker.start();
        worker.join(110_000);

        assertFalse(worker.isAlive(), "子线程必须在超时内结束");
        if (unexpected.get() != null) {
            // OOM 之外不允许有任何异常逃逸
            throw new IllegalStateException("子线程出现未预期的异常", unexpected.get());
        }
        HeapOverflowLab.FillResult result = resultRef.get();
        assertTrue(result != null, "子线程必须产出填充结果");
        assertTrue(result.oomCaught, "必须在小堆下真实触发 OutOfMemoryError");
        assertTrue(result.oomDetail != null && result.oomDetail.contains("OutOfMemoryError"),
                "异常信息应包含 OutOfMemoryError，实际: " + result.oomDetail);
        assertTrue(result.totalBytes > 0, "触发 OOM 前应持有大量字节");

        // 兜底：释放全部强引用并建议 GC，让测试 JVM 恢复正常
        System.gc();
        assertTrue(Runtime.getRuntime().maxMemory() > 0);
    }

    @Test
    @Timeout(60)
    void fillStopsWithinBudgetWithoutOom() {
        // 预算 8MB（8 个 1MB 块），远小于测试 JVM 堆 => 不会真正 OOM，应正常返回
        HeapOverflowLab.FillResult result = HeapOverflowLab.fillUntilOom(8L * 1024 * 1024);
        assertFalse(result.oomCaught, "8MB 预算不应触发 OOM");
        assertEquals(8L * 1024 * 1024, result.totalBytes, "应精确持有预算字节量");
        assertEquals(-1, result.chunks);
    }

    @Test
    @Timeout(30)
    void minimalClassBytesStillValid() {
        // 与 HeapOverflowLab 无关的轻量健全性检查：确保测试 JVM 堆参数合理
        Runtime rt = Runtime.getRuntime();
        assertTrue(rt.maxMemory() >= 64L * 1024 * 1024,
                "测试 JVM 堆应至少 64MB（surefire argLine 设为 256m）");
    }
}
