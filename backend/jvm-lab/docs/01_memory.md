# 01 · 内存实验讲义：堆溢出 / 栈溢出 / 元空间溢出（HeapOverflowLab · StackOverflowLab · MetaspaceLab）

> 模块路径：`backend/jvm-lab`
> 包名：`com.campusflow.lab.jvm.memory`

---

## 一、实验目标

| 实验 | 类 | 触发目标 | 关键参数 |
|---|---|---|---|
| 堆溢出 | `memory/HeapOverflowLab` | `java.lang.OutOfMemoryError: Java heap space` | `-Xmx64m` |
| 栈溢出 | `memory/StackOverflowLab` | `StackOverflowError` 并测量递归深度 | `-Xss256k`（可选） |
| 元空间溢出 | `memory/MetaspaceLab` | `java.lang.OutOfMemoryError: Metaspace` | `-XX:MaxMetaspaceSize=32m` |

三个实验分别对应 JVM 三块不同的"内存区"：堆（对象实例）、线程栈（栈帧）、元空间（类元数据），
合起来就是 **JVM 运行时内存结构** 的主干。

---

## 二、JVM 运行时内存结构（原理铺垫）

```
JVM 运行时数据区（JLS/JVM 规范 + HotSpot 实现）
├── 线程私有
│   ├── 程序计数器 (PC Register)          唯一不会 OOM 的区域
│   ├── Java 虚拟机栈 (VM Stack)          栈帧=局部变量表+操作数栈+动态链接+返回地址；-Xss
│   └── 本地方法栈 (Native Method Stack)  native 方法使用
└── 线程共享
    ├── 堆 (Heap)                         对象实例；分代：Eden/Survivor/Old；-Xms -Xmx
    ├── 方法区 (Method Area) 逻辑概念
    │   └── JDK8+ 实现为元空间 Metaspace   类元数据，本地内存；-XX:MaxMetaspaceSize
    └── 运行时常量池                       JDK8 后位于堆中（字符串常量池在堆里的 StringTable）
堆外补充：直接内存 Direct Memory (-XX:MaxDirectMemorySize)、JIT 代码缓存 Code Cache
```

**JDK8 关键变化**：永久代 PermGen 被移除，类元数据迁入本地内存的 Metaspace，
字符串常量池和静态变量归入堆。`-XX:PermSize/-XX:MaxPermSize` 参数在 JDK8 已废弃、JDK17 中彻底无效。

---

## 三、实验 1：HeapOverflowLab（堆溢出）

### 3.1 源码要点

```java
// 必须小堆运行：java -Xmx64m -cp target/classes com.campusflow.lab.jvm.memory.HeapOverflowLab
List<byte[]> holder = new ArrayList<>();
try {
    while (total < budgetBytes) {
        byte[] chunk = new byte[CHUNK_SIZE];   // 1MB
        holder.add(chunk);                     // 持强引用 => 永远"可达" => 永远无法被 GC
        total += chunk.length;
    }
} catch (OutOfMemoryError e) {                 // catch 的是 Error 不是 Exception
    oom = true;
} finally {
    holder.clear();                            // 释放强引用，让 JVM 恢复
}
```

要点：
1. **`List<byte[]>` 强引用持续持有**——这些对象在 GC 眼里始终"可达"，任何一次 GC 都救不了堆；
2. catch `OutOfMemoryError`（Error 层级，不是 Exception）；
3. catch 后立刻 `clear()` 释放引用，JVM 才能恢复正常——OOM 后进程通常已"半残"，生产上以告警+重启为主；
4. `fillUntilOom(budgetBytes)` 带预算参数的设计是为了让 JUnit 测试可以在小预算下验证逻辑而不会拖死测试 JVM。

### 3.2 运行命令

```bash
mvn -pl backend/jvm-lab compile
java -Xmx64m -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.memory.HeapOverflowLab
```

### 3.3 真实运行输出

以下为本模块在 JDK17（Temurin 17.0.21.1+2，Windows x64）下实际执行的输出：

```text
$ java -Dfile.encoding=UTF-8 -Xmx64m -cp target/classes com.campusflow.lab.jvm.memory.HeapOverflowLab
== HeapOverflowLab：堆溢出复现 ==
Runtime: maxMemory=64MB totalMemory=64MB freeMemory=62MB used=2MB
提示：本实验请用小堆运行 -> java -Xmx64m -cp target/classes com.campusflow.lab.jvm.memory.HeapOverflowLab

---- OOM 分析要点 ----
捕获异常        : java.lang.OutOfMemoryError: Java heap space
触发前已持有    : 31 x 1MB ≈ 31MB（这些全是"活对象"，GC 无法回收）
Runtime: maxMemory=64MB totalMemory=8MB freeMemory=6MB used=1MB

1) OOM 含义：发生 GC 后（full GC / concurrent GC）堆仍然放不下本次分配，
   且这次分配失败是"永久性"的（不是分配太快 GC 来不及），即堆里全是"活对象"。
2) 常见根因排查顺序：
   a. 堆太小？看 -Xmx 与 maxMemory 是否符合预期；
   b. 内存泄漏？对比多份 heap dump（jmap / jcmd GC.heap_dump），
      用 MAT 的 Leak Suspects / Dominator Tree 找持续增长的集合与 GC Root 链；
   c. 缓存无上限（如本例 List）？加容量上限、过期策略或改用弱/软引用；
   d. 一次加载过多数据（大查询、大文件）？分页/流式处理。
3) 建议始终保留现场：-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/path/to/dumps
4) 若日志出现 "OutOfMemoryError: GC overhead limit exceeded"，
   表示 JVM 花了 >98% 时间做 GC 却只回收了 <2% 堆，同样是堆活对象过多的信号。
```

**两个真实踩坑记录（讲义作者在本环境实际遇到，极具教学价值）：**

1. **catch 里打印异常会二次 OOM**：初版在 catch 块中写
   `oomDetail = e.getClass().getName() + ": " + e.getMessage()`，
   真实输出直接是：

```text
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
	at java.base/java.lang.Class.initClassName(Native Method)
	at java.base/java.lang.Class.getName(Class.java:865)
	at com.campusflow.lab.jvm.memory.HeapOverflowLab.fillUntilOom(HeapOverflowLab.java:59)
```

   堆 100% 满时，连"拼一段异常描述字符串"的分配都会再抛 OOM 并冲出 catch 块。
   正确姿势：catch 内先 `holder.clear()` 释放强引用，再构造描述。

2. **31×1MB 就打满 64MB 堆（humongous 对象）**：64MB 堆的 G1 Region Size 只有 1MB
   （`Heap Region Size: 1M`，见 gc 日志），1MB 的 byte[] 超过 Region 一半，
   属于 humongous 对象，直接分配在连续的 Old Region 且一个对象占 2 个 Region，
   有效容量远低于 64MB，所以 31 个对象就触顶了。这正是 G1 对大对象不友好的直观体现。

### 3.4 预期现象与解读

- `-Xmx64m` 下数秒内触发 `java.lang.OutOfMemoryError: Java heap space`；
- 触发前"已持有"字节数 ≈ 堆上限（64m 中可分配给数组部分的极限，约 55~60MB）；
- 解释：**OOM ≠ 内存不足时立刻崩**。HotSpot 先做若干轮 GC（含 Full GC），
  确认回收后仍放不下"本次分配"，且回收后可用空间依然不够，才判定 OOM。

---

## 四、实验 2：StackOverflowLab（栈溢出）

### 4.1 源码要点

```java
private static void recurse(long depth, long[] holder) {
    if (depth >= HARD_LIMIT) return;
    holder[0] = depth;                 // 记录最后成功进入的帧深
    recurse(depth + 1, holder);
}
```

- 用 `long[] holder`（堆上的单元素数组）记录深度：捕获 SOE 后读出的值即"最后一次成功压栈"的深度；
- `Thread(ThreadGroup, Runnable, String, long stackSize)` 构造器可在**同一 JVM 内**
  为单个线程指定栈大小，等价于"线程级 -Xss"；
- `measureInNewThread(256 * 1024)` 与 `measureInNewThread(0)` 对比即可演示栈大小对深度的线性影响。

### 4.2 运行命令

```bash
java -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.memory.StackOverflowLab
java -Xss256k -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.memory.StackOverflowLab
```

### 4.3 真实运行输出

默认参数运行（JDK17，Temurin 17.0.21.1+2）：

```text
$ java -Dfile.encoding=UTF-8 -cp target/classes com.campusflow.lab.jvm.memory.StackOverflowLab
== StackOverflowLab：栈溢出复现与 -Xss 影响 ==
当前 JVM 输入参数: [-Dfile.encoding=UTF-8]

  捕获 StackOverflowError：栈快照含 1024 帧，顶部帧: com.campusflow.lab.jvm.memory.StackOverflowLab.recurse(StackOverflowLab.java:107)
main 线程（栈大小由 -Xss 决定）            最大递归深度 = 16435 层
  [stackSize=0KB] StackOverflowError，快照 1024 帧，首帧: com.campusflow.lab.jvm.memory.StackOverflowLab.recurse(StackOverflowLab.java:107)
子线程 stackSize=0（JVM 默认）          最大递归深度 = 17875 层
  [stackSize=256KB] StackOverflowError，快照 1024 帧，首帧: com.campusflow.lab.jvm.memory.StackOverflowLab.recurse(StackOverflowLab.java:107)
子线程 stackSize=256KB              最大递归深度 = 3833 层
  [stackSize=4096KB] StackOverflowError，快照 1024 帧，首帧: com.campusflow.lab.jvm.memory.StackOverflowLab.recurse(StackOverflowLab.java:107)
子线程 stackSize=4MB                最大递归深度 = 74025 层
```

加 `-Xss256k` 再跑一次（对比 main 线程深度）：

```text
$ java -Dfile.encoding=UTF-8 -Xss256k -cp target/classes com.campusflow.lab.jvm.memory.StackOverflowLab
== StackOverflowLab：栈溢出复现与 -Xss 影响 ==
当前 JVM 输入参数: [-Dfile.encoding=UTF-8, -Xss256k]

main 线程（栈大小由 -Xss 决定）            最大递归深度 = 2307 层
子线程 stackSize=0（JVM 默认）          最大递归深度 = 3819 层
子线程 stackSize=256KB              最大递归深度 = 3805 层
子线程 stackSize=4MB                最大递归深度 = 74045 层
```

**读数解读**：
- 默认堆栈下 main=16435 层 vs 256KB 线程=3833 层 vs 4MB 线程=74025 层——深度与栈大小严格近似线性；
- `-Xss256k` 时 main 线程深度从 16435 骤降到 2307，但显式传 stackSize 的子线程不受 -Xss 影响
  （256KB 线程两次都在 3800 左右）——证明 **-Xss 只是"默认值"，Thread(stackSize) 参数可按线程覆盖**；
- `stackSize=0` 的子线程跟随 -Xss 变化（17875 → 3819），语义即"使用 JVM 默认"；
- 栈快照固定 1024 帧：JDK 限制 `MaxJavaStackTraceDepth` 默认 1024，SOE 时栈帧数远超此值。

### 4.4 预期现象与解读

- 默认 1m 栈（64 位 Windows HotSpot 默认约 1MB）递归深度约 1 万~2 万层（帧越大越少）；
- 256KB 栈的深度约为默认栈的 1/4，4MB 栈约为 4 倍——**深度与栈大小近似线性**；
- catch SOE 后 `e.getStackTrace()` 可能长度为 0：SOE 展开过程中 JIT 可能丢弃栈帧信息。

---

## 五、实验 3：MetaspaceLab（元空间溢出）

### 5.1 源码要点（纯 JDK 手写字节码，不依赖 ASM/Javassist）

```java
// 最小合法 .class：常量池 4 项 + 类头，无字段无方法，版本号 52 (Java 8)
buf.putInt(0xCAFEBABE);            // magic
buf.putShort((short) 0);           // minor_version
buf.putShort((short) 52);          // major_version
buf.putShort((short) 5);           // constant_pool_count = 4 项 + 1
// #1 Class->#2 ; #2 Utf8(类名) ; #3 Class->#4 ; #4 Utf8("java/lang/Object")
...
class LeakingClassLoader extends ClassLoader {
    Class<?> loadGeneratedClass(String binaryName) {
        byte[] bytes = minimalClassBytes(binaryName.replace('.', '/'));
        Class<?> c = defineClass(binaryName, bytes, 0, bytes.length);
        Class.forName(binaryName, true, this);   // 触发链接+初始化，元数据真正写入元空间
        return c;
    }
}
```

1. 为什么不能"同一份字节码反复 defineClass 不同类名"？
   `defineClass` 校验字节码常量池内的类名必须与传入 name 一致，否则抛
   `NoClassDefFoundError: wrong name`。所以按目标类名**逐个重新生成**字节码。
   （另一种常见实现是 `javax.tools.JavaCompiler` 编译内存源码字符串，思路相同，速度较慢。）
2. **每个类一个独立 ClassLoader 且全部强引用保留**——这是真实世界元空间泄漏的标准形态
   （Groovy/JS 脚本热更新、动态代理、Tomcat redeploy）；
3. 类卸载三要素（同时满足）：该类所有实例已回收 + 加载它的 ClassLoader 已回收 + Class 对象无引用。
   实验刻意违反第 2 条，所以类元数据永不卸载。

### 5.2 运行命令

```bash
java -XX:MaxMetaspaceSize=32m -cp backend/jvm-lab/target/classes com.campusflow.lab.jvm.memory.MetaspaceLab
```

### 5.3 真实运行输出

```text
$ java -Dfile.encoding=UTF-8 -XX:MaxMetaspaceSize=32m -cp target/classes com.campusflow.lab.jvm.memory.MetaspaceLab
== MetaspaceLab：元空间溢出复现 ==
提示：请用 -XX:MaxMetaspaceSize=32m -cp target/classes com.campusflow.lab.jvm.memory.MetaspaceLab 运行
（JDK8 是 PermGen 永久代 -XX:PermSize/-XX:MaxPermSize；JDK8+ 已改为本地内存的 Metaspace）

---- Metaspace 分析要点 ----
捕获异常     : java.lang.OutOfMemoryError: Metaspace
已加载类数量 : 5373
1) JDK8+ 没有永久代：类元数据在 Metaspace（本地内存/堆外），字符串常量池与静态变量在堆；
2) 触发条件：defineClass 的类元数据总量超过 MaxMetaspaceSize（或压缩类指针的 CompressedClassSpaceSize）；
3) 典型泄漏源：动态代理/ASM/CGLIB、Groovy/JS 脚本引擎热更新、大量 ClassLoader 反复加载、
   redeploy 后旧 ClassLoader 被 ThreadLocal/静态字段持有而无法整体卸载；
4) 类卸载条件（三要素同时满足）：该类所有实例已回收 + 加载它的 ClassLoader 已回收 +
   对应 java.lang.Class 对象无引用；本实验刻意保留 loader 强引用，因此永远不会卸载；
5) 诊断：jcmd <pid> GC.class_histogram 看类数量；jcmd <pid> VM.metaspace 看元空间细分；
   -Xlog:gc+metaspace=trace 可观察元空间分配与卸载。
```

**真实踩坑记录（第三次"catch 里分配"教训，且更有意思）：**
初版 catch 块中拼接字符串 `e.getClass().getName() + ": " + e.getMessage()`，
真实输出是第二次 Metaspace OOM 直接冲出 catch、main 退出（exit=1）：

```text
Exception in thread "main" java.lang.OutOfMemoryError: Metaspace
	at java.base/java.lang.ClassLoader.defineClass0(Native Method)
	at java.base/java.lang.System$2.defineClass(System.java:2324)
	...
	at java.base/java.lang.invoke.StringConcatFactory.makeConcatWithConstants(StringConcatFactory.java:358)
	...
	at com.campusflow.lab.jvm.memory.MetaspaceLab.generateUntilOom(MetaspaceLab.java:98)
```

注意栈里的 **StringConcatFactory.defineClass0**：JDK9+ 的字符串 `+` 编译为 invokedynamic，
首次执行时 StringConcatFactory 要**动态生成拼接方法类**并 defineClass —— 这也占元空间！
元空间打满后连字符串拼接都无法引导，第二次 OOM 从 catch 内炸出。
（修复：catch 内只置标志，释放 loader 引用后再构造描述。）

同时 32m 元空间下约 5373 个"极小类 + 独立 loader"就触顶，
可推算每个"类 + loader + CLD(ClassLoaderData)"开销约 6KB —— 真实动态类远比这个大，
几千个 Groovy 脚本类就能打爆默认配置，这就是元空间必须设上限的原因。

### 5.4 预期现象与解读

- `-XX:MaxMetaspaceSize=32m` 下数秒内抛 `OutOfMemoryError: Metaspace`，
  加载的类数量约数千个（每个极小类 + 独立 loader 的元数据开销合计数 KB）；
- 不设上限时默认几乎无界（受物理内存约束），这就是"元空间泄漏比永久代更隐蔽"的原因——
  永久代 -XX:MaxPermSize 默认有上限反而更早暴露；
- 诊断：`jcmd <pid> GC.class_histogram`（类数量飙升）、`jcmd <pid> VM.metaspace`（细分占用）、
  `-Xlog:gc+metaspace=trace`（分配/卸载明细）。

---

## 六、GC 算法基础（三实验共同原理）

1. **可达性分析（Reachability）**：从 GC Roots（虚拟机栈帧引用、静态字段、常量、
   JNI 引用、活跃线程、同步锁持有的对象等）出发，沿引用链遍历；
   不可达对象即可回收。Java 用的是可达性分析，不是"引用计数"
   （引用计数解决不了循环引用）。
2. **标记-清除（Mark-Sweep）**：简单，但产生内存碎片，清除效率低；
3. **标记-复制（Mark-Copy）**：Young 区标配（Eden:S0:S1 = 8:1:1），
   无碎片但浪费一半空间——适合"朝生夕死"对象占比 98% 的场景；
4. **标记-整理（Mark-Compact）**：老年代常见，无碎片但移动对象成本高；
5. **分代假说**：绝大多数对象朝生夕死；熬过多次 Young GC 的对象更可能长命——
   这是新生代/老年代划分和晋升机制的理论基础。

三个实验里：HeapOverflow 的 byte[] 全部是强可达活对象（GC 无能为力）；
StackOverflow 与 GC 无关（栈不参与回收）；Metaspace 的类元数据因为 loader 强引用
而始终被 GC Root 间接可达（loader 列表是静态集合 → GC Root），因此永不卸载。

---

## 七、常见面试追问与参考答案

**Q1：JDK8 之后方法区去哪了？PermGen 为什么被移除？**
A：方法区是逻辑概念，JDK8 起实现为本地内存中的 Metaspace。移除 PermGen 的原因：
(1) PermGen 大小固定（-XX:MaxPermSize），动态类生成多的应用容易 OOM，且大小难估；
(2) PermGen 与堆共用 GC 框架、Full GC 才回收，回收效率低且复杂；
(3) JRockit 与 HotSpot 合并时 JRockit 本就没有永久代，统一架构。
代价是需要自己管 -XX:MaxMetaspaceSize 防止本地内存被吃光。

**Q2：OutOfMemoryError 都是堆满了吗？列举至少 5 种 OOM。**
A：不是。常见：`Java heap space`（堆活对象装不下）、`GC overhead limit exceeded`
（98% 时间 GC 只回收 <2%）、`Metaspace`（类元数据超限）、
`unable to create new native thread`（线程数/虚拟内存/内核限制，非堆问题）、
`Direct buffer memory`（直接内存超限）、`Requested array size exceeds VM limit`
（数组长度超限）。前两种通常调堆或修泄漏，第三种查类泄漏，
第四种查线程创建失控或容器内存配额，最后两种查原生内存。

**Q3：栈溢出怎么定位、-Xss 调大行不行？**
A：看异常栈顶帧定位递归入口（`-Xss` 调大只是缓解，深度线性增大，问题依旧）。
根治：修递归出口；无法避免的深递归改成显式栈迭代；
序列化循环引用用自定义序列化或 @JsonManagedReference/@JsonBackReference 打断。
注意 -Xss 越大单线程占用越多，高并发下反而更早 "unable to create new native thread"。

**Q4：如何判断是内存泄漏还是内存不够？**
A：拿 2~3 个间隔的 heap dump（jcmd GC.heap_dump）对比：
如果老年代占用在两次 Full GC 后持续抬升且 MAT 支配树里出现持续增长的同一集合/类 → 泄漏；
如果 dump 里活跃对象本来就与业务量匹配、Full GC 后能回落 → 真实流量大，调堆或加节点。
辅助指标：Full GC 后 old 区回升斜率、`jstat -gcutil` 的 O 列走势。

**Q5：对象一定分配在堆上吗？**
A：不一定。逃逸分析（EA）开启时，不逃逸的对象可栈上分配（标量替换成局部变量）、
无同步消除（锁消除）、TLAB 快速分配也在堆内私有缓冲完成。
大对象/未通过 EA 的对象进堆（G1 下 >Region 一半的算 humongous 直接进 Old Region）。

**Q6：字符串常量池在哪？`String s = new String("ab")` 创建几个对象？**
A：JDK7+ 字符串常量池在堆中（StringTable 是哈希表，不是独立内存区）；
`new String("ab")`："ab" 字面量首次出现时在常量池建一个 String 对象，
`new` 再在堆建一个 => 最多 2 个对象、返回堆里那个。`intern()` 把堆实例引用放入/返回池中引用，
JDK7 起 intern 可复用堆对象引用。

**Q7：什么是内存屏障级别的"类卸载三条件"？验证过吗？**
A：类卸载需同时满足：① 该类全部实例已不可达；② 加载它的 ClassLoader 已不可达；
③ 对应 java.lang.Class 对象无引用。MetaspaceLab 通过保留 loader 强引用使条件②恒不成立，
配合 `jcmd GC.class_histogram` 可看到类数量只增不减，从而稳定复现 Metaspace OOM。
