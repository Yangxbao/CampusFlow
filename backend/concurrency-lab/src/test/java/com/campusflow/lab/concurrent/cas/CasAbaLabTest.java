package com.campusflow.lab.concurrent.cas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CasAbaLab 的 JUnit 5 测试：ABA 复现/检测、CAS 自旋正确性、LongAdder 与 AtomicLong 结果正确。
 */
class CasAbaLabTest {

    @Test
    @Timeout(30)
    void plainAtomicReferenceCannotDetectAba() {
        AtomicReference<String> ref = new AtomicReference<>("A");
        // 模拟 t2 在 t1 不在期间完成 A->B->A
        assertTrue(ref.compareAndSet("A", "B"));
        assertTrue(ref.compareAndSet("B", "A"));
        // t1 回来按旧值做 CAS —— 成功，说明普通 CAS 感知不到 ABA
        assertTrue(ref.compareAndSet("A", "C"), "普通 AtomicReference 无法感知 A->B->A，CAS 仍应成功");
        assertEquals("C", ref.get());
    }

    @Test
    @Timeout(30)
    void stampedReferenceDetectsAba() {
        AtomicStampedReference<String> ref = new AtomicStampedReference<>("A", 1);
        // t2: A/1 -> B/2 -> A/3
        assertTrue(ref.compareAndSet("A", "B", 1, 2));
        assertTrue(ref.compareAndSet("B", "A", 2, 3));
        // t1 用离开时的旧版本做 CAS —— 应失败
        assertFalse(ref.compareAndSet("A", "C", 1, 2), "版本戳已变，应检测到 ABA 并 CAS 失败");
        assertEquals(3, ref.getStamp(), "当前版本应为 3");
        // 持最新版本才能成功
        assertTrue(ref.compareAndSet("A", "C", 3, 4));
        assertEquals("C", ref.getReference());
    }

    @Test
    @Timeout(60)
    void casSpinIncrementIsCorrect() throws Exception {
        long result = CasAbaLab.casSpinIncrement(2, 1000);
        assertEquals(2000L, result, "CAS 自旋累加必须不丢失更新");
    }

    @Test
    @Timeout(60)
    void atomicLongAndLongAdderAreBothCorrect() throws Exception {
        assertEquals(800_000L, CasAbaLab.atomicLongResult(4, 200_000));
        assertEquals(800_000L, CasAbaLab.longAdderResult(4, 200_000));
    }
}
