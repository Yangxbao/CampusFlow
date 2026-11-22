package com.campusflow.lab.jvm.deadlock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeadlockLab 的 JUnit 测试。
 *
 * <p>安全策略：死锁线程均为 daemon（不阻碍 JVM 退出）；{@code @Timeout} 保证绝不挂死；
 * 检测循环最长 20s 后必然返回。绝不调用 main（main 内有 System.exit）。
 */
class DeadlockLabTest {

    @Test
    @Timeout(60)
    void deadlockDetectedWithBothThreads() throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buf, true, StandardCharsets.UTF_8);

        DeadlockLab.DetectionResult result = DeadlockLab.runDeadlockScenario(capture, 20);

        assertTrue(result.detected, "ThreadMXBean 必须能检测到人为制造的死锁");
        assertTrue(result.waitedMillis >= 0);
        assertTrue(result.deadlockedThreadNames.contains(DeadlockLab.THREAD_ONE)
                        && result.deadlockedThreadNames.contains(DeadlockLab.THREAD_TWO),
                "两个死锁线程都应出现，实际: " + result.deadlockedThreadNames);

        String output = buf.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("BLOCKED") || output.contains("state="),
                "输出应包含线程状态（BLOCKED 表示在等锁）");
        // JDK17 统一栈格式带模块前缀（如 "at app//com.campusflow..."），因此只断言类名出现
        assertTrue(output.contains(DeadlockLab.class.getName()),
                "应打印死锁线程的栈帧");
    }

    @Test
    @Timeout(30)
    void deadlockThreadsAreDaemonAndReachBlockedState() throws Exception {
        // 独立锁对象：不与 runDeadlockScenario 场景共享，避免相互污染检测结果
        Object lockA = new Object();
        Object lockB = new Object();
        Thread t1 = DeadlockLab.startHolderAThenB(lockA, lockB, "daemon-check-T1");
        Thread t2 = DeadlockLab.startHolderBThenA(lockA, lockB, "daemon-check-T2");
        Thread.sleep(500);
        Thread.State s1 = t1.getState();
        Thread.State s2 = t2.getState();
        System.out.println("  [DeadlockLabTest] t1=" + s1 + " t2=" + s2);

        assertTrue(t1.isDaemon() && t2.isDaemon(), "死锁线程必须为 daemon，保证测试 JVM 可正常退出");
        assertTrue(s1 == Thread.State.BLOCKED, "t1 应阻塞在锁上，实际: " + s1);
        assertTrue(s2 == Thread.State.BLOCKED, "t2 应阻塞在锁上，实际: " + s2);
        // 不 join、不唤醒：daemon 死锁线程随 JVM 生命周期结束，不影响测试进程
    }
}
