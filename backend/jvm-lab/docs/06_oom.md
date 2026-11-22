# 06 · OOM 诊断实验讲义（OomDiagnosisLab）

> 模块路径：`backend/jvm-lab`
> 包名：`com.campusflow.lab.jvm.oom`

---

## 一、实验目标

1. 小堆（-Xmx64m）下真实触发 OOM，catch 后打印诊断要点；
2. 用 `com.sun.management.HotSpotDiagnosticMXBean` 读取堆相关参数（MaxHeapSize 等）；
3. **运行时动态开启** `-XX:+HeapDumpOnOutOfMemoryError`（manageable 标志可运行时写）并真实产出 .hprof；
4. 给出 OOM 现场诊断四步法（与 scripts/ 采集脚本配合）。

---

## 二、源码要点

```java
// 1. HotSpotDiagnosticMXBean 读取参数（jdk.management 模块内置，无需额外依赖）
com.sun.management.HotSpotDiagnosticMXBean hs =
        ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
String maxHeap = hs.getVMOption("MaxHeapSize").getValue();

// 2. 运行时动态设置（HeapDumpOnOutOfMemoryError 是 manageable，可运行时写）
hs.setVMOption("HeapDumpOnOutOfMemoryError", "true");

// 3. 触发 OOM（catch 内第一件事必须是释放强引用！）
try {
    while (total < budgetBytes) { holder.add(new byte[1024 * 1024]); total += CHUNK; }
} catch (OutOfMemoryError e) {
    oom = true;          // 堆 100% 满时 catch 里禁止任何分配（包括拼字符串）
} finally {
    holder.clear();      // 先释放引用再做事
}
```

**真实踩坑记录**：初版在 catch 中调用 `e.getClass().getName() + ...`，
-Xmx64m 下第二次 OOM 直接冲出 catch（栈顶 `Class.initClassName` → `Class.getName`）——
OOM 后进程已"半残"，catch 里只应置标志 + 释放引用，日志等恢复后再补。

---

## 三、运行命令与真实输出

```bash
java -Dfile.encoding=UTF-8 -Xmx64m -cp target/classes com.campusflow.lab.jvm.oom.OomDiagnosisLab
```

真实输出（JDK17，Temurin 17.0.21.1+2）：

```text
== OomDiagnosisLab：OOM 现场诊断 ==

[1] HotSpotDiagnosticMXBean 读取当前 JVM 参数：
    MaxHeapSize = 67108864
    InitialHeapSize = 67108864
    NewSize = 1363144
    MaxNewSize = 39845888
    UseG1GC = true
    UseZGC = false
    UseParallelGC = false
    UseSerialGC = false
    HeapDumpOnOutOfMemoryError = false
    HeapDumpPath = 
    MaxMetaspaceSize = -1
    CompressedClassSpaceSize = 1073741824

[2] 运行时动态设置 HeapDumpOnOutOfMemoryError（该标志是 manageable，可动态修改）：
    setVMOption 成功，回读 HeapDumpOnOutOfMemoryError = true
    等价启动参数: -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<dir>

[3] 触发真实 OOM（预算上限 2GB，-Xmx64m 下几秒内即触发）：
java.lang.OutOfMemoryError: Java heap space
Dumping heap to java_pid11396.hprof ...
Heap dump file created [35776463 bytes in 0.021 secs]
    结果: java.lang.OutOfMemoryError: Java heap space，触发前持有 31MB
```

**输出亮点**：
1. `-Xmx64m` 对应 `MaxHeapSize = 67108864`（64×1024×1024，精确）；
   `MaxMetaspaceSize = -1` 表示未设上限；`CompressedClassSpaceSize = 1073741824`（1G 默认保留区）；
2. **真实产出了 heap dump**：运行时 setVMOption 生效后，触发 OOM 的瞬间
   JVM 打印 `Dumping heap to java_pid11396.hprof ... Heap dump file created [35776463 bytes]`——
   35MB 的 dump 与 64MB 堆中 31MB 活对象相符（dump 是 Full GC 后全量含不可达对象）；
   该文件可用 Eclipse MAT 直接打开验证 Dominator Tree 里就是 31 个 1MB byte[]。
3. 注意：`-XX:+HeapDumpOnOutOfMemoryError` 需要配合 `-XX:HeapDumpPath` 指定目录/文件，
   否则默认写到工作目录；OOM 可能反复发生时 JVM **每个进程只 dump 一次**（防雪崩）。

---

## 四、OOM 现场诊断四步法

```text
1) 保留现场（进程可以崩，不能丢 dump）
   -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/data/dumps
   -XX:+ExitOnOutOfMemoryError            # 容器环境建议：崩了让编排层拉起新实例
   -XX:+CrashOnOutOfMemoryError           # 或崩溃产生 core
   也可运行时开启：jcmd <pid> VM.set_flag HeapDumpOnOutOfMemoryError true（manageable 标志）

2) 没 dump 时补救采集（大堆注意 :live 的 Full GC 代价）
   jmap -dump:live,format=b,file=heap.hprof <pid>     # 只存活对象，先 Full GC
   jcmd <pid> GC.heap_dump /path/heap.hprof           # 推荐
   scripts/collect_heap_dump.sh <pid>                 # 模块脚本封装

3) 离线分析
   Eclipse MAT: Leak Suspects / Dominator Tree / Path to GC Roots(排除软弱引用)
   VisualVM / jhat(已废弃)；超大 dump 可用 jmap -histo:live <pid> 先看类直方图

4) 结合 -Xlog:gc* 定性
   Java heap space               堆活对象真满（泄漏 or 容量不足）
   GC overhead limit exceeded    98% 时间 GC 只回收 <2%（同样活对象过多）
   Metaspace                     类元数据泄漏（动态类/ClassLoader 泄漏）
   unable to create new native thread   线程数/内存配额超限（不是堆问题）
   Direct buffer memory          -XX:MaxDirectMemorySize 堆外超限
   Requested array size exceeds VM limit   单次分配过大
```

---

## 五、常见面试追问与参考答案

**Q1：HeapDumpOnOutOfMemoryError 的原理与注意事项？**
A：JVM 在抛出首个 OOM 时（error handling 流程）若标志开启则 fork 出堆转储流程，
先做一次 full GC/不压缩转储再写 hprof。注意：① 每进程只 dump 一次（防反复 OOM 反复写盘）；
② 大堆 dump 需要等同堆大小的磁盘余量与秒级~分钟级时间；③ 容器里注意写入 PV，
否则写到容器临时层会丢；④ 与 ExitOnOutOfMemoryError 组合是云原生标配。

**Q2：jmap -dump 与 jcmd GC.heap_dump 有什么区别？:live 什么含义？**
A：功能等价，jcmd 更推荐（权限处理更稳、JDK8+ 通用）。
`-dump:live` 只转储 GC 后存活对象（转储前强制 Full GC），文件小、分析干净，但大堆 STW 明显；
不带 live 转储全量（含垃圾），文件大但不触发 GC。内存泄漏排查建议 live，
瞬态问题分析建议全量。

**Q3：MAT 里怎么看"谁泄漏"？**
A：Leak Suspects 报告定位嫌疑对象 → Dominator Tree 看支配树大头
（某个 HashMap 占 80% 堆之类）→ 对可疑对象 Path to GC Roots（exclude weak/soft references）
找强引用链 → 定位到代码里的静态集合/缓存。对比两个时间点的 dump（Histogram Diff）
是确认增长源的金标准。

**Q4：OOM 后进程还能正常服务吗？**
A：理论 catch 住可继续，实际已不可信：并发线程可能同时收到 OOM、
锁可能未释放、框架状态可能损坏。生产实践：OOM 视为进程级故障
（告警+保留 dump+重启），这正是 -XX:+ExitOnOutOfMemoryError 的意义。
本实验 catch 只是教学演示。

**Q5：unable to create new native thread 怎么排查？**
A：不是堆问题。看线程数（jstack 统计 / `ulimit -u`）、栈大小总和（-Xss×线程数）、
容器/OS 内存配额（线程栈也是内存）、`vm.max_map_count`。
缓解：降 Xss、线程池化、减少线程创建；根因常是"每请求一线程"或线程池无上限。

**Q6：为什么 dump 文件比 -Xmx 还小/还大？**
A：dump 大小 ≈ 转储时堆实际用量（full GC 后）+ 对象头开销，可能远小于 -Xmx；
含全部 region/元数据的版本（-XX:+HeapDumpCompressedObjects 之类视实现）可能偏大。
实验中 64m 堆 dump 35MB，与 31MB 活对象 + 开销吻合。
