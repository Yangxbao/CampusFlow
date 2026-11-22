package com.campusflow.lab.jvm.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StackOverflowLab 的 JUnit 测试。
 *
 * <p>安全策略：递归都在<b>子线程</b>中触发并在内部 catch StackOverflowError，
 * {@code @Timeout} 保证测试绝不挂死；栈溢出只消耗该线程自己的栈，不影响测试 JVM。
 */
class StackOverflowLabTest {

    @Test
    @Timeout(120)
    void stackOverflowCaughtAndDepthMeasured() throws Exception {
        // 子线程中触发并 catch，栈溢出不外泄
        AtomicReference<Long> depth = new AtomicReference<>(-1L);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                depth.set(StackOverflowLab.measureMaxDepth());
            } catch (Throwable t) {
                unexpected.set(t);
            }
        }, "so-worker");
        worker.start();
        worker.join(110_000);

        assertTrue(!worker.isAlive(), "子线程必须在超时内结束");
        if (unexpected.get() != null) {
            throw new IllegalStateException("子线程出现未预期异常", unexpected.get());
        }
        assertTrue(depth.get() > 100, "默认栈下递归深度应远大于 100，实际: " + depth.get());
    }

    @Test
    @Timeout(120)
    void smallerStackYieldsSmallerDepth() throws Exception {
        long smallDepth = StackOverflowLab.measureInNewThread(256 * 1024);   // 256KB
        long defaultDepth = StackOverflowLab.measureInNewThread(0);          // JVM 默认（-Xss）
        assertTrue(smallDepth > 0, "256KB 栈也应能递归很多层，实际: " + smallDepth);
        assertTrue(defaultDepth > smallDepth,
                "默认栈深度(" + defaultDepth + ")应大于 256KB 栈深度(" + smallDepth + ")");
    }

    @Test
    @Timeout(120)
    void measureMaxDepthDoesNotLeakError() {
        // measureMaxDepth 内部 catch 了 StackOverflowError，正常返回深度
        long d = StackOverflowLab.measureMaxDepth();
        assertTrue(d > 0, "深度应大于 0，实际: " + d);
    }
}
