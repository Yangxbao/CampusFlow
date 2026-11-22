# 08 ThreadLocal 实验：隔离性、线程池污染与正确清理

> 源码：`src/main/java/com/campusflow/lab/concurrent/threadlocal/ThreadLocalLab.java`
> 测试：`src/test/java/com/campusflow/lab/concurrent/threadlocal/ThreadLocalLabTest.java`

## 一、实验目标

1. 验证 ThreadLocal 的**线程隔离**：3 个线程 set/get 各自的值互不影响；
2. 复现**线程池数据串线程**：任务 set 后不 remove，下一个复用同线程的任务读到残留值（污染）；
3. 建立**正确清理范式**：`try { ... } finally { ThreadLocal.remove(); }`；
4. 理解 ThreadLocal 内存结构（Thread→ThreadLocalMap→Entry 弱引用 key）与内存泄漏机制。

## 二、完整源码要点

```java
private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

// 1. 隔离性：每个线程 set 自己的值
CURRENT_USER.set("用户-A");     // 写入的是当前线程自己的 ThreadLocalMap
String seen = CURRENT_USER.get(); // 读的也是自己线程的 map
CURRENT_USER.remove();

// 2. 污染复现：单线程池 -> 线程必然复用
ExecutorService pool = Executors.newFixedThreadPool(1, ...);
Future<String> f1 = pool.submit(() -> {
    CURRENT_USER.set("user-A");
    LabKit.log("任务1 处理业务（忘记 remove，线程带着 user-A 归池）");
    return CURRENT_USER.get();
});
Future<String> f2 = pool.submit(() -> {
    String leaked = CURRENT_USER.get();   // 还没 set 就先读（模拟鉴权拦截器）
    return leaked;                        // -> "user-A"（串号！）
});

// 3. 正确范式
pool.submit(() -> {
    CURRENT_USER.set("user-A");
    try {
        return doBiz();
    } finally {
        CURRENT_USER.remove();   // 关键清理，归还"干净"的线程
    }
});
```

## 三、运行命令

```bash
export JAVA_HOME="D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"
mvn -pl backend/concurrency-lab compile

java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.threadlocal.ThreadLocalLab

mvn -pl backend/concurrency-lab test
```

## 四、预期现象

- 隔离：三个线程各自读到 `用户-A/B/C`，互不串扰；
- 污染：任务 2（新请求）在尚未 set 的情况下读到了任务 1 的 `user-A` —— 真实业务中等于"把上一个
  用户的数据当成当前用户"，属于高危 bug；
- 清理：任务 1 用 finally remove 后，任务 2 读到 `null`。

## 五、原理分析

- **存储结构**：每个 `Thread` 对象内有一个 `ThreadLocalMap`（不是 ThreadLocal 里存线程，而是
  **线程里存 map**）。map 的 key 是 ThreadLocal 实例（**弱引用**），value 是 set 的值（**强引用**）。
  `set/get` 都以 `Thread.currentThread()` 定位 map，天然隔离。
- **为什么不会串**：值放在各线程自己的 map 里，跨线程根本没有共享变量，隔离性由结构保证，
  不需要任何锁。
- **内存泄漏链**：Entry key 是弱引用——外部不再引用该 ThreadLocal 时 key 可被 GC 置为 null；
  但 value 仍被 Entry 强引用，Entry 被 map 强引用，map 被 Thread 强引用。**线程不死（线程池！）
  value 就一直不可达地活着**。ThreadLocalMap 在 get/set 时会顺带清理 stale entry（探测式/启发式
  清理），但不能保证及时清空——所以**规范是用完 remove**。
- **线程池污染**：线程复用意味着 ThreadLocalMap 跟着线程"穿越"多个逻辑请求。本实验的
  "任务2 读到 user-A"在业务上就是**越权访问**。Spring 的 `RequestContextHolder`/`SecurityContext`
  都在请求结束时由 Filter/Interceptor 显式 reset（本质就是 remove）。
- **InheritableThreadLocal / TTL**：`InheritableThreadLocal` 仅在**创建子线程时**拷贝父线程值；
  线程池中线程早已创建，不会传递新值——需要阿里 `TransmittableThreadLocal`（包装 Runnable 抓取
  快照）或手动传递上下文对象。异步场景用显式参数传递上下文永远是更可测的方案。

## 六、实际输出示例（真实运行，JDK 17.0.21.1 / Windows）

```text
==================== 1. ThreadLocal 线程隔离：3 个线程各自 set/get ====================
[isolated-B] get() = 用户-B（读到的都是自己 set 的值）
[isolated-A] get() = 用户-A（读到的都是自己 set 的值）
[isolated-C] get() = 用户-C（读到的都是自己 set 的值）
隔离结果: [用户-B, 用户-A, 用户-C]

==================== 2. 污染复现：单线程池复用线程，任务1 set 后【忘记 remove】 ====================
[biz-worker-1] 任务1: 登录用户=user-A，处理业务...（忘记 remove，线程带着 user-A 归池）
[biz-worker-1] 任务2: 业务还没 set，直接读到当前用户 = user-A  <-- 串号！这是任务1 残留的数据
不清理时, 任务2 读到的用户 = user-A

==================== 3. 正确范式：任务1 finally 中 remove()，任务2 读不到残留 ====================
[biz-worker-1] 任务1: 登录用户=user-A，处理业务...（finally 中会 remove）
[biz-worker-1] 任务2: 业务还没 set，直接读到当前用户 = null
正确清理后, 任务2 读到的用户 = null
```

## 七、常见面试追问

**1. ThreadLocal 的实现原理？为什么线程隔离？**
每个 Thread 持有自己的 `ThreadLocalMap`（`Thread.threadLocals` 字段），key 是 ThreadLocal 实例
（弱引用），value 是 set 的值。set/get/remove 都操作当前线程自己的 map，数据不跨线程共享，
隔离由存储结构天然保证。

**2. 为什么 key 用弱引用？会完全避免内存泄漏吗？**
key 弱引用使 ThreadLocal 实例本身可被回收（防止 map 强引用导致 ThreadLocal 泄漏）；但 value 仍被
Entry 强引用，线程不死则 value 无法回收。Map 的探测清理只在做 set/get 时顺带触发。所以弱引用
只是"半程防护"，**完整防泄漏必须显式 remove**。

**3. 线程池场景下 ThreadLocal 有什么坑？如何避免？**
两个坑：①数据串线程（上个任务 set 不 remove，下个任务读到残留，甚至越权）；②内存泄漏（线程
长期存活，value 常驻）。避免：try-finally remove；拦截器/AOP 统一清理；框架化封装（如 Spring
的 RequestContextFilter 模式）；或干脆显式传参。

**4. ThreadLocalMap 是怎么解决 hash 冲突的？为什么用这种结构？**
开放定址法（线性探测）而不是链表法——键数量少（一个线程里通常几个~几十个 ThreadLocal），
冲突概率低，探测法的连续内存对小规模更高效。清理 stale entry（key==null）也在探测时顺带做。

**5. InheritableThreadLocal 和 TransmittableThreadLocal 的区别？**
InheritableThreadLocal 只在**创建子线程那一刻**拷贝父线程的值；线程池线程是复用的，任务提交时
不会重新拷贝，所以传递失效或脏读。TransmittableThreadLocal 在任务提交时抓取快照、执行时回放、
完成后还原，配合 TtlRunnable/TtlCallable 或 agent 使用，专门解决线程池上下文传递。

**6. 用 ThreadLocal 存 SimpleDateFormat 安全吗？更好的做法？**
安全（经典用法：`private static final ThreadLocal<SimpleDateFormat>`）——SimpleDateFormat 非线程
安全，ThreadLocal 隔离各线程独立实例。JDK8+ 更推荐 `DateTimeFormatter`（不可变，天然线程安全，
可 static 共享），避免 ThreadLocal 的清理负担。

**7. ThreadLocal 一定需要 synchronized 吗？**
不需要。ThreadLocal 的隔离性来自"每线程一份 map"，没有任何跨线程共享变量；它解决的是"上下文
传递"问题而不是"共享状态竞争"问题。需要共享就同步，需要隔离就 ThreadLocal——两类问题别混用。

## 八、测试说明

`ThreadLocalLabTest` 共 3 个用例（`@Timeout(30)`）：3 线程隔离（值互不相同）、单线程池污染复现
（任务2 读到 "user-A"）、finally remove 后读到 null。
