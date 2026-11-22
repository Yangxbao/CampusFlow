# 04 CAS 实验：AtomicInteger、ABA 问题与 LongAdder

> 源码：`src/main/java/com/campusflow/lab/concurrent/cas/CasAbaLab.java`
> 测试：`src/test/java/com/campusflow/lab/concurrent/cas/CasAbaLabTest.java`

## 一、实验目标

1. 掌握 `AtomicInteger` 的 CAS 用法（`incrementAndGet` / `compareAndSet` 的成功与失败路径）；
2. 复现 **ABA 问题**：值经历 A→B→A，普通 CAS 依然成功；
3. 用 `AtomicStampedReference`（引用+版本戳）正确感知 ABA；
4. 用 `AtomicReference` 手写 **CAS 自旋累加器**，理解原子类的实现套路；
5. 对比高竞争下 **LongAdder 与 AtomicLong** 的性能差异。

## 二、完整源码要点

CAS 基本用法：

```java
AtomicInteger value = new AtomicInteger(100);
value.incrementAndGet();            // 内部 CAS 自旋
value.compareAndSet(101, 200);      // 期望 101 -> 改 200，成功
value.compareAndSet(101, 999);      // 期望不匹配 -> false
```

ABA 复现（两个真实线程，用 sleep 错开时序）：

```java
AtomicReference<String> ref = new AtomicReference<>("A");
// t1：读到 A 后"去忙 300ms"，回来执行 CAS(A→C)
// t2：期间完成 A→B、B→A
// 结果：t1 的 CAS(A→C) = true —— 中间变化完全无感知
```

版本戳方案：

```java
AtomicStampedReference<String> sref = new AtomicStampedReference<>("A", 1);
sref.compareAndSet("A", "B", 1, 2);   // 引用+版本一起比较
sref.compareAndSet("B", "A", 2, 3);
// t1 用旧版本号 CAS：compareAndSet("A", "C", 1, 2) -> false，正确感知 ABA
```

CAS 自旋累加器（原子类的核心套路）：

```java
AtomicReference<Long> holder = new AtomicReference<>(0L);
while (true) {
    Long cur = holder.get();
    if (holder.compareAndSet(cur, cur + 1)) break;  // 失败就重读重试
}
```

LongAdder vs AtomicLong 基准：4 线程 × 100 万次累加 × 3 轮取最好。

## 三、运行命令

```bash
export JAVA_HOME="D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"
mvn -pl backend/concurrency-lab compile

java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.cas.CasAbaLab

mvn -pl backend/concurrency-lab test
```

## 四、预期现象

- `compareAndSet(101, 999)` 因期望值不匹配返回 false；
- ABA 复现：t1 的 CAS 成功，当前值 C，完全感知不到 A→B→A；
- AtomicStampedReference 用旧版本 CAS 失败，用最新版本（A, 3）成功；
- CAS 自旋累加 2×1000 次，最终值恰好 2000（无丢失）；
- LongAdder 明显快于 AtomicLong（本机 3~16ms vs 44~52ms，倍数随机器/竞争度浮动）。

## 五、原理分析

- **CAS 的硬件基础**：`sun.misc.Unsafe`/`VarHandle` 的 compareAndSwap 最终编译为 CPU 原子指令
  （x86 `lock cmpxchg`），对"缓存行"级别保证读-改-写的原子性。JDK9 后推荐 `VarHandle`。
- **原子类 = volatile 语义 + CAS 自旋**。`AtomicInteger.incrementAndGet()` 即
  `do { old = get(); } while (!compareAndSet(old, old+1))`。失败重试在低竞争时几乎一次成功，
  高竞争时大量线程反复失败自旋——这是 AtomicLong 的瓶颈。
- **ABA 问题**：CAS 只回答"值还等于期望值吗"，不回答"期间变过没有"。对数值累加无害，但对
  **指针/对象复用**场景致命：无锁栈弹栈时节点被回收又复用，CAS 会把旧节点重新挂上；资金账务中
  "扣款→退款→余额回到原值"会被误判为未发生。解决方案：
  `AtomicStampedReference`（int 版本戳）或 `AtomicMarkableReference`（boolean 标记）；
  数据库层面用 version 乐观锁同理。
- **LongAdder 为什么快**：内部维护 `base + Cell[]`。写入时先试 CAS base，失败（说明有竞争）则
  哈希分散到不同 Cell 上各自累加，把"一个热点变量"摊成"多段变量"；`sum()` 时汇总 base+各 Cell。
  代价是内存占用增加、`sum` 不是强一致的瞬时快照（无锁读期间可能有并发写）。
  AtomicLong 恰好相反：始终单点精确、拿得到返回值，但高竞争自旋重试多。
- **选型**：统计计数（读少写多、允许 sum 近似）用 LongAdder；需要"基于返回值做下一步"（ID 生成、
  状态机比较）用 AtomicLong；两者都只适合单变量。

## 六、实际输出示例（真实运行，JDK 17.0.21.1 / Windows）

```text
==================== 1. AtomicInteger：CAS 的基本用法 ====================
初始值 = 100
incrementAndGet() = 101（CAS 自旋实现，线程安全）
compareAndSet(101, 200) = true，当前值 = 200
compareAndSet(101, 999) = false（期望值不匹配，CAS 失败），当前值 = 200

==================== 2. ABA 复现：值经历 A→B→A，普通 CAS 照样成功 ====================
[aba-t1] t1 读到当前值 A，随后去忙别的事 300ms...
[aba-t2] t2 完成 A→B
[aba-t2] t2 完成 B→A（对 t1 来说"好像什么都没发生"）
[aba-t1] t1 回来执行 CAS(A→C) = true，当前值 = C —— 值其实经历过 A→B→A，但 CAS 仍然成功！

==================== 3. AtomicStampedReference：引用+版本戳，正确感知 ABA ====================
[stamped-t1] t1 读到 A（版本=1），去忙别的事 300ms...
[stamped-t2] t2 完成 A/1 → B/2
[stamped-t2] t2 完成 B/2 → A/3（值回到 A，但版本戳已变为 3）
[stamped-t1] t1 用旧版本 1 执行 CAS(A→C) = false（版本已变，正确失败！）当前 = A（版本=3）
持最新版本 3 再 CAS(A→D) = true —— 只有拿着最新版本戳才能修改成功。

==================== 4. AtomicReference CAS 自旋：手写"原子累加器" ====================
2 线程 x 1000 次 CAS 自旋累加，最终 = 2000

==================== 5. LongAdder vs AtomicLong：4 线程 x 1,000,000 次累加（3 轮取最好） ====================
AtomicLong: 结果=4000000, 耗时=52 ms
LongAdder : 结果=4000000, 耗时=16 ms
AtomicLong: 结果=4000000, 耗时=46 ms
LongAdder : 结果=4000000, 耗时=9 ms
AtomicLong: 结果=4000000, 耗时=44 ms
LongAdder : 结果=4000000, 耗时=3 ms
最好成绩对比: AtomicLong=44 ms, LongAdder=3 ms —— 高竞争下 LongAdder 通过"分段累加+sum 汇总"避免单点自旋竞争
```

## 七、常见面试追问

**1. CAS 的原理是什么？有什么缺点？**
Compare-And-Swap：比较内存值与期望值，相等则原子地更新为新值，底层是 `lock cmpxchg` 等 CPU 原子指令。
缺点：ABA 问题（需版本戳）；自旋失败重试在长期高竞争下浪费 CPU（升级为 LongAdder 或锁）；
只能保证单个变量的原子性（多变量组合仍需锁或封装成不可变对象+引用）。

**2. 什么是 ABA 问题？如何解决？**
线程 1 读到 A 后挂起；线程 2 把值改为 B 再改回 A；线程 1 恢复后 CAS(A→C) 成功——它无法感知中间变化。
对链表/栈指针、资金流水等场景可能造成逻辑错误。解决：`AtomicStampedReference` 版本戳、
`AtomicMarkableReference` 标记位、或改用锁/不可变设计；数据库乐观锁用 version 字段同理。

**3. AtomicLong 和 LongAdder 的区别与选型？**
AtomicLong：单变量、每次操作返回精确结果（incrementAndGet 的返回值）、高竞争下自旋重试多；
LongAdder：base+Cell 分段累加，写性能高、`sum()` 汇总（非原子瞬时快照），拿不到单次递增返回值。
选型：QPS 统计、指标计数选 LongAdder；全局唯一 ID、需要返回值的原子推进选 AtomicLong。

**4. AtomicInteger.incrementAndGet 在 JDK8 与 JDK17 中实现有区别吗？**
语义一致（CAS 自旋）。JDK8 用 `Unsafe.getAndAddInt` 自旋；JDK9+ 内部逐步迁移到
`VarHandle`/`jdk.internal.misc.Unsafe`，字节码指令与屏障语义由 JVM 保证，对外行为不变。

**5. CAS 失败的线程会不会被挂起？**
不会。CAS 是乐观策略，失败者立即重试（自旋/忙等），不进等待队列、不阻塞，因此无上下文切换开销；
但这也意味着长时间拿不到就白白烧 CPU。悲观策略（锁）则挂起线程让出 CPU。乐观锁适合短临界区、
低-中竞争；长临界区或高竞争用锁更划算。

**6. 为什么 AtomicReference 要求对象引用不可变/无状态？**
CAS 比较的是引用是否相同。若对象内部状态可变，"引用没变"不代表"内容没变"，会复现 ABA 类问题。
所以 AtomicReference 配合不可变对象（record/值对象）使用才安全——想改就"复制出新对象再 CAS 替换"。

**7. LongAdder.sum() 得到的值是精确的吗？**
是"某一时刻的近似汇总"：sum 遍历 base+各 Cell 期间没有加锁，并发写仍在发生，读到的可能是中间状态。
需要精确瞬时值就得加锁或用 AtomicLong。这也是"分段累加换写吞吐"的必然取舍。

## 八、测试说明

`CasAbaLabTest` 共 4 个用例（`@Timeout(30~60)`）：普通 AtomicReference 无法感知 ABA（断言 CAS 成功）、
AtomicStampedReference 检测 ABA（旧版本失败/新版本成功）、CAS 自旋累加 2000 精确、
AtomicLong/LongAdder 各 4×20 万次累加结果精确 80 万。
