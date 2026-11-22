# 10 竞态条件实验：count++ 丢失更新与三种修复

> 源码：`src/main/java/com/campusflow/lab/concurrent/counter/RaceConditionLab.java`
> 测试：`src/test/java/com/campusflow/lab/concurrent/counter/RaceConditionLabTest.java`

## 一、实验目标

1. **复现丢失更新**：两线程同时对普通 `int` 做 `count++`，观察结果小于期望；
2. 用 **synchronized / AtomicLong / LongAdder** 三种方案修复，验证结果精确；
3. 对比三种方案在 4 线程 × 50 万次递增下的耗时，建立选型直觉；
4. 理解"读-改-写"非原子的根源（字节码与 JMM 层面）。

## 二、完整源码要点

```java
private static int unsafeCount = 0;          // 无同步
private static int syncedCount = 0;          // synchronized 保护
private static final Object LOCK = new Object();

// 1. 竞态复现：2 线程 x 100000 次（带启动闸门 CountDownLatch 保证真正并发）
for (int i = 0; i < 100_000; i++) {
    unsafeCount++;                            // getfield/iadd/putfield 三步非原子
}

// 2. 三种修复
synchronized (LOCK) { syncedCount++; }        // 悲观互斥
atomic.incrementAndGet();                     // CAS 自旋（乐观）
adder.increment();                            // 分段累加（base + Cell[]）
```

耗时对比使用 4 线程 × 500000 次，全部经 CountDownLatch 启动闸门同时起跑。

## 三、运行命令

```bash
export JAVA_HOME="D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"
mvn -pl backend/concurrency-lab compile

java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.counter.RaceConditionLab

mvn -pl backend/concurrency-lab test
```

## 四、预期现象

- 竞态轮次中结果显著小于 200000（丢数万次更新），偶尔某轮"恰好没丢"（竞态的非确定性）；
- 三种修复结果全部精确等于 2000000；
- 耗时大致呈 `synchronized > AtomicLong > LongAdder`（数值随机器波动，趋势在高竞争下更明显）。

## 五、原理分析

- **丢失更新的机制**：`count++` 编译为 `getfield → iadd → putfield` 三步。线程 A、B 都读到 n，
  各自 +1 后写回，最终只加了一次。这是最典型的 **Check-Then-Act / Read-Modify-Write 竞态**。
  单条字节码指令≠原子，JMM 从不保证普通变量的复合操作原子性。
- **synchronized**：互斥使三步成为临界区序列，正确但代价是加解锁开销 + 竞争失败时线程挂起
  （重量级）或自旋（轻量级），高竞争下上下文切换显著。
- **AtomicLong**：CAS 乐观自旋，无锁无上下文切换，单变量场景最优；但所有线程 CAS 同一变量，
  高竞争时失败重试率飙升（自旋烧 CPU）。`incrementAndGet` 还返回递增后的值，可继续参与判断
  （这是 LongAdder 做不到的）。
- **LongAdder**：`base + Cell[]` 分段。写入先 CAS base，失败说明有竞争，转而哈希到自己的 Cell
  累加；`sum()` 汇总。把"单点热点"摊成"多段并行"，高竞争下写吞吐高一个数量级；代价是内存、
  sum 弱一致、拿不到单次返回值。
- **启动闸门的意义**：如果不用 CountDownLatch，线程是先后启动的，第一段运行几乎没有竞争，
  竞态可能"复现不出来"。闸门让所有线程在同一起跑线开跑，最大化交错概率——并发复现实验的通用技巧。

## 六、实际输出示例（真实运行，JDK 17.0.21.1 / Windows）

```text
==================== 1. 竞态复现：2 线程同时 count++ 各 100000 次（无同步） ====================
第 1 轮: 期望 200000, 实际 144824, 丢失 55176 次 <-- 丢失更新！
第 2 轮: 期望 200000, 实际 186834, 丢失 13166 次 <-- 丢失更新！
第 3 轮: 期望 200000, 实际 200000, 丢失 0 次（本次未丢失，竞态并非每次都发生）

==================== 2. 修复对比：4 线程 x 500,000 次（结果应恒为 2,000,000） ====================
synchronized: 结果=2000000(正确), 耗时=52 ms
AtomicLong  : 结果=2000000(正确), 耗时=32 ms
LongAdder   : 结果=2000000(正确), 耗时=20 ms

========== 观察结论 ==========
1) count++ 在字节码层面是 getfield/iadd/putfield 三步，两线程交错执行必然丢失更新；
   即使在 x86 上底层看似单条指令的表象也改变不了"读-改-写"非原子的事实。
2) 三种修复都保证结果正确：synchronized 简单可靠但有锁开销与阻塞；
   AtomicLong 无锁 CAS，低-中竞争下性能好，但高竞争时大量自旋重试；
   LongAdder 分段累加（Cell[]），写时分散、读时 sum 汇总，高竞争写多读少场景最优。
3) 选型：简单低频用 synchronized；计数统计读少写多用 LongAdder；
   需要"读改写后基于结果做决定"（如 ID 生成器要拿到返回值）用 AtomicLong。
```

注：第 3 轮恰好没有丢失是竞态非确定性的真实体现——"没丢"不代表线程安全，只能说明这一次交错
没撞上。并发正确性必须靠机制保证，不能靠"跑了很多次都没出事"。

## 七、常见面试追问

**1. 为什么两个线程同时 count++ 会少加？说下底层过程。**
count++ 是读-改-写三步（getfield/iadd/putfield）。A 读到 100，B 也读到 100，A 写回 101，B 写回
101——两次加法只生效一次。可 `javap -c` 验证字节码；普通变量无任何原子性保证。

**2. synchronized、AtomicLong、LongAdder 三者怎么选？**
低频简单场景 synchronized（可读、能保护多变量）；需要返回值的单变量原子推进用 AtomicLong；
纯计数统计（监控指标、QPS）用 LongAdder。多变量复合不变量只能锁或封装原子对象。

**3. AtomicLong 高竞争下的问题是什么？LongAdder 如何解决？**
所有线程 CAS 同一个变量，失败者自旋重试，CPU 空转比例飙升。LongAdder 把计数拆成 base+Cell[]
（默认 1 个 Cell，CAS base 失败才扩 Cell，最大为 CPU 核数），写分散、读汇总，用空间和"弱一致
sum"换写吞吐。

**4. volatile 修饰 count 能解决吗？为什么？**
不能。volatile 提供可见性与有序性，不提供原子性；三步操作交错仍丢更新。见 03_visibility 实验。

**5. 竞态一定发生吗？怎么让实验稳定复现？**
不一定，取决于线程交错时序——所以"没出事"不等于"没 bug"。稳定复现手段：CountDownLatch 闸门
同时起跑、提高操作次数、增加线程数、用 CyclicBarrier，甚至人为在"读"与"写"之间插入间隙。

**6. 什么是 Check-Then-Act 竞态？除了计数还有什么例子？**
先检查后动作，两步之间状态可能被其他线程改变。例子：单例的 if(instance==null) 懒加载、
`if(!map.containsKey(k)) map.put(k,v)`（应改用 putIfAbsent/computeIfAbsent）、扣款余额判断后
更新。修复思路都是把"检查+动作"合成一个原子操作或加锁。

**7. 如果要统计接口 QPS，用哪个？要不要精确？**
LongAdder 最合适（多线程高频累加、周期性 sum 输出指标，允许统计窗口内的弱一致）。
要求精确对账才用 AtomicLong/锁。监控指标一般不需要强一致瞬时值。

## 八、测试说明

`RaceConditionLabTest` 共 4 个用例（`@Timeout(60)`）：无同步竞态（多轮内断言丢失更新 > 0）、
synchronized/AtomicLong/LongAdder 三种修复结果精确 200000。
