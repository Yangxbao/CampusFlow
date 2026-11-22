package com.campusflow.lab.jvm.args;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实验：打印运行时内存各区域大小、当前 GC 名称与 JVM 参数清单。
 *
 * <p>运行方式（建议带几个参数，观察 getInputArguments 能否原样回显）：
 * <pre>
 *   java -Xms128m -Xmx512m -Xss512k -XX:+UseG1GC -XX:MaxMetaspaceSize=64m \
 *        -cp target/classes com.campusflow.lab.jvm.args.JvmArgsLab
 * </pre>
 *
 * <p>本实验是调优的"仪表盘"：改任何 JVM 参数后先跑它确认生效。
 */
public class JvmArgsLab {

    public static void main(String[] args) {
        System.out.println("== JvmArgsLab：JVM 运行时内存/GC/参数一览 ==");
        printReport(System.out);
    }

    /**
     * 收集运行时报告（供 main 与 JUnit 复用）。
     * 返回的 Map 里包含 runtime/heap/nonheap/pools/gc/args 各分区摘要。
     */
    public static Map<String, String> collectRuntimeReport(Appendable out) throws Exception {
        Map<String, String> report = new LinkedHashMap<>();

        // ---- 1. Runtime 三兄弟：maxMemory / totalMemory / freeMemory ----
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory(), total = rt.totalMemory(), free = rt.freeMemory();
        if (out instanceof java.io.PrintStream) {
            ((java.io.PrintStream) out).println("[1] Runtime（当前堆视图，单位 MB）:");
            ((java.io.PrintStream) out).printf("    maxMemory   = %dMB  (≈ -Xmx，已提交上限)%n", max / 1048576);
            ((java.io.PrintStream) out).printf("    totalMemory = %dMB  (≈ 当前已向 OS 提交的堆大小，≈ -Xms 起步随需增长)%n", total / 1048576);
            ((java.io.PrintStream) out).printf("    freeMemory  = %dMB  (totalMemory 里还没被分配的部分)%n", free / 1048576);
            ((java.io.PrintStream) out).printf("    used        = %dMB  (totalMemory - freeMemory)%n", (total - free) / 1048576);
        }
        report.put("maxMemoryMB", String.valueOf(max / 1048576));
        report.put("totalMemoryMB", String.valueOf(total / 1048576));
        report.put("freeMemoryMB", String.valueOf(free / 1048576));

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        if (out instanceof java.io.PrintStream) {
            ((java.io.PrintStream) out).println();
            ((java.io.PrintStream) out).println("[2] MemoryMXBean 堆/非堆用量:");
            ((java.io.PrintStream) out).println("    Heap:     " + usage(memory.getHeapMemoryUsage()));
            ((java.io.PrintStream) out).println("    NonHeap:  " + usage(memory.getNonHeapMemoryUsage()));
            ((java.io.PrintStream) out).println("    pending finalizable objects: "
                    + memory.getObjectPendingFinalizationCount());
        }
        report.put("heap", usage(memory.getHeapMemoryUsage()));
        report.put("nonheap", usage(memory.getNonHeapMemoryUsage()));

        // ---- 3. 内存池明细 ----
        if (out instanceof java.io.PrintStream) {
            ((java.io.PrintStream) out).println();
            ((java.io.PrintStream) out).println("[3] MemoryPoolMXBean 各内存池（G1 下常见: Eden/Survivor/Old/Metaspace/CodeCache）:");
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                String kind = pool.getType() == MemoryType.HEAP ? "HEAP    " : "NON-HEAP";
                ((java.io.PrintStream) out).printf("    [%s] %-30s %s 峰值:%s%n",
                        kind, pool.getName(), usage(pool.getUsage()), usage(pool.getPeakUsage()));
            }
        }

        // ---- 4. 当前 GC 名称 ----
        if (out instanceof java.io.PrintStream) {
            ((java.io.PrintStream) out).println();
            ((java.io.PrintStream) out).println("[4] GarbageCollectorMXBean（GC 名称可反推当前收集器）:");
        }
        StringBuilder gcNames = new StringBuilder();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gcNames.length() > 0) {
                gcNames.append(" | ");
            }
            gcNames.append(gc.getName());
            if (out instanceof java.io.PrintStream) {
                ((java.io.PrintStream) out).printf("    %-24s count=%d time=%dms  pools=%s%n",
                        gc.getName(), gc.getCollectionCount(), gc.getCollectionTime(),
                        gc.getMemoryPoolNames() != null ? String.join(",", gc.getMemoryPoolNames()) : "-");
            }
        }
        report.put("gcNames", gcNames.toString());

        // ---- 5. JVM 启动参数 ----
        java.util.List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        if (out instanceof java.io.PrintStream) {
            ((java.io.PrintStream) out).println();
            ((java.io.PrintStream) out).println("[5] ManagementFactory.getRuntimeMXBean().getInputArguments():");
            for (String a : inputArgs) {
                ((java.io.PrintStream) out).println("    " + a);
            }
            ((java.io.PrintStream) out).println();
            ((java.io.PrintStream) out).println("[6] 其他运行时信息:");
            ((java.io.PrintStream) out).printf("    java.version=%s vm.name=%s pid=%s uptime=%dms%n",
                    System.getProperty("java.version"),
                    ManagementFactory.getRuntimeMXBean().getVmName(),
                    ManagementFactory.getRuntimeMXBean().getName(),
                    ManagementFactory.getRuntimeMXBean().getUptime());
            ((java.io.PrintStream) out).println("    可用处理器: " + rt.availableProcessors());
        }
        report.put("inputArguments", String.join(" ", inputArgs));
        return report;
    }

    /** 打印完整报告的便捷入口。 */
    public static void printReport(java.io.PrintStream out) {
        try {
            collectRuntimeReport(out);
        } catch (Exception e) {
            e.printStackTrace(out);
        }
    }

    private static String usage(MemoryUsage u) {
        if (u == null) {
            return "n/a";
        }
        return String.format("init=%dMB used=%dMB committed=%dMB max=%dMB",
                u.getInit() / 1048576, u.getUsed() / 1048576,
                u.getCommitted() / 1048576, u.getMax() < 0 ? -1 : u.getMax() / 1048576);
    }
}
