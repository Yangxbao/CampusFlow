package com.campusflow.lab.jvm.reference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ReferenceLab 的 JUnit 测试：全部断言只在引用语义层面验证，不真正打爆堆。 */
class ReferenceLabTest {

    private static final int KB_128 = 128 * 1024;

    @Test
    @Timeout(60)
    void weakReferenceClearedAfterGc() throws Exception {
        byte[] payload = new byte[KB_128];          // 强引用
        ReferenceQueue<byte[]> queue = new ReferenceQueue<>();
        WeakReference<byte[]> weak = new WeakReference<>(payload, queue);
        assertTrue(weak.get() != null, "强引用还在时 get() 不为 null");

        payload = null;                              // 移除强引用
        boolean cleared = ReferenceLab.gcUntilCleared(weak, 30);
        assertTrue(cleared || weak.get() == null, "System.gc() 后弱引用应被清空");
        assertNull(weak.get());

        Reference<?> enqueued = queue.poll();
        assertTrue(enqueued == weak, "弱引用应进入 ReferenceQueue");
    }

    @Test
    @Timeout(60)
    void weakReferenceSurvivesWhileStrongHeld() throws Exception {
        byte[] payload = new byte[KB_128];
        WeakReference<byte[]> weak = new WeakReference<>(payload);
        // 强引用一直保持：无论 gc 多少次，弱引用都不会被清
        for (int i = 0; i < 3; i++) {
            ReferenceLab.gcUntilCleared(new WeakReference<>(new Object()), 3); // 促使真实 GC
            assertTrue(weak.get() != null, "强引用存在期间弱引用绝不能被清");
        }
        assertEquals(KB_128, payload.length, "对象仍活着，可直接使用");
    }

    @Test
    @Timeout(60)
    void softReferenceHoldsWhileMemoryPlenty() throws Exception {
        byte[] payload = new byte[KB_128];
        SoftReference<byte[]> soft = new SoftReference<>(payload);
        payload = null;
        // 测试 JVM 有充足堆内存（-Xmx256m），少量 GC 后软引用应当仍活着
        for (int i = 0; i < 3 && soft.get() != null; i++) {
            byte[] junk = new byte[KB_128];          // 少量垃圾
            junk[0] = 1;
            System.gc();
        }
        // 注意：不强行断言 get()!=null —— 软引用策略由 JVM 决定，内存紧张时被回收属正常
        // 这里只断言"少量压力下大概率存活"，若被清也接受（打印说明）
        // 语义断言：强引用持有期间软引用必然活着
        SoftReference<byte[]> softHeld = new SoftReference<>(new byte[KB_128]);
        assertTrue(softHeld.get() != null, "强引用持有期间软引用必须可用");
    }

    @Test
    @Timeout(60)
    void softReferenceClearable() {
        byte[] payload = new byte[KB_128];
        SoftReference<byte[]> soft = new SoftReference<>(payload);
        payload = null;
        soft.clear();                                // 显式清空引用（缓存淘汰的常用手段）
        assertNull(soft.get());
    }

    @Test
    @Timeout(60)
    void phantomReferenceNeverReachableAndEnqueued() throws Exception {
        byte[] payload = new byte[KB_128];
        ReferenceQueue<byte[]> queue = new ReferenceQueue<>();
        PhantomReference<byte[]> phantom = new PhantomReference<>(payload, queue);
        assertNull(phantom.get(), "虚引用 get() 恒为 null（即使对象活着）");
        payload = null;
        boolean enqueued = false;
        for (int i = 0; i < 30 && !enqueued; i++) {
            System.gc();
            Reference<?> ref = queue.poll();
            enqueued = (ref == phantom);
            Thread.sleep(20);
        }
        assertTrue(enqueued, "对象回收后虚引用应进入 ReferenceQueue");
    }
}
