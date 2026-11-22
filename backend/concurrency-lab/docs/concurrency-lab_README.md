# concurrency-lab：JUC 并发实验模块

CampusFlow 的纯 JDK 并发实验模块（无 Spring、无第三方依赖，仅 lombok(provided) + junit-jupiter(test)）。
10 个主题，每个主题一个可运行的 main 实验类 + 一个 JUnit 5 测试类 + 一篇中文讲义。
**所有"实际输出示例"均为 JDK 17.0.21.1（Temurin，Windows）上的真实运行输出。**

## 环境要求

- JDK 17（本工作区已解压：`tools/jdk17/jdk-17.0.21.1+2`，Maven 编译需 JAVA_HOME 指向它）
- Maven 3.9.x（PATH 中已就绪）

## 快速开始

```bash
# 1) 指向 JDK17（Git Bash / MSYS）
export JAVA_HOME="D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"

# 2) 在 CampusFlow 根目录编译（仅本模块，不影响其他模块）
cd D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/CampusFlow
mvn -pl backend/concurrency-lab compile

# 3) 运行任一实验（main 方法直接演示现象并输出观察结论）
java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.threadpool.ThreadPoolLab

# 4) 运行全部测试（38 个用例，均带 @Timeout 保护）
mvn -pl backend/concurrency-lab test
```

## 实验索引

| # | 主题 | main 实验类 | 测试类 | 讲义 | 核心知识点 |
|---|------|-------------|--------|------|-----------|
| 01 | 线程池 | `threadpool.ThreadPoolLab` | `ThreadPoolLabTest` | [01_threadpool.md](01_threadpool.md) | 七参数、先入队后扩容、四种拒绝策略、优雅关闭、动态调参 |
| 02 | synchronized | `sync.SynchronizedLab` | `SynchronizedLabTest` | [02_sync.md](02_sync.md) | 对象锁/类锁、可重入、锁失效场景、锁升级 |
| 03 | volatile | `visibility.VolatileLab` | `VolatileLabTest` | [03_visibility.md](03_visibility.md) | 可见性死循环、count++ 非原子、happens-before 发布 |
| 04 | CAS/ABA | `cas.CasAbaLab` | `CasAbaLabTest` | [04_cas.md](04_cas.md) | CAS 自旋、ABA 复现与版本戳、LongAdder vs AtomicLong |
| 05 | ReentrantLock | `lock.ReentrantLockLab` | `ReentrantLockLabTest` | [05_lock.md](05_lock.md) | 可重入、公平/非公平、tryLock、lockInterruptibly、Condition |
| 06 | AQS | `aqs.AqsLab` | `AqsLabTest` | [06_aqs.md](06_aqs.md) | 自定义不可重入独占锁、CLH 排队、模板方法 |
| 07 | Semaphore | `semaphore.SemaphoreLab` | `SemaphoreLabTest` | [07_semaphore.md](07_semaphore.md) | 场馆限流 N 人、acquire/release、tryAcquire 超时 |
| 08 | ThreadLocal | `threadlocal.ThreadLocalLab` | `ThreadLocalLabTest` | [08_threadlocal.md](08_threadlocal.md) | 隔离性、线程池串号污染、finally remove 范式 |
| 09 | ConcurrentHashMap | `concurrenthashmap.ConcurrentHashMapLab` | `ConcurrentHashMapLabTest` | [09_concurrenthashmap.md](09_concurrenthashmap.md) | 并发 put 安全、computeIfAbsent 原子、HashMap 丢数据对比 |
| 10 | 竞态条件 | `counter.RaceConditionLab` | `RaceConditionLabTest` | [10_counter.md](10_counter.md) | count++ 丢更新复现、三种修复与耗时对比 |

## 模块结构

```text
backend/concurrency-lab/
├── pom.xml                          # 仅 lombok(provided) + junit-jupiter(test)
├── docs/                            # 本目录：10 篇讲义 + 本 README
└── src
    ├── main/java/com/campusflow/lab/concurrent/
    │   ├── common/LabKit.java       # 日志/受控 sleep+join/命名线程工厂
    │   ├── threadpool/  sync/  visibility/  cas/  lock/
    │   ├── aqs/  semaphore/  threadlocal/  concurrenthashmap/  counter/
    └── test/java/com/campusflow/lab/concurrent/   # 10 个 *Test（JUnit5 + @Timeout）
```

## 验证记录（真实执行）

- 编译：`mvn -pl backend/concurrency-lab compile` → `Compiling 11 source files with javac [debug release 17]`，BUILD SUCCESS；
- 测试：`mvn -pl backend/concurrency-lab test` → **Tests run: 38, Failures: 0, Errors: 0, Skipped: 0**，BUILD SUCCESS；
- main 实验：10 个实验类全部用 JDK17 `java -cp target/classes` 实际运行成功（exit=0），
  真实输出已粘贴进各篇讲义"实际输出示例"一节。

## 讲义阅读建议

按 10 → 02 → 03 → 01 → 04 → 05 → 06 → 07 → 08 → 09 的顺序阅读更适合初学者：
先建立"竞态/可见性"问题直觉（10、02、03），再学工程化工具（线程池、原子类、显式锁），
最后进入 AQS 原理与并发容器。每篇讲义均含 ≥5 个面试追问与参考答案。
