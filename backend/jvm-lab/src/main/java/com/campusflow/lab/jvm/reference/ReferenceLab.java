package com.campusflow.lab.jvm.reference;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 实验：强引用 / 软引用(SoftReference) / 弱引用(WeakReference) / 虚引用(PhantomReference) 行为对比。
 *
 * <p>运行方式：
 * <pre>
 *   java -Xmx64m -cp target/classes com.campusflow.lab.jvm.reference.ReferenceLab
 * </pre>
 * （小堆下软引用更容易被回收，现象更明显；不限制堆也能跑，软引用可能长期存活）
 *
 * <p>四种引用对比表（结论）：
 * <pre>
 *   强引用 Strong   : 普通赋值。只要可达就绝不回收 —— OOM 的根源。
 *   软引用 Soft     : 内存不足（GC 后仍紧张）时才回收。适合缓存（Guava Cache key 之外的兜底、图片缓存）。
 *   弱引用 Weak     : 下一次 GC 必回收（不管内存够不够）。适合 WeakHashMap / ThreadLocalMap 的 key。
 *   虚引用 Phantom  : get() 永远返回 null；唯一作用是"对象被回收"这一事件到达 ReferenceQueue，
 *                     用于堆外内存/直接内存的回收联动（DirectByteBuffer 的 Cleaner 机制）。
 * </pre>
 */
public class ReferenceLab {

    /** 每个实验对象持有 1MB，方便观察内存变化。 */
    public static final int PAYLOAD_SIZE = 1024 * 1024;

    public static void main(String[] args) throws Exception {
        System.out.println("== ReferenceLab：四种引用行为对比 ==");
        System.out.println("提示：小堆运行现象更明显 -> java -Xmx64m -cp target/classes "
                + ReferenceLab.class.getName());
        System.out.println();

        strongRefDemo();
        weakRefDemo();
        softRefDemo();
        phantomRefDemo();

        System.out.println();
        System.out.println("---- 分析要点 ----");
        System.out.println("1) 可达性分析从 GC Roots（栈帧局部变量、静态字段、JNI 引用、活跃线程等）出发，");
        System.out.println("   引用可达性强度：强 > 软 > 弱 > 虚；reference 引用的对象不再被强引用链覆盖时，");
        System.out.println("   GC 按引用强度策略决定是否回收并把 Reference 登记到 ReferenceQueue；");
        System.out.println("2) 软引用回收策略（默认策略实现）：按空闲堆比例 LRU， SoftRefLRUPolicyMSPerMB（默认1000ms）");
        System.out.println("   => 软引用对象在 GC 后还能存活 heapFreeMB * 1000 毫秒，内存紧张时先于 OOM 被清；");
        System.out.println("3) 虚引用必须在构造时传入 ReferenceQueue，且 get() 恒为 null；");
        System.out.println("   JDK 里 DirectByteBuffer 回收依赖 PhantomReference（Cleaner/PhantomReferent）+ Reference Handler 线程；");
        System.out.println("4) ThreadLocal 内存泄漏场景：ThreadLocalMap 的 Entry 的 key 是弱引用，value 是强引用；");
        System.out.println("   key 被回收后 value 仍被 Thread -> Map -> Entry 强引用，需 remove() 或线程结束才释放；");
        System.out.println("5) ReferenceQueue.poll() 非阻塞，remove() 阻塞；Reference Handler 线程负责入队。");
    }

    /* =============== 1. 强引用 =============== */
    private static void strongRefDemo() {
        System.out.println("[1] 强引用：只要引用还在，System.gc() 也不会回收");
        byte[] payload = new byte[PAYLOAD_SIZE];       // 强引用，payload 被 main 栈帧持有
        byte[] notCleared = payload;
        System.gc();
        System.out.println("    gc() 后对象仍存活（引用还在栈帧里）: " + (payload.length == PAYLOAD_SIZE));
        payload = null;                                // 去掉一个引用
        System.gc();
        System.out.println("    还有 notCleared 强引用 => 仍可达，不会被回收（GC Roots 可达性分析）");
        System.out.println("    教训：静态集合/缓存只增不减 = 制造强引用泄漏，最终 Java heap space OOM\n");
    }

    /* =============== 2. 弱引用 =============== */
    private static void weakRefDemo() throws Exception {
        System.out.println("[2] 弱引用 WeakReference：下一次 GC 后必然被回收（无论内存是否充足）");
        byte[] payload = new byte[PAYLOAD_SIZE];
        ReferenceQueue<byte[]> queue = new ReferenceQueue<>();
        WeakReference<byte[]> weak = new WeakReference<>(payload, queue);
        System.out.println("    gc 前 weak.get() != null : " + (weak.get() != null));
        payload = null;                                // 移除强引用
        boolean cleared = gcUntilCleared(weak, 20);
        System.out.println("    移除强引用并 System.gc() 后 weak.get() == null : " + (weak.get() == null)
                + "（尝试 gc " + (cleared ? "成功" : "未生效") + "）");
        Reference<? extends byte[]> enqueued = queue.poll();
        System.out.println("    ReferenceQueue 收到回收通知: " + (enqueued == weak) + "\n");
    }

    /* =============== 3. 软引用 =============== */
    private static void softRefDemo() throws Exception {
        System.out.println("[3] 软引用 SoftReference：内存充足时活得好好的；内存紧张（GC 后仍不足）才回收");
        byte[] payload = new byte[PAYLOAD_SIZE];
        SoftReference<byte[]> soft = new SoftReference<>(payload);
        System.gc();
        System.out.println("    内存充足时 gc() 后 soft.get() != null : " + (soft.get() != null));
        payload = null;
        // 用小堆运行时，继续制造分配压力，软引用会在 OOM 前被回收；
        // 大堆运行时可能一直活着 —— 这正是软引用"内存敏感缓存"的语义。
        int rounds = 0;
        while (soft.get() != null && rounds < 200) {
            // 每轮制造 2MB 垃圾，逐步逼近内存不足，让 GC 决定清理软引用
            byte[] garbage = new byte[PAYLOAD_SIZE];
            garbage[0] = 1;
            rounds++;
        }
        System.gc();
        System.out.println("    制造分配压力 " + rounds + " 轮后 soft.get() == null : " + (soft.get() == null)
                + "（小堆 -Xmx64m 下通常为 true；大堆下可能仍为 false —— 均属正常现象）");
        System.out.println("    软引用被回收的时机由 JVM 的空闲内存策略决定，这正是它适合做缓存的原因\n");
    }

    /* =============== 4. 虚引用 =============== */
    private static void phantomRefDemo() throws Exception {
        System.out.println("[4] 虚引用 PhantomReference：get() 恒为 null，只用于\"回收事件\"通知");
        byte[] payload = new byte[PAYLOAD_SIZE];
        ReferenceQueue<byte[]> queue = new ReferenceQueue<>();
        PhantomReference<byte[]> phantom = new PhantomReference<>(payload, queue);
        System.out.println("    phantom.get() = " + phantom.get() + "（永远为 null，连对象活着时也是 null）");
        payload = null;
        boolean enqueued = false;
        for (int i = 0; i < 20 && !enqueued; i++) {
            System.gc();
            Reference<?> ref = queue.poll();
            if (ref == phantom) {
                enqueued = true;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        System.out.println("    移除强引用 + gc() 后，虚引用进入 ReferenceQueue : " + enqueued);
        System.out.println("    堆外内存回收范式：持有 DirectByteBuffer 的 PhantomReference，");
        System.out.println("    入队后在专门线程里 free 掉堆外内存 —— Netty/Netty DirectMemory 与 JDK Cleaner 同理\n");
    }

    /** 反复尝试 System.gc() 直到引用被清（gc() 只是"建议"，循环增强健壮性）。 */
    static boolean gcUntilCleared(WeakReference<?> ref, int maxTries) throws InterruptedException {
        for (int i = 0; i < maxTries; i++) {
            if (ref.get() == null) {
                return true;
            }
            // 制造一点垃圾，促使年轻代 GC 真实发生
            for (int j = 0; j < 16; j++) {
                byte[] junk = new byte[64 * 1024];
                junk[0] = (byte) j;
            }
            System.gc();
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return ref.get() == null;
    }
}
