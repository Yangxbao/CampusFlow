# 03 volatile 实验：可见性、原子性陷阱与 happens-before

> 源码：`src/main/java/com/campusflow/lab/concurrent/visibility/VolatileLab.java`
> 测试：`src/test/java/com/campusflow/lab/concurrent/visibility/VolatileLabTest.java`

## 一、实验目标

1. 复现**可见性问题**：普通 boolean 标志位被写线程修改后，自旋读线程长时间"看不到"；
2. 验证 **volatile 能立即解决可见性**（0ms 观察到变化）；
3. 复现 **volatile 不保证原子性**：2 线程 × 10 万次 `count++` 丢失数万次更新；
4. 演示 **happens-before 的"安全发布"**：volatile 写发布的普通变量对看到该标志的线程必然可见。

## 二、完整源码要点

可见性问题（读线程带 deadline 保证实验可自动结束）：

```java
private static boolean normalFlag = false;      // 普通标志位

Thread reader = new Thread(() -> {
    while (System.currentTimeMillis() < deadline) {
        for (int i = 0; i < 100_000; i++) {     // 内层纯自旋
            if (normalFlag) { return; }          // JIT(OSR) 会把这个读提升出内层循环
            spins++;
        }
    }
}, "visibility-reader");
reader.start();
sleep(300);
normalFlag = true;   // main 写入后，reader 依然自旋到超时 —— 问题复现
```

volatile 修复：把 `normalFlag` 换成 `volatile boolean volatileFlag`，读线程立即退出。

volatile 不保证原子性：

```java
private static volatile int counter = 0;
// 2 个线程各执行 100_000 次 counter++ —— 读-改-写三步交错，仍丢更新
```

happens-before 安全发布：

```java
payload = 42;      // ① 普通写
published = true;  // ② volatile 写：① happens-before ②（程序顺序规则）
// 读线程：看到 published == true 后读 payload，必然是 42（volatile 规则传递）
```

## 三、运行命令

```bash
export JAVA_HOME="D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"
mvn -pl backend/concurrency-lab compile

java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.visibility.VolatileLab

mvn -pl backend/concurrency-lab test
```

## 四、预期现象

- 场景 1：main 设置 `normalFlag=true` 后，读线程**继续空转约 1200ms**（窗口剩余时间）才退出，
  自旋次数达数百亿次 —— 问题复现。极少数情况下 JIT 未优化会"本次未复现"，多跑几次即可；
- 场景 2：volatile 版本 0ms 内观察到变化；
- 场景 3：期望 200000，实际通常 13 万~19 万（丢失数万次更新）；
- 场景 4：读线程读到 payload = 42，永远不会读到 0。

## 五、原理分析

- **可见性问题的两个来源**：
  1. JMM 允许编译器/处理器优化普通变量读取：字段读被提升（hoisting）出循环、寄存器缓存；
  2. 硬件层面每核私有缓存/写缓冲，无同步指令时不保证立即传播。
  volatile 同时掐断两条路：JIT 禁止把 volatile 读提升出循环；字节码层面在读volatile 变量后插入
  `LoadLoad/LoadStore` 屏障（x86 上体现为 `lock` 前缀指令或等价语义），强制从主存/缓存一致性协议
  （MESI）取最新值。
- **为什么 volatile 不保证原子性**：`count++` 反编译为 `getfield / iadd / putfield` 三步。两个线程
  同时读到 100，各加一再写回，结果 101 —— 丢失一次更新。volatile 只保证"读到的值是最新"与"写立即
  可见"，不保证"读改写"序列的原子性。原子性要用 `AtomicInteger`（CAS）或锁。
- **happens-before 规则（JMM 核心）**：程序顺序规则（单线程内按代码顺序）、volatile 规则（volatile 写
  hb 后续对同一变量的读）、monitor 规则、start/join 规则、传递性。本实验：`payload=42` hb
  `published=true`（程序顺序），`published=true` hb 读线程看到 `published==true`（volatile），
  传递后 `payload=42` hb 读 payload —— 所以读线程不可能读到 0。这就是"volatile 开关 + 普通数据"
  的安全发布模式，DCL 单例、shutdown 标志都是这个用法。
- **volatile 的典型误用**：当作原子计数器（本实验场景 3）；依赖多变量复合不变量（volatile 只能
  单点保证）。volatile 适用场景：状态标志（一写多读）、一次性安全发布、DCL。

## 六、实际输出示例（真实运行，JDK 17.0.21.1 / Windows）

```text
==================== 1. 可见性问题复现：普通 boolean 标志位（读线程死循环等待） ====================
[main] 把 normalFlag 置为 true ...
读线程自旋了 86031000000 次，在标志设置后又空转约 1192 ms 才因超时退出 —— 可见性问题复现！

==================== 2. volatile 修复可见性：写线程改 volatile 标志，读线程立即看到 ====================
[main] 把 volatileFlag 置为 true ...
[volatile-reader] 读线程观察到 volatileFlag=true，立即退出循环
读线程 0 ms 内观察到变化 -> true

==================== 3. volatile 不保证原子性：2 线程 x 100000 次 count++ ====================
期望 200000，实际 168241，丢失 31759 次更新！volatile 保证了可见性，但 count++ 仍是三步非原子操作

==================== 4. happens-before：用 volatile 安全"发布"普通变量 ====================
[hb-writer] payload=42 写入完成
[hb-writer] published=true（volatile 写）
[hb-reader] 看到 published=true，读取 payload=42（保证是 42，不会读到 0）
发布演示最终读到 payload = 42
```

## 七、常见面试追问

**1. volatile 的两个语义是什么？底层如何实现？**
可见性（写立即刷出、读绕过缓存取最新）+ 有序性（禁止指令重排：写前 StoreStore、写后 StoreLoad、
读后 LoadLoad/LoadStore 屏障）。JVM 层面插入内存屏障，x86 上 volatile 写带 `lock` 前缀指令；
volatile 读禁止被 JIT 提升出循环。

**2. volatile 能保证原子性吗？为什么 count++ 不行？**
不能。count++ 是读-改-写三步操作（getfield/iadd/putfield），volatile 只保证每一步读到最新值，
但两线程交错仍丢失更新。需要 `AtomicInteger`/`LongAdder` 或锁。

**3. 说说 happens-before 是什么，列出常用规则。**
happens-before 是 JMM 定义的跨线程操作可见性偏序关系（不是时间先后）。常用规则：程序顺序、
monitor 锁解锁 hb 后续加锁、volatile 写 hb 后续读、线程 start、线程 join、线程 interrupt、
final 构造规则、传递性。两个操作没有 hb 关系则可能出现重排可见性问题。

**4. i++ / count++ 到底几步操作？用 javap 怎么证明？**
三步：读字段（getfield）、自增（iadd）、写回（putfield）。`javap -c` 可见。多线程不同步即竞态；
volatile 加不加都一样，必须 AtomicXxx 或锁。

**5. volatile 适用哪些场景？给出反例。**
适用：一写多读的状态标志（shutdown flag）、一次性发布（配置/上下文对象）、DCL 单例的实例字段。
反例：计数器（非原子）、复合不变量（`low < high` 两个字段都 volatile 也保证不了组合约束）。

**6. 双重检查锁定（DCL）为什么必须 volatile？**
`instance = new Singleton()` 不是原子的：分配内存→构造→赋值引用，2/3 可能重排，别的线程可能拿到
"已赋值但未构造完成"的引用。volatile 禁止该重排，保证读到引用时对象已初始化。

**7. 普通变量的可见性问题一定发生吗？**
不一定。JIT 是否提升读、CPU 缓存何时同步都是实现细节，实验中如果 JIT 未优化就"复现失败"——
这正是 JMM 的意义：**不依赖实现细节，只依赖语义保证**。写代码要按 JMM 保证写，而不是赌现象。

## 八、测试说明

`VolatileLabTest` 共 3 个用例：volatile 可见性（读线程在 2s 内观察到，断言 true）、volatile count++
丢失更新（断言丢失次数 > 0）、happens-before 发布（断言读到 42）。全部 `@Timeout` 保护，
读线程自旋带 deadline，测试不可能挂死。普通标志位的"不可见"现象受 JIT 影响，按"尽力复现"处理，
不对它做失败断言。
