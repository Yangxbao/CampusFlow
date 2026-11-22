package com.campusflow.lab.jvm.memory;

/**
 * 实验：栈内存溢出（StackOverflowError）复现与 -Xss 影响。
 *
 * <p>运行方式（普通参数即可）：
 * <pre>
 *   java -cp target/classes com.campusflow.lab.jvm.memory.StackOverflowLab
 *   java -Xss256k -cp target/classes com.campusflow.lab.jvm.memory.StackOverflowLab
 * </pre>
 *
 * <p>实验内容：
 * <ol>
 *   <li>不同栈大小下递归，统计最大递归深度；</li>
 *   <li>用 {@link Thread#Thread(ThreadGroup, Runnable, String, long)} 的 stackSize 参数
 *       在同一进程内对比不同栈大小（等价于"给线程单独设置 -Xss"）下的深度差异；</li>
 *   <li>catch StackOverflowError 后打印分析要点。</li>
 * </ol>
 *
 * <p>结论预期：栈越小可递归深度越小，二者近似成正比；-Xss 只影响单线程栈上限，
 * 不影响堆；线程数 × -Xss 构成栈区总虚拟内存开销。
 */
public class StackOverflowLab {

    /** 防御性硬上限：正常情况下远早于该值就栈溢出了。 */
    private static final long HARD_LIMIT = 1_000_000_000L;

    public static void main(String[] args) throws Exception {
        System.out.println("== StackOverflowLab：栈溢出复现与 -Xss 影响 ==");
        System.out.println("当前 JVM 输入参数: " + java.lang.management.ManagementFactory
                .getRuntimeMXBean().getInputArguments());
        System.out.println();

        long d1 = measureMaxDepth();
        printResult("main 线程（栈大小由 -Xss 决定）", d1);

        long d2 = measureInNewThread(0);
        printResult("子线程 stackSize=0（JVM 默认）", d2);

        long d3 = measureInNewThread(256 * 1024);
        printResult("子线程 stackSize=256KB", d3);

        long d4 = measureInNewThread(4L * 1024 * 1024);
        printResult("子线程 stackSize=4MB", d4);

        System.out.println();
        System.out.println("---- 分析要点 ----");
        System.out.println("1) 每次方法调用压入一个栈帧（局部变量表/操作数栈/动态链接/返回地址），");
        System.out.println("   帧总大小超过线程栈上限即抛 StackOverflowError；");
        System.out.println("2) 栈越小/帧越大（局部变量多），可递归深度越小；深度与栈大小近似成正比；");
        System.out.println("3) 常见根因：递归无出口、递归出口写错、toString()/JSON 序列化循环引用、代理相互调用；");
        System.out.println("4) -Xss 调大只是\"缓解\"，根治要修复递归逻辑或改迭代/显式栈；");
        System.out.println("5) 栈线程私有、不参与 GC；进程可创建线程数 ≈ 可用虚拟内存 / (栈大小 + 内核开销)，");
        System.out.println("   故 -Xss 过大反而更早遇到 unable to create new native thread。");
    }

    private static void printResult(String label, long depth) {
        System.out.printf("%-32s 最大递归深度 = %d 层%n", label, depth);
    }

    /**
     * 在当前线程递归直到 StackOverflowError，返回近似的最大递归深度。
     * 该方法内部 catch 了 StackOverflowError，保证异常不外泄。
     */
    public static long measureMaxDepth() {
        long[] holder = new long[1];
        try {
            recurse(0, holder);
        } catch (StackOverflowError e) {
            // 期望行为：递归最深处抛出并逐层展开到此处
            StackTraceElement[] st = e.getStackTrace();
            System.out.printf("  捕获 StackOverflowError：栈快照含 %d 帧，顶部帧: %s%n",
                    st.length, st.length > 0 ? st[0] : "(空：JIT 内联/省略后可能无栈帧)");
        }
        return holder[0];
    }

    /**
     * 在指定栈大小的新线程中递归到栈溢出，返回该线程的近似最大递归深度。
     *
     * @param stackSizeBytes Thread 构造器的 stackSize 参数（字节）；0 表示 JVM 默认（即 -Xss）
     */
    public static long measureInNewThread(long stackSizeBytes) throws Exception {
        final long[] holder = new long[1];
        Runnable task = () -> {
            try {
                recurse(0, holder);
            } catch (StackOverflowError e) {
                StackTraceElement[] st = e.getStackTrace();
                System.out.printf("  [stackSize=%dKB] StackOverflowError，快照 %d 帧，首帧: %s%n",
                        stackSizeBytes / 1024, st.length,
                        st.length > 0 ? st[0] : "(空)");
            }
        };
        Thread t = new Thread(null, task, "SO-Lab-" + stackSizeBytes + "B", stackSizeBytes);
        t.start();
        t.join(120_000);
        return holder[0];
    }

    /** 每帧记录当前深度后继续递归；holder[0] 即最后成功进入的帧深。 */
    private static void recurse(long depth, long[] holder) {
        if (depth >= HARD_LIMIT) {
            return; // 防御：正常到不了这里
        }
        holder[0] = depth;
        recurse(depth + 1, holder);
    }
}
