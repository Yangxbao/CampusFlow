package com.campusflow.lab.jvm.oom;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 实验：触发小堆 OOM 后，用 HotSpotDiagnosticMXBean 打印堆参数，
 * 并演示 -XX:+HeapDumpOnOutOfMemoryError 的用法。
 *
 * <p>【重要】必须小堆运行，否则触发 OOM 太慢：
 * <pre>
 *   java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./heapdump.hprof \
 *        -cp target/classes com.campusflow.lab.jvm.oom.OomDiagnosisLab
 * </pre>
 *
 * <p>实验内容：
 * <ol>
 *   <li>{@link #printHeapOptions()}：用 HotSpotDiagnosticMXBean 读取
 *       MaxHeapSize / InitialHeapSize / HeapDumpOnOutOfMemoryError / HeapDumpPath 等参数；</li>
 *   <li>演示运行时动态修改可写(manageable)标志：setVMOption("HeapDumpOnOutOfMemoryError", "true")；</li>
 *   <li>小堆下真实触发 OutOfMemoryError 并 catch，打印诊断要点与 heap dump 分析步骤。</li>
 * </ol>
 */
public class OomDiagnosisLab {

    public static void main(String[] args) {
        System.out.println("== OomDiagnosisLab：OOM 现场诊断 ==");
        System.out.println();
        printHeapOptions();

        System.out.println();
        System.out.println("[2] 运行时动态设置 HeapDumpOnOutOfMemoryError（该标志是 manageable，可动态修改）：");
        try {
            setHeapDumpOnOom(true);
            System.out.println("    setVMOption 成功，回读 HeapDumpOnOutOfMemoryError = "
                    + getVMOption("HeapDumpOnOutOfMemoryError"));
            System.out.println("    等价启动参数: -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<dir>");
        } catch (RuntimeException e) {
            System.out.println("    动态设置失败: " + e.getMessage());
            System.out.println("    请改用启动参数 -XX:+HeapDumpOnOutOfMemoryError");
        }

        System.out.println();
        System.out.println("[3] 触发真实 OOM（预算上限 2GB，-Xmx64m 下几秒内即触发）：");
        OomTriggerResult r = triggerOom(2L * 1024 * 1024 * 1024);
        printOomAnalysis(r);
    }

    /* ==================== 1. HotSpotDiagnosticMXBean 读取参数 ==================== */

    /** 关注的 JVM 参数清单。 */
    public static final String[] WATCHED_FLAGS = {
            "MaxHeapSize", "InitialHeapSize", "NewSize", "MaxNewSize",
            "UseG1GC", "UseZGC", "UseParallelGC", "UseSerialGC",
            "HeapDumpOnOutOfMemoryError", "HeapDumpPath",
            "MaxMetaspaceSize", "CompressedClassSpaceSize"
    };

    /**
     * 打印堆相关参数（key=value 行格式），返回读取结果 Map（供 JUnit 断言）。
     * HotSpotDiagnosticMXBean 位于 com.sun.management 包，JDK 标准模块 jdk.management 内置。
     */
    public static java.util.Map<String, String> printHeapOptions() {
        com.sun.management.HotSpotDiagnosticMXBean hs =
                ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        System.out.println("[1] HotSpotDiagnosticMXBean 读取当前 JVM 参数：");
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String flag : WATCHED_FLAGS) {
            String value;
            try {
                value = hs.getVMOption(flag).getValue();
            } catch (RuntimeException e) {
                value = "(unsupported)";
            }
            result.put(flag, value);
            System.out.println("    " + flag + " = " + value);
        }
        System.out.println("    注：MaxHeapSize 显示的是字节；-Xmx64m 实际会向上取整到页对齐值。");
        System.out.println("    判断当前收集器也可以看 UseG1GC/UseZGC 等哪个为 true（JDK17 无 CMS）。");
        return result;
    }

    /** 运行时动态设置 HeapDumpOnOutOfMemoryError。 */
    public static void setHeapDumpOnOom(boolean on) {
        com.sun.management.HotSpotDiagnosticMXBean hs =
                ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        hs.setVMOption("HeapDumpOnOutOfMemoryError", String.valueOf(on));
    }

    /** 读取单个 VM 选项。 */
    public static String getVMOption(String flag) {
        com.sun.management.HotSpotDiagnosticMXBean hs =
                ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        return hs.getVMOption(flag).getValue();
    }

    /* ==================== 2. 触发 OOM 并诊断 ==================== */

    /** OOM 触发结果。 */
    public static final class OomTriggerResult {
        public final boolean oomCaught;
        public final String oomDetail;
        public final long bytesHeld;

        public OomTriggerResult(boolean oomCaught, String oomDetail, long bytesHeld) {
            this.oomCaught = oomCaught;
            this.oomDetail = oomDetail;
            this.bytesHeld = bytesHeld;
        }
    }

    /**
     * 填充 1MB 块直到 OOM（或达到预算上限），OOM 在内部 catch。
     * 建议 -Xmx64m 运行，使 OOM 在毫秒级触发。
     */
    public static OomTriggerResult triggerOom(long budgetBytes) {
        List<byte[]> holder = new ArrayList<>();
        long total = 0;
        boolean oom = false;
        String detail = null;
        try {
            while (total < budgetBytes) {
                byte[] chunk = new byte[1024 * 1024];
                holder.add(chunk);
                total += chunk.length;
            }
        } catch (OutOfMemoryError e) {
            // 教训：堆 100% 满时 catch 内禁止任何分配（包括拼字符串），
            // 必须先释放强引用，否则会再次 OOM 冲出 catch
            oom = true;
        } finally {
            holder.clear();
        }
        if (oom) {
            detail = "java.lang.OutOfMemoryError: Java heap space";
        }
        return new OomTriggerResult(oom, detail, total);
    }

    /** 打印 OOM 诊断要点与 heap dump 分析步骤。 */
    public static void printOomAnalysis(OomTriggerResult r) {
        System.out.println("    结果: " + (r.oomCaught ? r.oomDetail : "预算内未触发 OOM（请用 -Xmx64m 重跑）")
                + "，触发前持有 " + r.bytesHeld / 1048576 + "MB");
        System.out.println();
        System.out.println("---- OOM 现场诊断四步法 ----");
        System.out.println("1) 保留现场（最重要，进程可以崩但不能丢 dump）：");
        System.out.println("   -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/dumps");
        System.out.println("   HeapDumpPath 是目录则自动命名为 java_pid<pid>.hprof，是文件则直接写该文件；");
        System.out.println("   该标志为 manageable，可用 HotSpotDiagnosticMXBean 或 jcmd <pid> VM.set_flag 运行时开启；");
        System.out.println("2) 没 dump 时补救采集：jmap -dump:live,format=b,file=heap.hprof <pid>");
        System.out.println("   （:live 会先 Full GC，只统计活对象；大堆采集慢，抓前先确认服务可承受）；");
        System.out.println("   或 jcmd <pid> GC.heap_dump /path/heap.hprof（推荐，JDK8+ 通用）；");
        System.out.println("3) 离线分析：Eclipse MAT（Leak Suspects/Dominator Tree/Path to GC Roots）");
        System.out.println("   或 VisualVM、jhat（已废弃）看支配树与引用链，定位哪个集合/缓存无上限；");
        System.out.println("4) 结合 -Xlog:gc* 判断 OOM 类型：");
        System.out.println("   Java heap space=堆真满；GC overhead limit exceeded=98% 时间 GC 只回收 2%；");
        System.out.println("   Metaspace=类元数据泄漏；unable to create new native thread=线程数/内存超限（非堆问题）；");
        System.out.println("   Direct buffer memory=堆外直接内存超限（-XX:MaxDirectMemorySize）。");
    }
}
