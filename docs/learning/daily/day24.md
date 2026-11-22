# Day 24 · JUC 实验周下：AQS / ReentrantLock / Semaphore / CHM / ThreadLocal

> 阶段七第 2 天（收网）。今天补齐剩下 6 个 lab，并把 JUC 与项目代码连线。

## 一、今天学习什么

### Lab 5 ReentrantLock（lock.ReentrantLockLab + docs/05_lock.md）
- 可重入、公平/非公平（公平=排队先来后到，吞吐更低）、tryLock 超时、lockInterruptibly、
  Condition 精准唤醒；
- 与 synchronized 选择：需要超时/可中断/多条件队列才用 Lock，否则 synchronized 更简单。

### Lab 6 AQS（aqs.AqsLab + docs/06_aqs.md）
- state 变量 + CLH 双向队列 + 模板方法（tryAcquire/tryRelease）；
- 实验自己写一个不可重入独占锁——AQS 的 best 学习法；
- ReentrantLock/Semaphore/CountDownLatch 都是 AQS 的不同 state 语义。

### Lab 7 Semaphore（semaphore.SemaphoreLab + docs/07_semaphore.md）
- "场馆限流 N 人"模型：acquire/release、tryAcquire(timeout)；
- 项目映射：DB 连接池/Hikari maximum-pool-size=20 就是天然 Semaphore；
  消费端线程池有界队列同理。

### Lab 8 ThreadLocal（threadlocal.ThreadLocalLab + docs/08_threadlocal.md）
- ThreadLocalMap 结构：key 弱引用 value 强引用 → key 回收 value 悬挂；
- 线程池必须 remove 的两个项目实例：MDC.remove（TraceIdFilter finally）、
  AuthContext.clear（JwtAuthInterceptor afterCompletion）。

### Lab 9 ConcurrentHashMap（concurrenthashmap.ConcurrentHashMapLab + docs/09_concurrenthashmap.md）
- JDK8 CAS+synchronized 锁单桶（不是分段锁 Segment）；size 用 CounterCell 分散计数；
- computeIfAbsent 的原子性；composite 操作仍需额外同步。

### Lab 10 计数器竞态（counter.RaceConditionLab + docs/10_counter.md）
- 多方案横评：synchronized/AtomicLong/LongAdder 的正确性与性能。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/concurrency-lab/docs/05-10`（6 篇讲义） | 主教材 |
| `backend/concurrency-lab/src/main/java/com/campusflow/lab/concurrent/aqs/AqsLab.java` | 自定义独占锁实现 |
| `backend/campusflow-server/src/main/java/com/campusflow/server/config/TraceIdFilter.java` | ThreadLocal 清理实例 1 |
| `backend/campusflow-server/src/main/java/com/campusflow/server/config/JwtAuthInterceptor.java` | ThreadLocal 清理实例 2 |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/config/ThreadPoolConfig.java` | Semaphore 思维落地点 |

## 三、运行哪些命令

```bash
java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.aqs.AqsLab
java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.semaphore.SemaphoreLab
java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.threadlocal.ThreadLocalLab
java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.concurrenthashmap.ConcurrentHashMapLab
java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.counter.RaceConditionLab
java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.lock.ReentrantLockLab

mvn -pl backend/concurrency-lab test    # 全部 38 用例应绿
```

## 四、完成什么实验

1. 6 个 lab 全跑通，重点记录 AqsLab（自定义锁的 state/queue 变化）与
   ThreadLocalLab（泄漏复现）的输出；
2. **CHM 原子性陷阱**：`map.get(k) != null 判断后 map.put(k,v)` 两步复合操作在并发下
   失效——用 computeIfAbsent 改写对比；
3. **ThreadLocal 泄漏复现**：线程池 + ThreadLocal 不 remove，观察连续提交任务时
   读到上一个任务残留值（若 lab 已含此演示则直接引用输出）。

## 五、面试自测（口头回答，答案要点）

1. **AQS 的核心是什么？**
   （volatile int state + CAS 改 state + CLH 等待队列 + 模板方法留给子类定义
   获取/释放语义；独占/共享两种模式；addWaiter/排队/阻塞/唤醒流程。）
2. **ThreadLocal 为什么会内存泄漏？你们项目怎么防？**
   （key 弱引用 value 强引用；线程池线程长寿则 value 悬挂；用后 remove——
   项目两处清理点（Filter/Interceptor）举出来。）
3. **ConcurrentHashMap 怎么保证并发安全？**
   （JDK8：Node 数组 + CAS 初始化/插入 + synchronized 锁桶头；读无锁（volatile）；
   size 用 baseCount+CounterCell；复合操作用 computeIfAbsent/putIfAbsent。）
4. **Semaphore 在你的项目里有对应物吗？**
   （HikariCP maximumPoolSize=20 本质是许可=20 的信号量；MQ 消费线程 5-20 有界；
   限流（RateLimiterService）也是许可发放思想——不同 API 同一模型。）
5. **synchronized 和 ReentrantLock 怎么选？**
   （默认 synchronized（JVM 层优化好、不会忘解锁）；需要 tryLock 超时/可中断/
   多 Condition/公平性才上 Lock——项目锁几乎都用 Redisson 分布式锁，本地锁场景
   只有实验。）

## 六、自我检查标准（阶段七收网）

- [ ] 10 个 lab 全部跑通，`mvn -pl backend/concurrency-lab test` 全绿；
- [ ] 10 篇 lab 讲义读完，每篇 3 行结论笔记；
- [ ] 六个"项目映射"说得出（线程池→ThreadPoolConfig、Semaphore→Hikari/消费线程、
      ThreadLocal→MDC/AuthContext、CAS→Lua/Redisson、AQS→Redisson 底层、CHM→缓存本地 map）；
- [ ] 讲义 14 的 Q31/Q32/Q33/Q34 四题回答达标；
- [ ] 错题本更新本阶段条目。

## 附一、知识点详解

**1. AQS 自定义锁的最小实现骨架（实验 6 的代码形态）**
```java
class SimpleLock extends AbstractQueuedSynchronizer {
    tryAcquire(int acquires) { 
        if (compareAndSetState(0,1)) { setExclusiveOwnerThread(t); return true; }
        return false;                                   // 失败入队
    }
    tryRelease(int releases) { 
        if (getState()==0) throw ...; 
        setState(0); setExclusiveOwnerThread(null); return true; }
}
```
state=0/1 表达"空闲/持有"，CLH 队列由 AQS 基类托管——**写一次 AQS 子类，
理解所有锁的共性**。

**2. ThreadLocalMap 的 Entry 结构与清理时机**
`Entry extends WeakReference<ThreadLocal>`：key 是弱引用；外部不再持有
ThreadLocal 对象时 key 被回收、value 悬挂。Map 的 get/set 会顺带清理过期槽
（探测式清理），但**线程池长存线程上不主动调用就是赌运气**——remove 是唯一可靠方式。

**3. CHM 的"复合操作"陷阱（与项目联动的提醒）**
`if (!map.containsKey(k)) map.put(k, v)` 两步之间无锁——并发下重复 put。
正确姿势 computeIfAbsent/putIfAbsent。本项目几乎不用本地 Map 共享状态
（多实例下状态全在 Redis/DB），这本身就是分布式对并发的更优解——
CHM 用于"单实例内的只读快照"。

**4. Semaphore 与有界线程池的同构**
两者都限制并发访问数：Semaphore 手动 acquire/release；线程池由队列长度隐式实现。
项目的三层限流体系（Lua 用户级限流 / RateLimiterService 接口级 /
线程池+队列资源级）覆盖了从用户到资源的全链路——Semaphore 思想无处不在。

## 附二、lab-项目映射背诵卡

```
ThreadPoolLab  → ThreadPoolConfig 三池
SynchronizedLab→ 预热的锁语义(本地视角)
VolatileLab    → flag 类场景(停止标记)
CasAbaLab      → Lua/Redisson 的 CAS 底层
AqsLab         → Redisson RLock 内部
SemaphoreLab   → Hikari 池/消费线程
ThreadLocalLab → MDC/AuthContext 清理
CHMLab         → 本地缓存(Caffeine 底层思想)
RaceConditionLab → Lua 要原子性的原因
ReentrantLockLab → tryLock/lockInterruptibly 语义
```

---

## 附三、面试加练（3 题）

**1. AQS 的共享模式（tryAcquireShared）在哪用？**
要点：Semaphore/CountDownLatch/读锁——state 表示剩余许可/计数；
tryAcquireShared 返回负数入队、非负传播唤醒。写 AqsLab 时的独占模式
是理解共享模式的地基。

**2. CountDownLatch 与 CyclicBarrier 区别？**
要点：前者一次性（await 等计数归零，适合"等多任务完成"）；后者可复用
（ parties 互等，适合"分批同步"）；本项目 awaitConfirmed 的轮询不用它们
（MQ 异步等待），IT 里 CountDownLatch 对齐竞态窗口真实用到（LuaOversellIT）。

**3. 偏向锁为什么在 JDK15 默认禁用？**
要点：撤销成本高（需要 safepoint）、现代应用竞争模式变化、维护收益低——
说明 JVM 特性也会退役，学习要跟版本（docs/02_gc.md 也有 CMS JDK14 移除先例）。

**今日金句**：JUC 的所有工具都是 AQS 的不同 state 语义——学会一个，看懂全家。

---

## 明日预告

Day 25 进入阶段八（JVM 实验周）：内存区与三类 OOM。
