# Day 26 · GC 与诊断工具链（阶段八收网）

> 阶段八第 2 天。今天的主题："看得到"——GC 日志与四大诊断命令，
> 全部用 jvm-lab 的 8 个脚本实操。

## 一、今天学习什么

1. **GC 日志（GcLogLab + docs/02_gc.md）**：
   - JDK17 统一日志语法：`-Xlog:gc*,gc+heap=debug:file=gc.log:time,uptime,level,tags`
     （tag 集/输出/装饰字段三段式）；JDK8 的 PrintGCDetails 已移除；
   - Pause 行解读：`Pause Young (Normal) (G1 Evacuation Pause) Eden 2048M->0M ... real=0.02s`；
   - 统计：`grep 'Pause' gc.log`；坏信号：to-space exhausted / Full (Allocation Failure)；
2. **G1 vs ZGC（GcCompareLab）**：同负载跑两遍对比（收集器启动时确定，程序内无法切换）；
   MXBean 近似测量（GC 时间占比/分配速率）+ 已知边界（秒级负载有失真）；
3. **诊断工具链（scripts/ 四组脚本）**：
   - collect_thread_dump.sh：jstack 连采 3 份（间隔 2s）——**一份是快照三份才是证据**；
     死锁检查 grep 'Found one Java-level deadlock'；
   - collect_heap_dump.sh：jmap -dump:live（**会先触发 Full GC，生产评估影响**）/MAT 分析；
   - jcmd_diagnose.sh：一站式 VM.uptime/VM.flags/Thread.print/GC.class_histogram/GC.heap_info；
   - run_with_gclog.sh：带 gc 日志运行任意 main 的模板；
4. **死锁（DeadlockLab）**：制造 → ThreadMXBean.findDeadlockedThreads 自动检测 →
   JVM 不会自动解开 synchronized 死锁；
5. **OOM 诊断（OomDiagnosisLab）**：HotSpotDiagnosticMXBean 读参数 + 运行时
   setVMOption 开 HeapDumpOnOutOfMemoryError（实测 35MB hprof）；
6. **参数仪表盘（JvmArgsLab）**：改参数后先跑它验证生效。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/jvm-lab/docs/02_gc.md` | GC 日志解读与 G1/ZGC 对比（含 CMS 历史与 JDK14 移除事实） |
| `backend/jvm-lab/docs/05_deadlock.md` / `06_oom.md` / `07_args.md` | 死锁/OOM 四步法/生产参数模板 |
| `backend/jvm-lab/scripts/jcmd_diagnose.sh` | 脚本头部注释即使用说明（含 Full GC 代价提示） |
| `backend/campusflow-server/src/main/resources/application-local.yml` | 项目建议的 GC 参数（-Xms512m -Xmx1g UseG1GC MaxGCPauseMillis=100） |
| `docs/learning/09_JVM与性能排查.md` 第七节 | 生产参数模板（事故留证三件套） |

## 三、运行哪些命令

```bash
# GC 日志
java -Xmx256m -Xlog:gc*:file=gc.log:time,uptime,level,tags -cp backend/jvm-lab/target/classes \
     com.campusflow.lab.jvm.gc.GcLogLab
grep 'Pause' gc.log

# G1 vs ZGC 对比
java -Xms256m -Xmx256m -XX:+UseG1GC -Xlog:gc*:file=g1.log:time,uptime,level,tags -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.gc.GcCompareLab g1
java -Xms256m -Xmx256m -XX:+UseZGC  -Xlog:gc*:file=zgc.log:time,uptime,level,tags -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.gc.GcCompareLab zgc

# 死锁 + 诊断脚本三连（另开终端）
java -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.deadlock.DeadlockLab &
./backend/jvm-lab/scripts/collect_thread_dump.sh <pid> ./diagnostics
./backend/jvm-lab/scripts/jcmd_diagnose.sh <pid> ./diagnostics

# OOM 自动留证
java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./heapdump.hprof \
     -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.oom.OomDiagnosisLab
```

## 四、完成什么实验

1. **gc 日志读三行**：跑 GcLogLab 后挑一条 Young GC 逐字段解释（GC 编号/原因/各代变化/real）；
2. **对比报告**：G1 vs ZGC 各跑一遍，记录 GC 时间占比与平均暂停，3 行结论；
3. **死锁演练**：DeadlockLab 运行中用脚本连采 3 份 thread dump，
   找到 deadlock 段落并解释两条线程的锁链；
4. **hprof 留证**：跑 OomDiagnosisLab 确认 heapdump.hprof 生成，用 VisualVM/MAT 打开看
   Dominator Tree（byte[] 占大头即本次实验的元凶）。

## 五、面试自测（口头回答，答案要点）

1. **线上 CPU 100% 排查步骤？**
   （top → top -Hp → printf %x tid → jstack/jcmd Thread.print grep 十六进制 →
   区分业务线程还是 GC 线程 → 后者转 heap 路线——讲义 09 Q4 完整版。）
2. **-Xlog:gc* 里最先看什么？**
   （Pause real 时间与 GC 原因；坏信号（to-space exhausted/Full）；
   频率趋势判断分配速率变化。）
3. **为什么选 G1？什么时候换 ZGC？**
   （1g 小堆小对象 G1 吞吐好内存省（压测 P99 5ms 无 GC 报警）；8G+ 大堆 P99 敏感
   才考虑 ZGC 的亚毫秒暂停 + 读屏障吞吐税。）
4. **jstack 和 jcmd 优先用哪个？**
   （jcmd：一站式且与目标同 JVM 时更稳；Thread.print 等价 jstack；
   class_histogram 比 dump 快但同样触发 Full GC，先粗后细。）
5. **死锁能自动恢复吗？**
   （synchronized 死锁 JVM 不恢复（实验里 main 都要 System.exit 才能退出）；
   生产只能 jstack 找锁链修代码或重启；预防：锁排序/tryLock 超时。）

## 六、自我检查标准（阶段八收网）

- [ ] 10 个 jvm-lab 实验全部跑过（两类今天：gc/诊断 + 昨天三类 OOM/引用/类加载/参数）；
- [ ] 诊断脚本三连实操完成，产物在 ./diagnostics 归档；
- [ ] 一条 GC 日志逐字段讲解录音 1 分钟；
- [ ] 生产参数模板能默写（容量/收集器/事故留证三段）；
- [ ] 讲义 09 全部面试题过一遍；错题本更新。

## 附一、知识点详解

**1. 一条 GC 日志的"字段-含义"卡（默写级）**
```
GC(37) Pause Young (Normal) (G1 Evacuation Pause)
│      │            │        └ 触发机制: 复制存活对象
│      │            └ 收集类型: Young/Young(Mixed)/Full
│      └ 可预测性: Normal/Concurrent Start
└ 编号
Eden 2048M->0M(2048M)  → 回收了整个 Eden
Survivors 256M->512M   → 存活对象翻倍(晋升缓冲)
Heap 4.2G(32%)->2.1G(16%) → 堆使用率腰斩=回收有效
Times: user=0.03 real=0.02 → 多线程并行 GC
```

**2. 生产参数模板的"三段记忆法"**
容量段（-Xms=-Xmx / MaxMetaspaceSize / Xss）+ 收集器段（G1 + PauseMillis）+
留证段（HeapDumpOnOOm + Path + gc 日志轮转 filecount/filesize）。
加上 ExitOnOutOfMemoryError 共 9 个参数——**面试默写 9 个参数，
每个带一句理由**。

**3. jstack/jmap/jcmd 的选择决策树**
优先 jcmd（同 JVM 版本一站式）：先 VM.uptime/VM.flags（轻量无副作用）→
Thread.print（线程）→ GC.class_histogram（内存粗查，触发 Full GC）→
GC.heap_dump（重量级，最后用）。jstack 在需要连采对比时用（脚本已封装 3 连采）。

**4. 死锁日志的读法**
`Found one Java-level deadlock` 段落会列出互相等待的两条线程与各自持有的锁；
`-l` 参数还会打印 Lock 对象信息。DeadlockLab 的 ThreadMXBean 检测与此等价
——**工具诊断的原理是"等待图找环"**。

## 附二、诊断产物归档清单

```
gc_<ts>.log            → grep 'Pause' 统计 ___ 次, 平均 ___ms
threaddump_*_1/2/3.txt → BLOCKED 线程对比结论: ___
jcmd_*_GC.class_histogram.txt → 前三占用类: ___
heapdump.hprof         → MAT Dominator Tree 首位: byte[] (实验预期)
```

---

## 附三、面试加练（3 题）

**1. 容器里跑 JVM 有什么特殊注意？**
要点：JDK8u191+ / JDK10+ 默认 UseContainerSupport（按 cgroup 限额算内存，
不是物理机内存）；仍建议显式 -Xmx + MaxRAMPercentage；GC 线程数按 CPU 限额
计算（ActiveProcessorCount）。**容器感知避免"按宿主机 32G 算堆"的事故**。

**2. GC 日志怎么自动化分析？**
要点：轮转文件接入日志采集 → GCeasy/GCLogAnalyzer 定期解析 → 提取
Pause P99/Full GC 次数/晋升速率做趋势告警——手工 grep 是排查时用的，
常态监控要走自动化。

**3. 灰度发布时 JVM 观察什么？**
要点：新实例的启动分配速率、GC 频率与老实例对比；出现 Full GC 意味着
新代码有内存行为变化（缓存未上限/大对象）——灰度是 JVM 回归测试的机会。

**今日金句**：诊断工具链的价值不在"会用命令"，而在"知道先看什么、后看什么"。

---

## 明日预告

Day 27 分库分表：ShardingSphere 5 个集成测试讲透。
