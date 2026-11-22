package com.campusflow.lab.jvm.gc;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 实验：制造对象分配与存活/死亡循环，观察 GC 统计并学习 JDK17 统一日志（-Xlog:gc*）。
 *
 * <p>建议带 GC 日志运行：
 * <pre>
 *   java -Xmx256m -Xlog:gc*,gc+heap=debug,gc+metaspace:file=gc.log:time,uptime,level,tags \
 *        -cp target/classes com.campusflow.lab.jvm.gc.GcLogLab
 * </pre>
 *
 * <p>程序本身做的事：
 * <ol>
 *   <li>循环分配 1KB 小对象：70% 立即变垃圾（朝生夕死，模拟大多数业务对象）；</li>
 *   <li>30% 进入一个轮转的存活列表，转一圈后被置空（模拟中等寿命对象跨过 Young GC）；</li>
 *   <li>记录运行前后 {@link GarbageCollectorMXBean} 的采集次数/耗时增量；</li>
 *   <li>打印 -Xlog:gc* 的用法说明与日志字段解读。</li>
 * </ol>
 */
public class GcLogLab {

    static final int OBJECT_SIZE = 1024;          // 1KB
    static final int ALLOC_PER_ROUND = 20_000;    // 每轮 2 万个 1KB ≈ 20MB
    static final int ROUNDS = 30;                 // 30 轮 ≈ 600MB 分配压力
    static final int SURVIVOR_SLOTS = 500;        // 轮转存活列表容量

    public static void main(String[] args) {
        System.out.println("== GcLogLab：分配/存活/死亡循环 + JDK17 统一 GC 日志 ==");
        System.out.println("推荐命令（另开终端观察 gc.log）：");
        System.out.println("  java -Xmx256m -Xlog:gc*:file=gc.log:time,uptime,level,tags \\");
        System.out.println("       -cp target/classes " + GcLogLab.class.getName());
        System.out.println();

        Stats stats = runWorkload(ROUNDS);
        stats.print();

        printUnifiedLoggingGuide();
    }

    /** GC 统计快照。 */
    public static final class Stats {
        public final long allocatedApproxBytes;
        public final long youngCollectionsBefore;
        public final long youngCollectionsAfter;
        public final long youngTimeBeforeMs;
        public final long youngTimeAfterMs;
        public final long fullOrOldCountBefore;
        public final long fullOrOldCountAfter;
        public final long wallMillis;

        Stats(long allocatedApproxBytes, long ycb, long yca, long ytb, long yta,
              long fcb, long fca, long wallMillis) {
            this.allocatedApproxBytes = allocatedApproxBytes;
            this.youngCollectionsBefore = ycb;
            this.youngCollectionsAfter = yca;
            this.youngTimeBeforeMs = ytb;
            this.youngTimeAfterMs = yta;
            this.fullOrOldCountBefore = fcb;
            this.fullOrOldCountAfter = fca;
            this.wallMillis = wallMillis;
        }

        void print() {
            System.out.println("---- 运行统计 ----");
            System.out.println("近似分配总量 : " + allocatedApproxBytes / 1048576 + "MB");
            System.out.println("Young GC 次数: " + (youngCollectionsAfter - youngCollectionsBefore)
                    + " (累计 " + youngCollectionsAfter + ")");
            System.out.println("Young GC 耗时: " + (youngTimeAfterMs - youngTimeBeforeMs) + "ms (累计 " + youngTimeAfterMs + "ms)");
            System.out.println("Old/Full GC 次数: " + (fullOrOldCountAfter - fullOrOldCountBefore));
            System.out.println("墙钟耗时     : " + wallMillis + "ms");
            System.out.println();
            System.out.println("小对象朝生夕死 => Young GC 频繁但单次极快；存活列表跨 Young GC 被复制/晋升 => Old 区缓慢增长。");
            System.out.println("精确的每次 GC 细节请看 gc.log（见下方说明）。");
        }
    }

    /** 执行分配负载并返回统计（供 main 与 JUnit 复用）。 */
    public static Stats runWorkload(int rounds) {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long youngBefore = 0, youngTimeBefore = 0, oldBefore = 0;
        for (GarbageCollectorMXBean b : gcBeans) {
            String n = b.getName();
            long cnt = b.getCollectionCount(), t = b.getCollectionTime();
            // 不同收集器名称不同：G1 = "G1 Young Generation"/"G1 Old Generation"；
            // ZGC = "ZGC Cycler*"; Serial = "Copy"/"MarkSweepCompact"; Par = "PS MarkSweep" 等
            if (n != null && (n.toLowerCase().contains("young") || n.toLowerCase().contains("scavenge") || n.equalsIgnoreCase("Copy") || n.toLowerCase().contains("ps scavenger"))) {
                youngBefore += cnt;
                youngTimeBefore += t;
            } else {
                oldBefore += cnt;
            }
        }

        Object[] survivors = new Object[SURVIVOR_SLOTS];
        long allocated = 0;
        long t0 = System.currentTimeMillis();
        for (int r = 0; r < rounds; r++) {
            for (int i = 0; i < ALLOC_PER_ROUND; i++) {
                byte[] obj = new byte[OBJECT_SIZE];
                allocated += obj.length;
                if (i % 3 == 0) {
                    // ~1/3 进入轮转存活列表：先跨过一次 Young GC（复制到 Survivor），转一圈再死亡
                    survivors[i % SURVIVOR_SLOTS] = obj;
                }
                // 其余 obj 立刻不可达 => Minor GC 时直接回收（理想状态，理想的是它们根本活不到晋升）
            }
            if (r % 10 == 9) {
                // 定期清空存活列表，制造一轮大死亡，避免老年代无限增长
                java.util.Arrays.fill(survivors, null);
            }
        }
        long wall = System.currentTimeMillis() - t0;

        long youngAfter = 0, youngTimeAfter = 0, oldAfter = 0;
        for (GarbageCollectorMXBean b : gcBeans) {
            String n = b.getName();
            long cnt = b.getCollectionCount(), t = b.getCollectionTime();
            if (n != null && (n.toLowerCase().contains("young") || n.toLowerCase().contains("scavenge") || n.equalsIgnoreCase("Copy") || n.toLowerCase().contains("ps scavenger"))) {
                youngAfter += cnt;
                youngTimeAfter += t;
            } else {
                oldAfter += cnt;
            }
        }
        return new Stats(allocated, youngBefore, youngAfter, youngTimeBefore, youngTimeAfter,
                oldBefore, oldAfter, wall);
    }

    /** 打印 JDK17 统一日志(-Xlog) 用法说明。 */
    public static void printUnifiedLoggingGuide() {
        System.out.println("---- JDK17 统一日志 -Xlog 用法速查 ----");
        System.out.println("语法: -Xlog:<tag-set>[+tag...][*[...]]=<level>[:<output>[:<decorators>[:options]]]");
        System.out.println("示例:");
        System.out.println("  -Xlog:gc                           // 只看 GC 基本行，输出到 stdout");
        System.out.println("  -Xlog:gc*                          // 所有带 gc 标签的日志（最常用）");
        System.out.println("  -Xlog:gc*:file=gc.log              // 写文件");
        System.out.println("  -Xlog:gc+heap=debug                // 堆变化明细");
        System.out.println("  -Xlog:gc+humongous=debug           // G1 大对象（humongous）分配明细");
        System.out.println("  -Xlog:gc+metaspace=trace           // 元空间分配/卸载");
        System.out.println("  -Xlog:disable                       // 关闭全部");
        System.out.println("完整模板（推荐）:");
        System.out.println("  -Xlog:gc*,gc+heap=debug,gc+humongous=debug:file=gc-%t.log:time,uptime,level,tags:filecount=5,filesize=20m");
        System.out.println("decorator 字段: time(ISO时间) uptime(JVM启动秒) level 级别 tags 标签 pid tid hostname");
        System.out.println("JDK8 是 -XX:+PrintGCDetails / GC.log_tools(JDK9 前无统一日志)；JDK9+ 全面改为 -Xlog 统一框架。");
    }
}
