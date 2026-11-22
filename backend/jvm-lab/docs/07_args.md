# 07 · JVM 参数实验讲义：运行时观测与参数模板（JvmArgsLab）

> 模块路径：`backend/jvm-lab`
> 包名：`com.campusflow.lab.jvm.args`
> 本讲义包含任务要求的 **生产 JVM 参数建议模板** 与 **调优前后对比记录模板**（见第五、六节）。

---

## 一、实验目标

打印运行时"仪表盘"：Runtime 三兄弟、MemoryMXBean 堆/非堆、各内存池明细、
当前 GC 名称（反推收集器）、JVM 启动参数原样回显——
**改任何 JVM 参数后先跑它确认生效**，这是调优闭环的第一步。

---

## 二、观测 API 对照表

| 想看什么 | API |
|---|---|
| 堆上限/已提交/空闲 | `Runtime.maxMemory()/totalMemory()/freeMemory()` |
| 堆与非堆汇总 | `MemoryMXBean.getHeapMemoryUsage()/getNonHeapMemoryUsage()` |
| 各内存池（Eden/Survivor/Old/Metaspace/CodeCache） | `MemoryPoolMXBean`（type=HEAP/NON-HEAP，含 Usage/Peak） |
| 当前收集器 | `GarbageCollectorMXBean.getName()`（G1 Young Generation 等） |
| JVM 启动参数 | `ManagementFactory.getRuntimeMXBean().getInputArguments()` |
| 任意 HotSpot 标志 | `HotSpotDiagnosticMXBean.getVMOption/setVMOption` |
| 线程分配字节数 | `com.sun.management.ThreadMXBean.getThreadAllocatedBytes(tid)` |

语义细节：`maxMemory` ≈ -Xmx（运行时可变池上限）；`totalMemory` 是当前**已向 OS 提交**
的堆大小（≈ -Xms 起步，随需增长）；`freeMemory` 是 totalMemory 内未分配部分；
`used = total - free`。"Runtime 三兄弟"口径与 MemoryMXBean 的
init/used/committed/max 一一对应。

---

## 三、运行命令

```bash
java -Dfile.encoding=UTF-8 -Xms128m -Xmx512m -Xss512k -XX:+UseG1GC -XX:MaxMetaspaceSize=64m \
     -cp target/classes com.campusflow.lab.jvm.args.JvmArgsLab
```

## 四、真实运行输出

```text
== JvmArgsLab：JVM 运行时内存/GC/参数一览 ==
[1] Runtime（当前堆视图，单位 MB）:
    maxMemory   = 512MB  (≈ -Xmx，已提交上限)
    totalMemory = 128MB  (≈ 当前已向 OS 提交的堆大小，≈ -Xms 起步随需增长)
    freeMemory  = 125MB  (totalMemory 里还没被分配的部分)
    used        = 2MB  (totalMemory - freeMemory)

[2] MemoryMXBean 堆/非堆用量:
    Heap:     init=128MB used=3MB committed=128MB max=512MB
    NonHeap:  init=7MB used=3MB committed=8MB max=368MB
    pending finalizable objects: 0

[3] MemoryPoolMXBean 各内存池（G1 下常见: Eden/Survivor/Old/Metaspace/CodeCache）:
    [NON-HEAP] CodeHeap 'non-nmethods'        init=2MB used=2MB committed=2MB max=7MB ...
    [NON-HEAP] Metaspace                      init=0MB used=0MB committed=1MB max=64MB ...
    [NON-HEAP] CodeHeap 'profiled nmethods'   init=2MB used=0MB committed=2MB max=116MB ...
    [NON-HEAP] Compressed Class Space         init=0MB used=0MB committed=0MB max=64MB ...
    [HEAP    ] G1 Eden Space                  init=25MB used=3MB committed=25MB max=-1MB ...
    [HEAP    ] G1 Old Gen                     init=103MB used=0MB committed=103MB max=512MB ...
    [HEAP    ] G1 Survivor Space              init=0MB used=0MB committed=0MB max=-1MB ...
    [NON-HEAP] CodeHeap 'non-profiled nmethods' init=2MB used=0MB committed=2MB max=116MB ...

[4] GarbageCollectorMXBean（GC 名称可反推当前收集器）:
    G1 Young Generation      count=0 time=0ms  pools=G1 Eden Space,G1 Survivor Space,G1 Old Gen
    G1 Old Generation        count=0 time=0ms  pools=G1 Eden Space,G1 Survivor Space,G1 Old Gen

[5] ManagementFactory.getRuntimeMXBean().getInputArguments():
    -Dfile.encoding=UTF-8
    -Xms128m
    -Xmx512m
    -Xss512k
    -XX:+UseG1GC
    -XX:MaxMetaspaceSize=64m

[6] 其他运行时信息:
    java.version=17.0.21.1 vm.name=OpenJDK 64-Bit Server VM pid=31060@DESKTOP-S0PLL5V uptime=106ms
    可用处理器: 16
```

**观察点**：Metaspace 池 max=64MB 正是 `-XX:MaxMetaspaceSize=64m` 生效的证据；
`G1 Eden max=-1MB` 表示新生代不设固定上限（自适应）；
GC 名称 `G1 Young Generation` 可程序化判断当前收集器（ZGC 下为 ZGC Cycler 系列名称）。

---

## 五、生产 JVM 参数建议模板

> 以下为 JDK17 + Spring Boot 服务的参考起点（容器 4C8G 为例），按业务压测调整。
> 直接抄模板有风险，**必须结合压测与 GC 日志迭代**。

```bash
# ========== 基础内存 ==========
-Xms4g -Xmx4g                        # 堆与容器内存留 25% 余量（4G 堆 + ~1G 元空间/线程栈/堆外）
-XX:MaxMetaspaceSize=256m            # 元空间上限，防动态类泄漏打爆容器
-XX:MaxDirectMemorySize=1g           # 堆外直接内存上限（Netty/NIO）

# ========== 收集器（二选一） ==========
# 方案 A：通用吞吐+延迟平衡（默认推荐）
-XX:+UseG1GC
-XX:MaxGCPauseMillis=200             # 停顿目标，按业务 P99 要求定
-XX:InitiatingHeapOccupancyPercent=45
# 方案 B：大堆低延迟（16G+ 且对延迟敏感）
-XX:+UseZGC

# ========== 线程栈 ==========
-Xss1m                               # 默认即可；海量线程服务可降到 512k

# ========== 可观测性（必配） ==========
-Xlog:gc*,gc+heap=debug,gc+humongous=debug:file=/data/logs/gc/gc-%t.log:time,uptime,level,tags:filecount=5,filesize=50m
-XX:+HeapDumpOnOutOfMemoryError      # OOM 保留现场
-XX:HeapDumpPath=/data/dumps
-XX:+ExitOnOutOfMemoryError          # 云原生：OOM 即退出，交给编排拉起
-XX:NativeMemoryTracking=summary     # NMT 摘要，排查堆外内存用（开销 ~5%）
-XX:ErrorFile=/data/logs/hs_err_%p.log

# ========== 容器感知（JDK8u191+/JDK10+ 默认开启，显式声明防覆盖） ==========
-XX:+UseContainerSupport
-XX:ActiveProcessorCount=4           # 与 CPU 配额一致（GC 线程按它计算）

# ========== 字符编码与时区 ==========
-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai
```

**参数分层记忆法**：内存（堆/元空间/堆外）→ 收集器（选型+停顿目标）→
可观测（gc 日志/dump/error file）→ 环境（容器/编码）。
禁用项：CMS 系（JDK14 已移除）、PermGen 系（JDK8 已废弃）、
`-XX:+UseCompressedOops` 手动开关（JDK17 自动决策）。

## 六、调优前后对比记录模板

> 每次调优必须留下记录，可追溯、可回滚。真实案例格式（数字示例，实际以压测为准）：

```markdown
### 调优记录 #<编号>
- 日期/执行人：2026-xx-xx / xxx
- 服务：campusflow-sign-api   实例：4C8G 容器 × 8
- 现象与痛点：
    每日 10:00 高峰接口 P99 从 80ms 飙到 600ms，伴随 -Xlog:gc 出现
    "Pause Young 1.2s"，Old 区 GC 后占用持续 >70%。
- 调优假设：
    大促预热把全量商品缓存放堆内（约 2G），导致 Mixed GC 追不上分配速度。

### 前置基线（优化前 24h）
| 指标 | 数值 | 采集方式 |
|---|---|---|
| QPS/RT P50/P99 | 1200 / 45ms / 612ms | 网关监控 |
| Young GC 频率/平均停顿 | 8 次/分 / 45ms | gc.log 统计 |
| Full/Mixed GC 频率 | 3 次/小时 | gc.log |
| 堆峰值占用(GC 后 Old) | 3.1G / 4G | gc.log + jstat -gcutil |
| 分配速率 | 350 MB/s | gc.log(Eden 增量/时间) |
| CPU / GC 线程占比 | 62% / 18% | jstat + 容器指标 |

### 变更（一次只改一组，禁止一把梭）
```diff
- -Xms4g -Xmx4g -XX:MaxGCPauseMillis=100
+ -Xms6g -Xmx6g                       # 堆加大降低频率
+ -XX:MaxGCPauseMillis=200            # 放宽目标换取吞吐
+ -XX:+G1UseAdaptiveIHOP              # 自适应并发标记触发
```
同时业务侧：商品缓存改 Redis + 本地 Caffeine(上限 5000 条)。

### 后置对比（优化后 24h，同流量）
| 指标 | 前 | 后 | 变化 |
|---|---|---|---|
| QPS/RT P99 | 612ms | 118ms | -80% |
| Young GC 频率/停顿 | 8次/分 45ms | 3次/分 28ms | 频率-62% |
| Full/Mixed GC | 3次/时 | 0.2次/时 | -93% |
| GC CPU 占比 | 18% | 6% | -67% |

### 结论与回滚预案
结论：缓存外置 + 堆扩容解决 Mixed GC 追赶问题；P99 达标。
回滚预案：镜像版本 v1.2.3，参数回滚即还原启动脚本 git revert。
遗留观察：关注堆外内存 NMT（新增 Redis 客户端 buffer）。
```

---

## 七、常见面试追问与参考答案

**Q1：-Xms 与 -Xmx 设成一样好不好？**
A：生产通常一样：避免堆伸缩带来的向 OS 申请/归还内存的开销与抖动，
让 GC 的自适应预测更稳定（G1 按 committed 计算暂停目标）。缺点是内存水位恒高，
容器/混部场景下也可能希望收缩（`-XX:MaxHeapFreeRatio/MinHeapFreeRatio` + G1 的 uncommit）。
JDK17 G1 默认会按时段归还内存（JEP 346）。

**Q2：怎么知道一个 JVM 参数当前有没有生效？**
A：三层验证：① 启动参数回显 `getRuntimeMXBean().getInputArguments()` 或 `jcmd VM.command_line`；
② 实际值 `jcmd <pid> VM.flags` 或 HotSpotDiagnosticMXBean.getVMOption
（本实验演示：-Xmx64m → MaxHeapSize=67108864）；③ 效果验证看 gc 日志的 Heap Region Size/
Heap Max Capacity 等行。注意有些参数是"建议"（如 MaxGCPauseMillis）而非硬约束。

**Q3：容器里 JVM 内存参数要注意什么？**
A：JDK10+ 默认 UseContainerSupport 按 cgroup 限额计算堆默认值（约 25%）；
生产建议显式 -Xmx ≤ 容器内存的 50~75%，预留元空间、线程栈、堆外、NMT 开销；
ActiveProcessorCount 显式指定防 CPU 配额误判导致 GC 线程过多；
-XX:+ExitOnOutOfMemoryError 配合 k8s liveness 让编排接管。

**Q4：为什么 -Xmx64m 时 maxMemory() 可能不等于 64MB？**
A：不同收集器/平台的取整与可用性计算：G1 下 maxMemory ≈ -Xmx 按页对齐；
Serial 早期还有减去 Survivor 保留等口径差异。本实验真实输出
`-Xmx64m → MaxHeapSize = 67108864`（正好 64M），而 `Runtime.maxMemory=64MB`；
某些平台上会看到 64m 显示为 62~66MB，属于对齐与保留区口径，属正常。

**Q5：jcmd VM.flags 和 getInputArguments 有什么区别？**
A：getInputArguments 只回显"启动时传入"的参数（含 -D 系统属性）；
VM.flags 显示**当前实际生效的**全部可管理标志（含默认值与运行时改动），
例如不传 -Xmx 时 VM.flags 会给出 ergonomics 推算出的 MaxHeapSize。
调优验证应以 VM.flags 为准。

**Q6：Noop/GC 之外，还有哪些必配的"体检"参数？**
A：GC 日志轮转、OOM dump + Exit、ErrorFile、NativeMemoryTracking、
-XX:+PrintCommandLineFlags（启动时回显生效值）、
JFR（JDK17 `-XX:StartFlightRecording=duration=60s,filename=...` 或持续低开销模式）
——JFR 是 JDK 内置生产诊断利器，采集分配采样/锁竞争/GC 事件。
