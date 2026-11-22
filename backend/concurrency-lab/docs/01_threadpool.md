# 01 线程池 ThreadPoolExecutor 实验

> 源码：`src/main/java/com/campusflow/lab/concurrent/threadpool/ThreadPoolLab.java`
> 测试：`src/test/java/com/campusflow/lab/concurrent/threadpool/ThreadPoolLabTest.java`

## 一、实验目标

1. 掌握 `ThreadPoolExecutor` 七大构造参数的含义与取值思路；
2. 观察任务提交后的流转顺序：**核心线程 → 有界队列 → 扩容到 max → 拒绝策略**（注意是"先入队、后扩容"）；
3. 对比四种拒绝策略（Abort / CallerRuns / Discard / DiscardOldest）的真实行为差异；
4. 学会自定义 `ThreadFactory`（命名线程，便于排查问题）；
5. 掌握优雅关闭组合拳：`shutdown()` + `awaitTermination()`，以及强杀 `shutdownNow()`；
6. 演示运行期动态调参 `setCorePoolSize` 对队列积压的立即消化效果。

## 二、完整源码要点

七参数构造（核心演示代码）：

```java
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        2,                                   // 1.corePoolSize 核心线程数
        4,                                   // 2.maximumPoolSize 最大线程数
        10, TimeUnit.SECONDS,                // 3.4.keepAliveTime+unit 非核心线程空闲存活时间
        new ArrayBlockingQueue<>(2),         // 5.workQueue 有界阻塞队列
        factory,                             // 6.threadFactory 线程工厂
        new ThreadPoolExecutor.AbortPolicy() // 7.handler 拒绝策略
);

// 自定义 ThreadFactory：统一命名 + 打印创建时机
ThreadFactory factory = r -> {
    Thread t = new Thread(r, "demo-worker-" + seq.getAndIncrement());
    t.setDaemon(true);
    return t;
};
```

任务流转验证：core=2、queue=2、max=4 时连续提交 8 个 400ms 任务，逐个打印
`poolSize` / `queue.size()`，可以直观看到每个任务被"执行/入队/扩容/拒绝"的去向。

优雅关闭：

```java
pool.shutdown();                    // 不再收新任务，已提交任务执行完
pool.awaitTermination(3, TimeUnit.SECONDS);  // 限时等待退出
List<Runnable> notRun = pool.shutdownNow();  // 强杀：中断在跑任务 + 返回未执行任务
```

动态调参：

```java
pool.setCorePoolSize(3);   // 扩容：立即创建新工作线程消化队列积压
pool.setMaximumPoolSize(2);
```

## 三、运行命令

```bash
# 1) 设置 JDK17（本机已解压到 tools/jdk17）
export JAVA_HOME="D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"

# 2) 在 CampusFlow 根目录编译
mvn -pl backend/concurrency-lab compile

# 3) 运行 main 实验
java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.threadpool.ThreadPoolLab

# 4) 运行测试
mvn -pl backend/concurrency-lab test
```

## 四、预期现象

- 提交 8 个任务：任务 0/1 由 2 个核心线程立即执行；任务 2/3 进入队列；任务 4/5 触发扩容（创建
  demo-worker-3/4）；任务 6/7 被 AbortPolicy 拒绝。最终执行 6 个、拒绝 2 个、峰值线程数 4；
- CallerRunsPolicy 场景中，被拒绝的任务打印的执行线程名是 `main`；
- DiscardOldestPolicy 场景中，执行的任务 id 是 `{0, 2}`，任务 1（队首最老）被丢弃；
- `shutdown()` 后再提交抛 `RejectedExecutionException`；`shutdownNow()` 令长任务在 sleep 中抛
  `InterruptedException`，并返回 1 个未执行任务；
- 动态扩容后 6 个任务总耗时约 700ms（单线程串行约 1200ms）。

## 五、原理分析

- **执行流转**（`execute` 源码三步）：
  1. `workerCount < corePoolSize` → `addWorker(command, true)` 新建核心线程；
  2. 否则 `workQueue.offer(command)` 尝试入队；
  3. 队列满且 `workerCount < maximumPoolSize` → `addWorker(command, false)` 建非核心线程；
     再失败 → `reject(command)` 调用 `RejectedExecutionHandler`。
  所以是 **先入队、后扩容**——想"忙时优先扩容"要用 SynchronousQueue（如 newCachedThreadPool）。
- **拒绝策略**都实现 `RejectedExecutionHandler.rejectedExecution(Runnable, ThreadPoolExecutor)`：
  Abort 抛异常（调用方感知）；CallerRuns 由提交线程兜底执行（天然背压限流，但会阻塞提交方）；
  Discard 静默丢弃新任务（丢"最新"）；DiscardOldest 先 `queue.poll()` 丢队首再重试 offer（丢"最老"）。
- **优雅关闭**：`shutdown()` 将状态置为 SHUTDOWN，空闲worker退出、队列任务继续执行完；
  `awaitTermination` 阻塞等待 TERMINATED；`shutdownNow()` 置 STOP，对所有 worker
  `interrupt()`，返回 `drainQueue()` 的未执行任务。标准范式：
  `shutdown() → awaitTermination(N) → 超时则 shutdownNow() → 再 awaitTermination`。
- **动态调参**：`setCorePoolSize` 若调大，会立即 `addWorker` 补足线程去消费队列积压（本实验
  717ms vs 1200ms 即为证据）；若调小，多余的空闲线程会在闲置超时后被回收。
- 线程池本身**不缓存任务结果**，`execute()` 丢弃异常会被 UncaughtExceptionHandler 吞掉，
  生产建议用 `submit()` 拿 Future 或包装异常日志。

## 六、实际输出示例（真实运行，JDK 17.0.21.1 / Windows，节选）

```text
==================== 1. 七参数构造与任务流转（core=2, max=4, ArrayBlockingQueue(2), AbortPolicy) ====================
提交 8 个任务（每个 400ms），观察：核心2 -> 队列2 -> 扩容2 -> 拒绝2
[threadFactory] 创建线程: demo-worker-1
[demo-worker-1] 执行任务 0（400ms）
[main] 提交任务 0 成功 -> poolSize=1, 队列=0
[threadFactory] 创建线程: demo-worker-2
[main] 提交任务 1 成功 -> poolSize=2, 队列=0
[main] 提交任务 2 成功 -> poolSize=2, 队列=1
[main] 提交任务 3 成功 -> poolSize=2, 队列=2
[threadFactory] 创建线程: demo-worker-3
[main] 提交任务 4 成功 -> poolSize=3, 队列=2
[threadFactory] 创建线程: demo-worker-4
[main] 提交任务 5 成功 -> poolSize=4, 队列=2
[main] 提交任务 6 被拒绝（AbortPolicy 抛出 RejectedExecutionException）
[main] 提交任务 7 被拒绝（AbortPolicy 抛出 RejectedExecutionException）
结果：执行 6 个 / 拒绝 2 个 / 峰值线程数 = 4（largestPoolSize）

---- CallerRunsPolicy（调用者线程执行） ----
[main] 任务 2 执行于 main
实际执行的任务 id: [0, 1, 2]，执行线程: [main, pool-2-thread-1, pool-2-thread-1]

---- DiscardOldestPolicy（丢弃队首最老任务） ----
实际执行的任务 id: [0, 2]，执行线程: [pool-4-thread-1, pool-4-thread-1]

[pool-6-thread-1] 长任务在 sleep 中收到中断 -> 立即收尾退出（响应中断）
shutdownNow() 返回未执行任务数 = 1

调整前: poolSize=1, active=1, 队列积压=5
setCorePoolSize(3) 后: poolSize=3, active=3
全部任务完成耗时 717 ms（若始终单线程串行约需 1200ms，扩容后应明显缩短）
```

## 七、常见面试追问

**1. 线程池七个参数分别是什么？工作队列选有界还是无界？**
corePoolSize（常驻核心线程）、maximumPoolSize（上限）、keepAliveTime+unit（非核心线程空闲存活时间）、
workQueue（等待队列）、threadFactory（线程命名/守护/优先级/异常处理）、handler（拒绝策略）。
生产必须用**有界队列**：无界队列（如 `Executors.newFixedThreadPool` 的 LinkedBlockingQueue）在
消费速度低于生产速度时无限堆积导致 OOM；同时必须指定拒绝策略与线程命名，方便限流与排障。

**2. 提交任务后，线程池的处理顺序是什么？为什么"先入队、后扩容"？**
core 未满建核心线程 → core 满入队 → 队列满且未到 max 建非核心线程 → 达 max 且队列满才拒绝。
这是 `execute()` 的固定逻辑：入队（offer）成功就不再建线程，只有入队失败才扩容。想让新任务优先
用新线程需要换 SynchronousQueue 或自己实现 execute 逻辑。

**3. 四种拒绝策略如何选型？**
AbortPolicy（默认，快速失败让调用方感知过载，适合可重试场景）；CallerRunsPolicy（不丢任务、天然限流，
但会拖慢提交线程，Web 容器提交线程被拖慢会拖垮入口，慎用于在线服务）；DiscardPolicy（无声丢任务，
几乎不可用，出问题无法感知）；DiscardOldest（保新弃旧，适合"最新行情"类场景）。生产常见做法：
自定义 handler 落库/发 MQ 延迟处理，或 Abort + 上层重试。

**4. shutdown 和 shutdownNow 的区别？如何保证应用停机时不丢任务？**
shutdown 只拒新任务，已提交任务（含队列中）全部执行完才 TERMINATED；shutdownNow 中断所有 worker
并返回未执行任务列表（注意：任务要能响应中断才有意义）。标准范式：
`shutdown()` → `awaitTermination(若干秒)` → 未结束则 `shutdownNow()` → 再 `awaitTermination`；
配合应用优雅停机钩子（Spring `SmartLifecycle.stop` / `@PreDestroy`）。

**5. 核心线程数怎么估算？**
CPU 密集型 ≈ N_cpu（或 N_cpu+1，防页缺失抖动）；IO 密集型 ≈ N_cpu * (1 + 等待时间/计算时间)，
或用 `N_cpu * U * (1 + W/C)`（U 目标 CPU 利用率）。经验值先行 + 压测校准；池参数要配合队列长度、
拒绝策略一起评估，而不是只调线程数。

**6. 线程池异常处理怎么做？线程池会泄漏线程吗？**
`execute()` 提交的任务抛异常会直接打印堆栈并结束该 worker（随后重建新 worker）；`submit()` 提交的
异常被封装进 Future，不 `get()` 就完全看不到——常见"任务静默失败"。做法：任务体内 try-catch 记日志、
或包装 Runnable、或统一 `afterExecute` 钩子。线程泄漏的常见原因是自定义 ThreadFactory 没 setDaemon
且任务死循环/长时间阻塞，或 shutdown 永远不被调用。

**7. 为什么不建议用 Executors 快捷方法？**
`newFixedThreadPool/newSingleThreadExecutor` 用无界 LinkedBlockingQueue（OOM 风险）；
`newCachedThreadPool` 允许 Integer.MAX_VALUE 个线程（线程爆炸）；`newScheduledThreadPool` 同样
无界延迟队列。阿里规约要求手动 new ThreadPoolExecutor 并显式给出全部七个参数。

## 八、测试说明

`ThreadPoolLabTest` 共 5 个用例（全部 `@Timeout(30)` 保护）：
核心流转（执行 6/拒绝 2/峰值 4）、CallerRuns 在 main 执行、Discard 静默丢弃、优雅关闭语义、
动态扩容消化队列（断言使用线程数 >= 2）。
