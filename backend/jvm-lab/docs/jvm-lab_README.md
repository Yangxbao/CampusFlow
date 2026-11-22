# jvm-lab · JVM 实验模块索引（README）

> 模块路径：`backend/jvm-lab`（独立 Maven 模块，仅依赖 lombok(provided) + junit-jupiter(test)，Java 17）
> 包名统一：`com.campusflow.lab.jvm.<主题>`，只用 JDK 标准库，无 Spring 依赖。

---

## 一、模块是什么

一套**可运行、可测试、可粘贴讲义**的 JVM 调优与诊断实验集：
10 个独立 main 实验 + 30 个 JUnit5 用例 + 8 个诊断脚本 + 7 篇中文讲义。
所有实验均已在 Temurin JDK 17.0.21.1+2（Windows x64）真实运行并采集输出，讲义中的输出均为真实记录。

## 二、实验总览

| # | 主题 | 类（`com.campusflow.lab.jvm.` 下） | 关键参数 | 讲义 |
|---|---|---|---|---|
| 1 | 堆溢出 | `memory.HeapOverflowLab` | `-Xmx64m` | [docs/01_memory.md](01_memory.md) |
| 2 | 栈溢出 | `memory.StackOverflowLab` | `-Xss256k`（可选） | [docs/01_memory.md](01_memory.md) |
| 3 | 元空间溢出 | `memory.MetaspaceLab` | `-XX:MaxMetaspaceSize=32m` | [docs/01_memory.md](01_memory.md) |
| 4 | GC 日志 | `gc.GcLogLab` | `-Xlog:gc*:file=gc.log` | [docs/02_gc.md](02_gc.md) |
| 5 | G1/ZGC 对比 | `gc.GcCompareLab` | `-XX:+UseG1GC` / `-XX:+UseZGC` | [docs/02_gc.md](02_gc.md) |
| 6 | 双亲委派 | `classloader.ClassLoaderLab` | 无 | [docs/03_classloader.md](03_classloader.md) |
| 7 | 四种引用 | `reference.ReferenceLab` | `-Xmx64m`（可选） | [docs/04_reference.md](04_reference.md) |
| 8 | 死锁检测 | `deadlock.DeadlockLab` | 无 | [docs/05_deadlock.md](05_deadlock.md) |
| 9 | OOM 诊断 | `oom.OomDiagnosisLab` | `-Xmx64m` | [docs/06_oom.md](06_oom.md) |
| 10 | 参数观测 | `args.JvmArgsLab` | 任意（建议带参运行） | [docs/07_args.md](07_args.md)（含生产参数模板与调优记录模板） |

## 三、快速开始

```bash
# 编译与测试（JDK17）
mvn -pl backend/jvm-lab compile
mvn -pl backend/jvm-lab test          # 30 个用例全部通过

# 运行实验（在 backend/jvm-lab 目录下）
java -Xmx64m   -cp target/classes com.campusflow.lab.jvm.memory.HeapOverflowLab
java -cp target/classes com.campusflow.lab.jvm.memory.StackOverflowLab
java -XX:MaxMetaspaceSize=32m -cp target/classes com.campusflow.lab.jvm.memory.MetaspaceLab
java -Xlog:gc*:file=gc.log:time,uptime,level,tags -cp target/classes com.campusflow.lab.jvm.gc.GcLogLab
java -cp target/classes com.campusflow.lab.jvm.classloader.ClassLoaderLab
java -cp target/classes com.campusflow.lab.jvm.deadlock.DeadlockLab      # 自动检测后 System.exit(0) 退出
```

Windows 下可直接使用脚本模板（见 scripts/ 目录内注释）：

```bat
scripts\run_with_gclog.bat com.campusflow.lab.jvm.gc.GcLogLab cp=target\classes xmx=256m
scripts\collect_thread_dump.bat <pid>
scripts\collect_heap_dump.bat <pid> .\diagnostics live
scripts\jcmd_diagnose.bat <pid> .\diagnostics
```

## 四、测试安全设计（不会挂死/不会 OOM 掉测试 JVM）

- 所有"危险"实验（SOE/Heap OOM/Metaspace）都在**子线程**触发并内部 catch，`@Timeout` 兜底；
- surefire 测试 JVM 限定 `-Xmx256m`（见模块 pom.xml），堆溢出实验秒级触发、即时释放；
- 死锁线程全部 daemon，测试不会挂死；`DeadlockLab` main 结束前 `System.exit(0)`；
- Metaspace 实验测试限载 300 个类并在 @AfterAll 清理；OOM 类实验不真正 OOM 整个测试 JVM。

## 五、诊断脚本清单（scripts/）

| 脚本（.bat / .sh） | 功能 |
|---|---|
| `collect_thread_dump.*` | jstack 连采 3 份线程转储（对比定位 BLOCKED/死锁） |
| `collect_heap_dump.*` | jmap -dump:live,format=b 采集 .hprof（含 :live 的 Full GC 代价提示） |
| `jcmd_diagnose.*` | jcmd 一次性采集 VM.uptime/VM.flags/Thread.print/GC.class_histogram/GC.heap_info（可选 heap_dump） |
| `run_with_gclog.*` | 带 `-Xlog:gc*:file=gc-%t.log` 运行任意 main 的模板脚本 |

## 六、讲义目录（docs/）

1. [01_memory.md](01_memory.md) — 堆/栈/元空间溢出三实验，JVM 内存结构与 GC 算法基础，含 3 个真实踩坑记录；
2. [02_gc.md](02_gc.md) — GC 日志解读与 G1/ZGC 对比，GC 算法、G1/ZGC 原理、CMS 历史与 JDK14 移除事实；
3. [03_classloader.md](03_classloader.md) — 双亲委派与破坏实践（含 Prohibited package name 真实案例）；
4. [04_reference.md](04_reference.md) — 强/软/弱/虚引用与可达性分析、ThreadLocal 泄漏；
5. [05_deadlock.md](05_deadlock.md) — 死锁制造与 ThreadMXBean 自动检测；
6. [06_oom.md](06_oom.md) — OOM 现场诊断四步法与 HeapDumpOnOutOfMemoryError 实证；
7. [07_args.md](07_args.md) — 运行时观测仪表盘 + **生产 JVM 参数建议模板** + **调优前后对比记录模板**。

每篇讲义结构：实验目标 → 源码要点 → 运行命令 → 真实运行输出 → 原理分析 → 面试追问(≥5)。

## 七、关键收获（真实运行得出的结论）

1. OOM 后 catch 里任何分配都可能二次 OOM（`Class.getName()`、StringConcatFactory 均真实复现）——catch 内只置标志并先释放引用；
2. `-Xss256k` 使 main 线程递归深度从 16435 → 2307，而 Thread(stackSize) 线程不受 -Xss 影响；
3. 32m 元空间下 5373 个"极小类+独立 loader"即触顶（每个约 6KB 元数据开销）；
4. 64m 堆里 31 个 1MB byte[] 即 OOM——G1 humongous 对象的隐性代价；
5. 同名类跨 ClassLoader 强转抛 `HelloTarget cannot be cast to HelloTarget`——类同一性=(loader, FQCN)；
6. 运行时 `setVMOption("HeapDumpOnOutOfMemoryError","true")` 后首次 OOM 真实生成 35MB hprof。

## 八、已知边界

- GcCompareLab 的 MXBean 近似测量在秒级负载下有失真，精确对比需分钟级负载 + 解析 Pause 行（讲义 02 有说明）；
- ReferenceLab 软引用在小堆压力下仍可能存活（真实语义如此），实验输出如实记录；
- Shenandoah 在 Oracle/Temurin 构建不可用，对比实验仅覆盖 G1/ZGC。
