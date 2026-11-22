package com.campusflow.lab.concurrent.threadlocal;

import com.campusflow.lab.concurrent.common.LabKit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * ThreadLocal 实验：线程隔离演示、线程池中不 remove 导致的数据串线程（污染）复现、
 * try/finally 正确清理范式。
 *
 * 运行：
 *   mvn -pl backend/concurrency-lab compile
 *   java -cp backend/concurrency-lab/target/classes com.campusflow.lab.concurrent.threadlocal.ThreadLocalLab
 */
public class ThreadLocalLab {

    /** 模拟"当前登录用户"上下文（类似 Spring 的 RequestContextHolder / SecurityContext） */
    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    public static void main(String[] args) throws Exception {
        List<String> isolated = runIsolation();
        System.out.println("隔离结果: " + isolated);
        String leaked = runPollution(false);
        System.out.println("不清理时, 任务2 读到的用户 = " + leaked);
        String clean = runPollution(true);
        System.out.println("正确清理后, 任务2 读到的用户 = " + clean);

        System.out.println();
        System.out.println("========== 观察结论 ==========");
        System.out.println("1) ThreadLocal 隔离性：每个线程访问的是自己 Thread 对象内部的 ThreadLocalMap，互不可见。");
        System.out.println("2) 线程池中线程是复用的：上一个任务 set 的值不 remove，下一个任务（另一个\"用户\"）");
        System.out.println("   就会读到上一个任务的残留数据 —— 数据串线程。");
        System.out.println("3) 正确范式：try { ... } finally { ThreadLocal.remove(); }；或使用阿里 TransmittableThreadLocal");
        System.out.println("   等方案时同样要清理。Spring 请求结束时的 RequestContextFilter.reset 等就是这个套路。");
        System.out.println("4) 内存结构：Thread -> ThreadLocalMap -> Entry(key 为 ThreadLocal 的弱引用, value 强引用)。");
        System.out.println("   key 可被 GC 回收变为 null（防止泄漏 key），但 value 仍被强引用 —— 线程不死则 value 泄漏，");
        System.out.println("   所以 set 之后必须 remove（尤其线程池场景）。");
    }

    /** 1. 线程隔离：3 个线程各自 set/get，互不影响。返回各线程读到的值 */
    static List<String> runIsolation() throws InterruptedException {
        LabKit.section("1. ThreadLocal 线程隔离：3 个线程各自 set/get");
        String[] names = {"A", "B", "C"};
        List<String> result = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread[] threads = new Thread[names.length];
        for (int i = 0; i < names.length; i++) {
            final String user = "用户-" + names[i];
            threads[i] = new Thread(() -> {
                CURRENT_USER.set(user);
                LabKit.sleep(100); // 确保三个线程的 set/get 交错发生
                String seen = CURRENT_USER.get();
                LabKit.log("get() = %s（读到的都是自己 set 的值）", seen);
                CURRENT_USER.remove();
                result.add(seen);
            }, "isolated-" + names[i]);
            threads[i].start();
        }
        for (Thread t : threads) {
            LabKit.join(t, 5000);
        }
        return result;
    }

    /**
     * 2/3. 线程池复用导致的污染与清理。
     * removeAfter=true 时任务1 用 try/finally 清理。
     * 返回任务2 在 set 之前读到的值（污染时应为 user-A）。
     */
    static String runPollution(boolean removeAfter) throws Exception {
        LabKit.section(removeAfter
                ? "3. 正确范式：任务1 finally 中 remove()，任务2 读不到残留"
                : "2. 污染复现：单线程池复用线程，任务1 set 后【忘记 remove】");
        ExecutorService pool = Executors.newFixedThreadPool(1, LabKit.namedFactory("biz-worker", true));
        try {
            // 任务1：模拟拦截器 set 当前登录用户，处理业务
            Future<String> f1 = pool.submit(() -> {
                CURRENT_USER.set("user-A");
                if (removeAfter) {
                    try {
                        LabKit.log("任务1: 登录用户=user-A，处理业务...（finally 中会 remove）");
                        return CURRENT_USER.get();
                    } finally {
                        CURRENT_USER.remove(); // 关键清理
                    }
                }
                LabKit.log("任务1: 登录用户=user-A，处理业务...（忘记 remove，线程带着 user-A 归池）");
                return CURRENT_USER.get();
            });
            f1.get(3, TimeUnit.SECONDS);

            // 任务2：新请求复用了同一个线程，一进来先"读当前用户"（模拟鉴权/日志读取）
            Future<String> f2 = pool.submit(() -> {
                String leaked = CURRENT_USER.get(); // 还没 set，先读
                LabKit.log("任务2: 业务还没 set，直接读到当前用户 = %s%s", leaked,
                        leaked == null ? "" : "  <-- 串号！这是任务1 残留的数据");
                return leaked;
            });
            return f2.get(3, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
