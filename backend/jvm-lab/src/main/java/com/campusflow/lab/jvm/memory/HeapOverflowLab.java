package com.campusflow.lab.jvm.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 实验：堆内存溢出（java.lang.OutOfMemoryError: Java heap space）复现与分析。
 *
 * <p><b>【重要】必须使用小堆运行，否则填充时间过长且占用宿主机大量内存：</b>
 * <pre>
 *   java -Xmx64m -cp target/classes com.campusflow.lab.jvm.memory.HeapOverflowLab
 * </pre>
 *
 * <p>实验思路：用一个 {@code List<byte[]>} 强引用持续持有 1MB 的 byte[]，
 * 这些对象始终"可达"，Young GC / Full GC 均无法回收它们；
 * 当堆（Eden + Survivor + Old，或 G1 下的所有 Region）都无法再容纳新的分配请求时，
 * JVM 抛出 {@code OutOfMemoryError: Java heap space}。
 *
 * <p>main 中 catch 该错误后打印分析要点（分析要点见 {@link #printAnalysis(FillResult)}）。
 */
public class HeapOverflowLab {

    /** 每次分配的块大小：1MB。 */
    public static final int CHUNK_SIZE = 1024 * 1024;

    public static void main(String[] args) {
        System.out.println("== HeapOverflowLab：堆溢出复现 ==");
        printHeapSummary();
        System.out.println("提示：本实验请用小堆运行 -> java -Xmx64m -cp target/classes "
                + HeapOverflowLab.class.getName());
        System.out.println();

        // 不设预算上限：一路填到 OOM（配合 -Xmx64m 几秒钟内即可触发）。
        FillResult result = fillUntilOom(Long.MAX_VALUE);
        printAnalysis(result);
    }

    /**
     * 向列表持续填充 1MB 块直到触发并捕获 OutOfMemoryError，或达到预算上限（字节）。
     * 返回填充统计。OOM 在本方法内部被 catch，不会向外传播。
     *
     * @param budgetBytes 预算上限（分配总字节数达到该值即停止）；传 {@link Long#MAX_VALUE} 表示不设上限
     */
    public static FillResult fillUntilOom(long budgetBytes) {
        List<byte[]> holder = new ArrayList<>();
        long total = 0;
        boolean oom = false;
        String oomDetail = null;
        try {
            while (total < budgetBytes) {
                byte[] chunk = new byte[CHUNK_SIZE];
                holder.add(chunk);   // 持强引用 => 永远可达 => 永远无法被 GC
                total += chunk.length;
            }
        } catch (OutOfMemoryError e) {
            // 注意：这里 catch 的是 Error 不是 Exception。
            // 血泪教训（实测发现）：此时堆 100% 满，catch 里若先调用
            // e.getClass().getName()/拼接字符串 等"看似无害"的代码，会立刻再抛一次
            // OutOfMemoryError 并冲出 catch 块！所以第一件事必须是释放强引用。
            oom = true;
        } finally {
            holder.clear();          // 先释放引用，堆立刻松动，JVM 恢复正常
        }
        if (oom) {
            try {
                // 释放引用后再构造描述（用常量避免依赖 e.getMessage() 的额外分配）
                oomDetail = "java.lang.OutOfMemoryError: Java heap space";
            } catch (OutOfMemoryError e2) {
                oomDetail = "OutOfMemoryError（堆仍紧张，跳过详细描述）";
            }
        }
        return new FillResult(oom, total, oom ? (int) (total / CHUNK_SIZE) : -1, oomDetail);
    }

    /** 填充结果统计。 */
    public static final class FillResult {
        /** 是否真的捕获到 OutOfMemoryError。 */
        public final boolean oomCaught;
        /** 成功分配并持有的总字节数。 */
        public final long totalBytes;
        /** 持有的 1MB 块数（未触发 OOM 时为 -1）。 */
        public final int chunks;
        /** OOM 的详细信息（如 "java.lang.OutOfMemoryError: Java heap space"）。 */
        public final String oomDetail;

        public FillResult(boolean oomCaught, long totalBytes, int chunks, String oomDetail) {
            this.oomCaught = oomCaught;
            this.totalBytes = totalBytes;
            this.chunks = chunks;
            this.oomDetail = oomDetail;
        }
    }

    /** 打印 OOM 分析要点。 */
    public static void printAnalysis(FillResult result) {
        System.out.println("---- OOM 分析要点 ----");
        if (result.oomCaught) {
            System.out.println("捕获异常        : " + result.oomDetail);
            System.out.println("触发前已持有    : " + result.chunks + " x 1MB ≈ "
                    + result.totalBytes / 1024 / 1024 + "MB（这些全是\"活对象\"，GC 无法回收）");
        } else {
            System.out.println("未触发 OOM（达到预算上限 " + result.totalBytes / 1024 / 1024
                    + "MB 后正常退出）。请用 -Xmx64m 重跑。");
        }
        printHeapSummary();
        System.out.println();
        System.out.println("1) OOM 含义：发生 GC 后（full GC / concurrent GC）堆仍然放不下本次分配，");
        System.out.println("   且这次分配失败是\"永久性\"的（不是分配太快 GC 来不及），即堆里全是\"活对象\"。");
        System.out.println("2) 常见根因排查顺序：");
        System.out.println("   a. 堆太小？看 -Xmx 与 maxMemory 是否符合预期；");
        System.out.println("   b. 内存泄漏？对比多份 heap dump（jmap / jcmd GC.heap_dump），");
        System.out.println("      用 MAT 的 Leak Suspects / Dominator Tree 找持续增长的集合与 GC Root 链；");
        System.out.println("   c. 缓存无上限（如本例 List）？加容量上限、过期策略或改用弱/软引用；");
        System.out.println("   d. 一次加载过多数据（大查询、大文件）？分页/流式处理。");
        System.out.println("3) 建议始终保留现场：-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/path/to/dumps");
        System.out.println("4) 若日志出现 \"OutOfMemoryError: GC overhead limit exceeded\"，");
        System.out.println("   表示 JVM 花了 >98% 时间做 GC 却只回收了 <2% 堆，同样是堆活对象过多的信号。");
    }

    /** 打印当前堆摘要信息。 */
    public static void printHeapSummary() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory(), total = rt.totalMemory(), free = rt.freeMemory();
        System.out.printf("Runtime: maxMemory=%dMB totalMemory=%dMB freeMemory=%dMB used=%dMB%n",
                max / 1048576, total / 1048576, free / 1048576, (total - free) / 1048576);
    }
}
