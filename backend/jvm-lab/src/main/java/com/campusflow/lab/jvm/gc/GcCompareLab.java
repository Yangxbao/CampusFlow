package com.campusflow.lab.jvm.gc;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * 实验：同一负载下 G1 与 ZGC 的对比方案 + 程序内运行时近似测量（分配速率、GC 时间占比）。
 *
 * <p>程序无法在同一 JVM 内切换收集器（收集器在 JVM 启动时确定），因此对比方案是：
 * <b>同一负载程序跑两次</b>，分别用 G1 与 ZGC 启动，比较两次输出的
 * 分配速率、GC 总耗时占比、平均单次暂停。建议命令（脚本打印，照抄执行即可）：
 * <pre>
 *   # 第一遍：G1
 *   java -Xms256m -Xmx256m -XX:+UseG1GC -Xlog:gc*:file=g1.log:time,uptime,level,tags \
 *        -cp target/classes com.campusflow.lab.jvm.gc.GcCompareLab g1
 *   # 第二遍：ZGC
 *   java -Xms256m -Xmx256m -XX:+UseZGC -Xlog:gc*:file=zgc.log:time,uptime,level,tags \
 *        -cp target/classes com.campusflow.lab.jvm.gc.GcCompareLab zgc
 * </pre>
 *
 * <p>程序内的"暂停时间测量"用运行时近似：负载前后读
 * {@link GarbageCollectorMXBean#getCollectionTime()} 增量 / 墙钟时间 = GC 时间占比；
 * {@link com.sun.management.ThreadMXBean#getThreadAllocatedBytes(long)} 测本线程真实分配字节。
 */
public class GcCompareLab {

    static final int CHUNK = 256 * 1024;   // 256KB
    static final int CHUNKS_PER_ROUND = 400; // 每轮 100MB
    static final int ROUNDS = 8;             // 总分配 800MB（256m 堆下必然多次 GC）

    public static void main(String[] args) {
        String label = args.length > 0 ? args[0] : "unknown";
        System.out.println("== GcCompareLab：G1 vs ZGC 对比（当前运行标记: " + label + "）==");
        System.out.println("当前 JVM 输入参数: " + ManagementFactory.getRuntimeMXBean().getInputArguments());
        String collector = detectCollector();
        System.out.println("当前收集器      : " + collector);
        System.out.println();

        Stats stats = runWorkload();
        stats.print(label, collector);

        System.out.println();
        printComparePlan();
    }

    /** 一次负载运行的测量结果。 */
    public static final class Stats {
        public final long allocatedBytes;
        public final long gcTimeDeltaMs;
        public final long gcCountDelta;
        public final long wallMillis;
        public final long avgPauseApproxUs;
        public final double gcTimeRatio;
        public final long allocationRateMBps;

        Stats(long allocatedBytes, long gcTimeDeltaMs, long gcCountDelta, long wallMillis) {
            this.allocatedBytes = allocatedBytes;
            this.gcTimeDeltaMs = gcTimeDeltaMs;
            this.gcCountDelta = gcCountDelta;
            this.wallMillis = wallMillis;
            this.avgPauseApproxUs = gcCountDelta > 0 ? gcTimeDeltaMs * 1000 / gcCountDelta : 0;
            this.gcTimeRatio = wallMillis > 0 ? (double) gcTimeDeltaMs / wallMillis : 0;
            this.allocationRateMBps = wallMillis > 0 ? allocatedBytes / 1048576 / Math.max(1, wallMillis / 1000)
                    : allocatedBytes / 1048576;
        }

        void print(String label, String collector) {
            System.out.println("---- 负载测量结果（" + label + " / " + collector + "）----");
            System.out.println("真实分配字节     : " + allocatedBytes / 1048576 + "MB");
            System.out.println("分配速率(近似)   : " + allocationRateMBps + " MB/s");
            System.out.println("GC 次数增量      : " + gcCountDelta);
            System.out.println("GC 总耗时增量    : " + gcTimeDeltaMs + "ms");
            System.out.println("平均单次 GC 耗时 : " + avgPauseApproxUs + "us（暂停时间近似值）");
            System.out.printf("GC 时间占比      : %.2f%%%n", gcTimeRatio * 100);
            System.out.println("墙钟耗时         : " + wallMillis + "ms");
            System.out.println("（以上为 MXBean 统计口径的近似测量；精确逐次暂停请解析 -Xlog:gc* 日志中的 Pause 行）");
        }
    }

    /** 执行标准负载（供 main 与 JUnit 复用），返回测量结果。 */
    public static Stats runWorkload() {
        // 1) GC 统计基线
        long gcCountBefore = 0, gcTimeBefore = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCountBefore += b.getCollectionCount();
            gcTimeBefore += b.getCollectionTime();
        }
        // 2) 本线程分配字节基线（HotSpot 提供 com.sun.management.ThreadMXBean）
        ThreadMXBean tmxb = ManagementFactory.getThreadMXBean();
        boolean allocSupported = tmxb instanceof com.sun.management.ThreadMXBean
                && ((com.sun.management.ThreadMXBean) tmxb).isThreadAllocatedMemorySupported()
                && ((com.sun.management.ThreadMXBean) tmxb).isThreadAllocatedMemoryEnabled();
        long tid = Thread.currentThread().getId();
        long allocBefore = allocSupported
                ? ((com.sun.management.ThreadMXBean) tmxb).getThreadAllocatedBytes(tid) : 0;

        // 3) 负载：分配 800MB，一部分短暂持有后释放（制造跨 GC 存活的对象）
        List<byte[]> shortLived = new ArrayList<>(128);
        long t0 = System.currentTimeMillis();
        for (int r = 0; r < ROUNDS; r++) {
            for (int i = 0; i < CHUNKS_PER_ROUND; i++) {
                byte[] chunk = new byte[CHUNK];
                chunk[0] = (byte) i;          // 防止被"未初始化即丢弃"优化
                if (i % 64 == 0) {
                    shortLived.add(chunk);    // 少量对象活过下一次 GC
                } else {
                    shortLived.clear();       // 其余立刻死亡
                }
            }
        }
        long wall = System.currentTimeMillis() - t0;
        shortLived.clear();

        // 4) 统计增量
        long gcCountAfter = 0, gcTimeAfter = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCountAfter += b.getCollectionCount();
            gcTimeAfter += b.getCollectionTime();
        }
        long allocAfter = allocSupported
                ? ((com.sun.management.ThreadMXBean) tmxb).getThreadAllocatedBytes(tid) : 0;

        return new Stats(allocAfter - allocBefore, gcTimeAfter - gcTimeBefore,
                gcCountAfter - gcCountBefore, wall);
    }

    /** 根据收集器 MXBean 名称猜测当前收集器。 */
    public static String detectCollector() {
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            String n = b.getName();
            if (n == null) continue;
            if (n.contains("G1")) return "G1 (UseG1GC, JDK9+ 默认)";
            if (n.contains("ZGC")) return "ZGC (UseZGC, 毫秒级亚毫秒停顿)";
            if (n.contains("Shenandoah")) return "Shenandoah";
            if (n.contains("Serial") || n.equalsIgnoreCase("Copy") || n.equalsIgnoreCase("MarkSweepCompact"))
                return "SerialGC";
            if (n.contains("PS") || n.contains("Parallel")) return "ParallelGC (UseParallelGC)";
        }
        return "unknown";
    }

    /** 打印 G1 vs ZGC 对比方案与适用场景。 */
    public static void printComparePlan() {
        System.out.println("---- G1 vs ZGC 对比方案 ----");
        System.out.println("1) 分别执行：");
        System.out.println("   java -Xms256m -Xmx256m -XX:+UseG1GC -Xlog:gc*:file=g1.log:time,uptime,level,tags \\");
        System.out.println("        -cp target/classes com.campusflow.lab.jvm.gc.GcCompareLab g1");
        System.out.println("   java -Xms256m -Xmx256m -XX:+UseZGC -Xlog:gc*:file=zgc.log:time,uptime,level,tags \\");
        System.out.println("        -cp target/classes com.campusflow.lab.jvm.gc.GcCompareLab zgc");
        System.out.println("2) 对比三组数字：分配速率（吞吐基线）、GC 总耗时占比（吞吐损失）、平均/最大暂停（尾延迟）。");
        System.out.println("3) 解析日志中的 Pause 行：grep 'Pause' g1.log / zgc.log，取其 pause 时间列做分布统计。");
        System.out.println();
        System.out.println("G1：分代 + Region 化 + 并发标记 + 增量回收（Mixed GC），暂停目标 -XX:MaxGCPauseMillis=200；");
        System.out.println("    适合大堆（4GB+）、需要平衡吞吐与延迟的通用服务，JDK9+ 默认。");
        System.out.println("ZGC：着色指针 + 读屏障，并发整理（标记/转移/重定位几乎全程并发），");
        System.out.println("     停顿 <1ms 且不随堆变大（JDK15+ 生产可用），代价是吞吐略降与内存占用(转发表/着色指针)；");
        System.out.println("     适合大堆（数十 GB+）、对尾延迟敏感的服务：实时风控、网关、交易系统。");
        System.out.println("CMS：并发收集先驱（老年代并发标记清除），但内存碎片 + 浮动垃圾 + Concurrent Mode Failure，");
        System.out.println("     JDK9 废弃、JDK14 移除 —— JDK17 已无 CMS，只做了解即可。");
    }
}
