# Day 23 · JUC 实验周上：线程池 / synchronized / volatile / CAS

> 阶段七（23-24 JUC）第 1 天。主教材换成 `backend/concurrency-lab`（纯 JDK 实验模块，
> 10 个主题每个都有 main 实验 + JUnit 测试 + 中文讲义 docs/01-10）。
> 今天的代码产出：跑通 4 个 lab 并记录真实输出。

## 一、今天学习什么

### Lab 1 线程池（threadpool.ThreadPoolLab + docs/01_threadpool.md）
- 七参数全解：corePoolSize/maximumPoolSize/keepAliveTime/workQueue/threadFactory/
  handler（拒绝策略）；
- **执行顺序陷阱**：核心满 → 先入队 → 队列满才扩到 max → 满则拒绝（不是先扩线程）；
- 四种拒绝策略对照项目：CallerRuns（notifyExecutor/compensationExecutor，削峰不丢数据）、
  Abort（exportExecutor，任务堆积 OOM 风险直接拒绝）；
- 优雅关闭 + allowCoreThreadTimeOut。

### Lab 2 synchronized（sync.SynchronizedLab + docs/02_sync.md）
- 对象锁/类锁、可重入、锁失效场景（静态字段锁实例对象/锁引用可变对象/锁字符串常量）；
- 锁升级：无锁→偏向→轻量级（CAS 自旋）→重量级（monitor）。

### Lab 3 volatile（visibility.VolatileLab + docs/03_visibility.md）
- 可见性死循环复现（无 volatile 的 flag 不退出）；
- count++ 非原子（读改写三步）——volatile 不保证原子性；
- happens-before 的发布语义（安全发布对象）。

### Lab 4 CAS/ABA（cas.CasAbaLab + docs/04_cas.md）
- CAS 自旋原理（Unsafe compareAndSwap）；
- ABA 复现与版本戳（AtomicStampedReference 思路）；
- LongAdder 分段累加 vs AtomicLong 高竞争吞吐差异。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/concurrency-lab/docs/01-04`（4 篇讲义） | 主教材：原理+实验+真实输出 |
| `backend/concurrency-lab/src/main/java/com/campusflow/lab/concurrent/threadpool/ThreadPoolLab.java` | 实验本体 |
| `backend/concurrency-lab/src/main/java/com/campusflow/lab/concurrent/sync/SynchronizedLab.java` | 同上 |
| `backend/concurrency-lab/src/main/java/com/campusflow/lab/concurrent/visibility/VolatileLab.java` | 同上 |
| `backend/concurrency-lab/src/main/java/com/campusflow/lab/concurrent/cas/CasAbaLab.java` | 同上 |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/config/ThreadPoolConfig.java` | 理论落地点：三池选型注释 |

## 三、运行哪些命令

```bash
# 编译 + 全量测试（38 个用例均带 @Timeout 保护）
export JAVA_HOME="你的 JDK17"
mvn -pl backend/concurrency-lab compile
mvn -pl backend/concurrency-lab test

# 逐个跑 main 实验（观察真实输出）
java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.threadpool.ThreadPoolLab
java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.visibility.VolatileLab
java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.cas.CasAbaLab
```

## 四、完成什么实验

1. 四个 lab 各跑一遍 main，把"实际输出示例"截图/抄录（讲义里的输出均为真实记录，
   你的输出应趋势一致）；
2. **线程池陷阱复现**：core=2, max=4, queue=2 提交 8 个任务——数一数同时运行线程数
   （答案不是 4！先 2 核心 + 2 队列，第 5-6 个任务才扩线程，第 7-8 个被拒绝）；
3. **LongAdder 对比**：8 线程 × 100 万次自增，分别用 AtomicLong 与 LongAdder 计时，
   记录倍数差（高竞争下差距明显）。

## 五、面试自测（口头回答，答案要点）

1. **线程池参数怎么定？**
   （先定性：IO 密集 vs CPU 密集；项目实践：notifyExecutor 4/8/200 CallerRuns（IO 密集），
   exportExecutor 2/4/50 Abort（任务少而重防 OOM）；压测回归定参，不背 N+1 公式。）
2. **synchronized 锁的是什么？**
   （对象头 Mark Word；方法=实例/类对象；字符串常量池锁的风险（全局共享可能串锁）；
   可重入靠计数器。）
3. **volatile 和 synchronized 的区别？**
   （可见性+有序性 vs 原子性+互斥；volatile 写即刷主存、读失效缓存行；
   适用：一写多读的 flag（如关闭标记）；count++ 必须用 CAS/锁。）
4. **CAS 有什么问题？**
   （ABA——版本戳解决；自旋空耗——限制重试或用锁；只能保证单变量——
   AtomicReference 组合状态；LongAdder 分段降竞争。）
5. **为什么你们项目拒绝策略不统一？**
   （按业务损失函数选：通知/补偿丢不起→CallerRuns 反压；导出任务堆积会 OOM→Abort
   快速失败——拒绝策略是业务决策不是技术偏好。）

## 六、自我检查标准

- [ ] 4 个 lab 全部跑通且测试全绿；
- [ ] 线程池"先入队后扩容"实验亲手复现；
- [ ] 四篇 lab 讲义各写 3 行结论笔记；
- [ ] 能把 ThreadPoolConfig 的三个池与四种拒绝策略一一对应。

## 附一、知识点详解

**1. 线程池"先入队后扩容"的完整推演（必考）**
core=2/max=4/queue=2 提交 8 个任务：
T1/T2 占核心 → T3/T4 入队（队列满）→ T5/T6 创建非核心线程 → T7/T8 触发拒绝策略。
**为什么这么设计**：核心线程是"长期员工"，队列是缓冲，扩容是应急——
先缓冲后应急避免线程抖动。这也解释了"用无界队列（Executors.newFixedThreadPool）
时 max 参数永远无效"的现象。

**2. CallerRuns 的隐藏收益（为什么 notifyExecutor 选它）**
拒绝时由提交线程（MQ 消费线程）自己执行任务——**消费线程被占用 → 上游拉取减速 →
天然背压**。对比 Abort（exportExecutor）：导出任务可拒绝（用户重试），
通知任务不可丢（CallerRuns 反压）。拒绝策略 = 数据价值 × 可重试性 的函数。

**3. synchronized 锁升级的触发条件（实验对照）**
无锁 → 偏向锁（单线程重复获取）→ 轻量级（交替竞争，CAS 自旋）→ 重量级
（多线程激烈竞争，monitor/OS 互斥量）。JDK15 后偏向锁默认禁用（维护成本>
收益）——实验里验证的是"交替 vs 激烈"两种竞争形态的表现差异。

**4. volatile 死循环的根因**
`while(!flag)` 编译后读的是工作内存缓存副本（JIT 优化提升到寄存器）；
volatile 强制每次从主存读。**这是 JMM 可见性问题最直观的复现**——
背"可见性"三个字不如亲手跑一次不退出的循环。

## 附二、实验输出对照表

| Lab | 预期现象 | 我的实测 |
|---|---|---|
| ThreadPoolLab | 拒绝时机在 T7/T8（非 T3/T4） | |
| SynchronizedLab | 交替竞争无升级/激烈竞争升级 | |
| VolatileLab | 无 volatile 死循环；加后退出 | |
| CasAbaLab | LongAdder 比 AtomicLong 快 ___ 倍 | |

---

## 附三、面试加练（3 题）

**1. 线程池要监控哪些指标？**
要点：活跃线程数/队列深度（积压预警）/拒绝次数（CallerRuns 生效次数）/
任务耗时分布——ThreadPoolExecutor 的 getActiveCount/getQueue().size() 直接可用，
配 actuator 自定义 metrics。

**2. 线程池参数能运行时改吗？**
要点：core/max 可以（setCorePoolSize），队列长度不行（ArrayBlockingQueue 定容）——
要动态队列需自实现或换 LinkedBlockingQueue+手动上限。动态调参 + 配置中心
是演进方向（concurrency-lab 01 讲义有说明）。

**3. JDK21 虚拟线程会不会取代线程池？**
要点：虚拟线程适合"阻塞型 IO 海量任务"（免池化），CPU 密集与需要资源隔离
的场景仍要池；本项目 notify/export 池仍合理，但 MQ 消费这类阻塞场景
是虚拟线程的潜在收益区。

**今日金句**：线程池不是配置题，是"资源隔离与背压"的设计题。

---

## 明日预告

Day 24 JUC 下半场：AQS/Semaphore/CHM/ThreadLocal/计数器竞态。
