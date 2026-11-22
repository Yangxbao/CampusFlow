package com.campusflow.lab.concurrent.counter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RaceConditionLab 的 JUnit 5 测试：竞态复现 + 三种修复的正确性。
 */
class RaceConditionLabTest {

    @Test
    @Timeout(60)
    void unsynchronizedIncrementLosesUpdates() throws Exception {
        int lost = RaceConditionLab.unsafeIncrementRace(5);
        assertTrue(lost > 0, "无同步的 count++ 应发生丢失更新（2 线程 x 100000 次），实际丢 " + lost + " 次");
    }

    @Test
    @Timeout(60)
    void synchronizedFixIsCorrect() throws Exception {
        assertEquals(200_000, RaceConditionLab.synchronizedFix(2, 100_000), "synchronized 修复后结果必须精确");
    }

    @Test
    @Timeout(60)
    void atomicLongFixIsCorrect() throws Exception {
        assertEquals(200_000L, RaceConditionLab.atomicLongFix(2, 100_000), "AtomicLong 修复后结果必须精确");
    }

    @Test
    @Timeout(60)
    void longAdderFixIsCorrect() throws Exception {
        assertEquals(200_000L, RaceConditionLab.longAdderFix(2, 100_000), "LongAdder 修复后结果必须精确");
    }
}
