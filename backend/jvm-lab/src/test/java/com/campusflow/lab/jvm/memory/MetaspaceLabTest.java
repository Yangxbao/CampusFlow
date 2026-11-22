package com.campusflow.lab.jvm.memory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MetaspaceLab 的 JUnit 测试。
 *
 * <p>安全策略：
 * <ul>
 *   <li>测试只加载 <b>有上限</b>（300 个）的最小类，绝不真正打爆测试 JVM 的元空间；</li>
 *   <li>加载在子线程进行，OOM 也会被内部 catch 并可接受；</li>
 *   <li>{@code @AfterAll} 清空保留列表，类元数据随后可被 GC 卸载，不污染其他测试。</li>
 * </ul>
 */
class MetaspaceLabTest {

    private static final int SAFE_CLASS_COUNT = 300;

    @Test
    @Timeout(120)
    void handcraftedBytecodeLoadsAndInitializes() throws Exception {
        String name = MetaspaceLab.BASE_PACKAGE + ".HandcraftedSmoke_" + System.nanoTime();
        MetaspaceLab.LeakingClassLoader loader = new MetaspaceLab.LeakingClassLoader();
        Class<?> c = loader.loadGeneratedClass(name);
        assertEquals(name, c.getName());
        assertEquals(loader, c.getClassLoader());
        // 继承自 Object
        assertEquals(Object.class, c.getSuperclass());
    }

    @Test
    @Timeout(120)
    void minimalClassBytesStructureCorrect() {
        String internal = "com/campusflow/lab/jvm/memory/gen/BytesCheck";
        byte[] bytes = MetaspaceLab.minimalClassBytes(internal);
        // magic
        assertEquals(0xCAFEBABE, ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF));
        // major version 52 at offset 6-7
        int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        assertEquals(52, major);
        assertTrue(bytes.length > 20);
    }

    @Test
    @Timeout(120)
    void generateBoundedClassesWithoutOom() throws Exception {
        AtomicReference<MetaspaceLab.LoadResult> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                // 有上限 + 有 deadline 的安全负载：不会打爆测试 JVM 元空间
                resultRef.set(MetaspaceLab.generateUntilOom(SAFE_CLASS_COUNT, Long.MAX_VALUE));
            } catch (Throwable t) {
                unexpected.set(t);
            }
        }, "metaspace-worker");
        worker.start();
        worker.join(110_000);

        assertTrue(!worker.isAlive(), "子线程必须在超时内结束");
        if (unexpected.get() != null) {
            throw new IllegalStateException("子线程出现未预期异常", unexpected.get());
        }
        MetaspaceLab.LoadResult result = resultRef.get();
        assertTrue(result != null);
        assertFalse(result.otherFailure != null,
                "不应有非 OOM 失败: " + (result.otherFailure == null ? "" : result.otherFailure));
        // 未设 MaxMetaspaceSize 的测试 JVM 中 300 个极小类不会 OOM；若真 OOM 也允许（记录即可）
        if (!result.oomCaught) {
            assertEquals(SAFE_CLASS_COUNT, result.classesLoaded, "应完整加载全部安全上限数量的类");
        }
        assertTrue(result.classesLoaded >= SAFE_CLASS_COUNT || result.oomCaught,
                "要么完整加载 300 个类，要么提前 OOM（记录在案）");
    }

    @AfterAll
    static void cleanup() {
        // 释放对 loader/class 的强引用，类元数据随后可整体卸载
        MetaspaceLab.RETAINED_CLASSES.clear();
        MetaspaceLab.RETAINED_LOADERS.clear();
        System.gc();
    }
}
