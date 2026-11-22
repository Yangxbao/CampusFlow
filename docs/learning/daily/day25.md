# Day 25 · JVM 内存区与三类 OOM

> 阶段八（25-26 JVM）第 1 天。主教材 `backend/jvm-lab`（10 实验 + 30 测试 + 8 脚本 + 7 讲义），
> 配套讲义 `docs/learning/09_JVM与性能排查.md`。

## 一、今天学习什么

1. **运行时数据区地图**：线程私有（PC/虚拟机栈/本地方法栈）vs 共享（堆/Metaspace）；
   项目参数映射：-Xss（栈）、-Xms/-Xmx（堆）、-XX:MaxMetaspaceSize；
2. **三类 OOM 全部亲手复现**（jvm-lab 实验 1/2/3）：
   - HeapOverflowLab（-Xmx64m）：List 持强引用 1MB byte[] → 31 个即 OOM
     （G1 humongous 对象占连续 Region 的隐性代价）；
   - StackOverflowLab（-Xss256k）：递归深度 16435→2307；Thread(stackSize) 构造不受 -Xss 影响；
   - MetaspaceLab（-XX:MaxMetaspaceSize=32m）：5373 个动态类+独立 loader 触顶；
     loader 强引用 = 真实泄漏形态（Groovy/动态代理/redeploy）；
3. **OOM 后 catch 的坑**：任何分配都可能二次 OOM（Class.getName/StringConcatFactory 真实复现）；
   catch 内只置标志先释放引用；
4. **四种引用**（ReferenceLab）：强（OOM 根源）/软（内存不足才回收，缓存）/弱
   （下次 GC 必回收，ThreadLocalMap key）/虚（回收通知，DirectByteBuffer Cleaner）。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/jvm-lab/docs/jvm-lab_README.md` | 模块总览 + 关键收获（真实运行结论） |
| `backend/jvm-lab/docs/01_memory.md` | 三 OOM 实验详解（含踩坑记录） |
| `backend/jvm-lab/docs/04_reference.md` | 四引用与 ThreadLocal 泄漏 |
| `backend/jvm-lab/src/main/java/com/campusflow/lab/jvm/memory/HeapOverflowLab.java` | 实验代码（子线程触发+安全设计） |
| `backend/jvm-lab/src/main/java/com/campusflow/lab/jvm/memory/MetaspaceLab.java` | 手工拼 class 字节码的巧思 |
| `backend/jvm-lab/src/main/java/com/campusflow/lab/jvm/reference/ReferenceLab.java` | 四引用对比表 |

## 三、运行哪些命令

```bash
# 编译（小堆运行，秒级触发）
mvn -pl backend/jvm-lab compile

java -Xmx64m -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.memory.HeapOverflowLab
java -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.memory.StackOverflowLab
java -Xss256k -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.memory.StackOverflowLab
java -XX:MaxMetaspaceSize=32m -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.memory.MetaspaceLab
java -Xmx64m -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.reference.ReferenceLab

mvn -pl backend/jvm-lab test    # 30 用例全绿（测试 JVM 限 -Xmx256m，安全设计）
```

## 四、完成什么实验

1. 三类 OOM 全复现，记录：触发参数、异常信息、递归深度/类数——与 jvm-lab README
   第七节的结论对照（16435→2307 / 5373 类 / 31 个 1MB 数组）；
2. **-Xss 对比实验**：默认栈 vs 256k 两次跑 StackOverflowLab，抄录深度数字；
3. **四引用观察**：ReferenceLab 输出对照讲义对比表，确认软引用在小堆下先于 OOM 被回收。

## 五、面试自测（口头回答，答案要点）

1. **JVM 内存区域划分？哪些 OOM 你见过？**
   （五区 + 三类 OOM 实测数据（31 个数组/16435→2307/5373 类）——用数字证明动手过。）
2. **Metaspace 为什么会泄漏？**
   （动态类 + ClassLoader 强引用；每类约 6KB 元数据；Tomcat redeploy 场景；
   防护：MaxMetaspaceSize 上限 + 监控类加载计数。）
3. **ThreadLocal 的 key 为什么设计成弱引用？**
   （让外部无强引用后 key 可被 GC，减少泄漏面；value 仍强引用需 remove——
   项目 Filter/Interceptor 两处清理。）
4. **软引用适合什么场景？有什么坑？**
   （缓存兜底；坑：GC 后仍紧张才回收，时机不可控——本项目热点缓存用 Caffeine
   显式 TTL/上限而非软引用，行为可预期。）
5. **OOM 后还能做什么？**
   （HeapDumpOnOutOfMemoryError 自动留证（35MB hprof 实测）；ExitOnOutOfMemoryError
   交给重启；catch 里不做任何分配。）

## 六、自我检查标准

- [ ] 三个 OOM 实验 + 四引用实验跑通；
- [ ] 三组真实数字背出（31/16435→2307/5373）；
- [ ] 内存区地图能画（含 humongous 与 Metaspace 位置）；
- [ ] 讲义 09 的 Q1-Q3（基础区）回答达标。

## 附一、知识点详解

**1. humongous 对象的判定与代价（G1 特有）**
判定：对象大小 ≥ Region 大小的 50%（默认 Region = 堆/2048 向上取 2 的幂）。
64m 堆 → Region 1MB → 1MB 数组恰好 humongous。代价：连续 Region 分配
（易碎片化）、直接进老年代（跳过 Young GC 缓冲）、Mixed GC 回收它需 special 处理。
**业务映射**：大 DTO/长 JSON/CSV 整文件读入都可能成为 humongous——导出任务要用流式写。

**2. 三种 OOM 的"堆外视角"**
Heap OOM 在堆内；Metaspace OOM 在本地内存（MaxMetaspaceSize 上限）；
unable to create new native thread 是 OS 层（线程数 × -Xss + 其他进程内存超限）。
三者对应的观测工具不同：MAT 看 dump、类加载统计看 Metaspace、
`jstack | wc -l` 看线程数。

**3. OOM 测试的安全设计（jvm-lab 的工程细节，面试可讲）**
所有危险实验在子线程触发 + 内部 catch + @Timeout 兜底 + surefire 限 -Xmx256m——
**"能安全地制造故障"本身是测试工程能力**。对照生产：HeapDumpOnOutOfMemoryError +
ExitOnOutOfMemoryError 让 OOM "可控地发生"。

**4. 四种引用的项目映射**
强：所有业务对象（默认）；弱：ThreadLocalMap key（day24）；虚：堆外内存回收通知
（Netty DirectByteBuffer）；软：**本项目没有用软引用做缓存**（Caffeine 用显式
TTL/上限，行为可预期）——知道"为什么不用"和"为什么用"同样重要。

## 附二、OOM 三实验记录卡

```
Heap:  -Xmx64m, OOM 前成功持有 ___ 个 1MB 数组(预期31), 原因: humongous
Stack: -Xss256k 递归深度 ___(预期2307); 默认栈 ___(预期16435)
Meta:  MaxMetaspaceSize=32m, 触顶类数 ___(预期5373), 平均每类开销≈6KB
四引用: 软引用在 OOM 前/后被回收: ___
```

---

## 附三、面试加练（3 题）

**1. 堆外内存（Direct Memory）怎么排查？**
要点：JVM 参数 MaxDirectMemorySize 上限 + NMT（NativeMemoryTracking=summary）
+ Netty 的 PooledByteByteBufAllocator metrics；现象是 RSS 高但堆正常——
本项目堆内为主，堆外风险在 Netty/IO 引入后才出现。

**2. G1 常用调优参数除了 PauseMillis 还有哪些？**
要点：G1HeapRegionSize（大对象多可增大）、InitiatingHeapOccupancyPercent
（并发标记启动阈值，默认 45）、G1ReservePercent（to-space 预留）——
**先看 GC 日志定位问题再调参**，不是背参数表。

**3. 类加载器泄漏怎么排查？**
要点：MAT 的 Class Loader Explorer 看每个 loader 的类数；
jcmd GC.class_histogram 的时间序列对比；本实验（MetaspaceLab）就是它的
"手工复刻版"——loader 强引用是根因。

**今日金句**：OOM 不是异常，是"内存模型对你代码的审判结果"。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 内存区与 OOM | 09_JVM与性能排查 第 3-4 节 |
| 四种引用 | 09_JVM 第 4.4 节；jvm-lab docs/04 |

**容易说错的表述**：
- ✗ "OOM 是异常要 catch" → ✓ "OOM 是 Error，靠留证+退出兜底，catch 内不能分配"；
- ✗ "元空间在堆里" → ✓ "Metaspace 用本地内存，与堆独立上限"。

---

## 附五、明天的预习清单

1. 安装好 Eclipse MAT 或 VisualVM（明天分析 hprof 用）；
2. 确认 `backend/jvm-lab/scripts/` 的 .sh 有执行权限（Linux/WSL：chmod +x）；
3. 重读讲义 09 的第五节（GC 日志语法四段式），明天直接进入实操。

---

## 明日预告

Day 26 GC 与诊断工具链：gc 日志、G1/ZGC、jstack/jmap/jcmd。
