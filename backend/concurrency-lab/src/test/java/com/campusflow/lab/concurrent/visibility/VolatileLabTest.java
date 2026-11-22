package com.campusflow.lab.concurrent.visibility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VolatileLab 的 JUnit 5 测试。
 * 注意：普通变量的可见性失败受 JIT 优化影响，属"尽力复现"，测试只对确定性行为断言。
 */
class VolatileLabTest {

    @Test
    @Timeout(30)
    void volatileFlagIsVisibleAcrossThreads() throws Exception {
        boolean observed = VolatileLab.runVolatileVisibility(300);
        assertTrue(observed, "volatile 写必须立即对读线程可见（可见性保证）");
    }

    @Test
    @Timeout(60)
    void volatileIncrementIsNotAtomic() throws Exception {
        int lost = VolatileLab.runNonAtomicIncrement();
        assertTrue(lost > 0, "volatile count++ 应发生丢失更新（实际丢 " + lost + " 次）");
    }

    @Test
    @Timeout(30)
    void happensBeforePublication() throws Exception {
        int payload = VolatileLab.runHappensBeforePublish();
        assertEquals(42, payload, "volatile 发布后读线程读到的 payload 必须是最新值 42");
    }
}
