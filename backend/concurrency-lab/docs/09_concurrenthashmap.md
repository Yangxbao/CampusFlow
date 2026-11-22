# 09 ConcurrentHashMap 实验：并发 put、computeIfAbsent 原子性与 HashMap 对比

> 源码：`src/main/java/com/campusflow/lab/concurrent/concurrenthashmap/ConcurrentHashMapLab.java`
> 测试：`src/test/java/com/campusflow/lab/concurrent/concurrenthashmap/ConcurrentHashMapLabTest.java`

## 一、实验目标

1. 验证 `ConcurrentHashMap` 并发 put 的安全性：8 线程 × 2000 条不重复 key，size 恰好 16000；
2. 验证 `computeIfAbsent` 的原子性：8 线程竞争同一 key，mappingFunction 只执行一次；
3. 对比 `HashMap` 并发 put 的**丢数据**现象（JDK8 真实表现）；
4. 文字说明 JDK7 HashMap 并发扩容链表成环导致 CPU 100% 的历史问题（不实际复现，避免测试挂死）。

## 二、完整源码要点

```java
static final int THREADS = 8;
static final int PER_THREAD = 2000;

// 1. CHM 并发 put：每个线程写入自己的不重复 key 段 [base, base+PER_THREAD)
ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
map.put(base + i, i);

// 2. computeIfAbsent 原子性：8 线程同时竞争同一个 "hot-key"
cache.computeIfAbsent("hot-key", k -> {
    invocations.incrementAndGet();   // 统计 mappingFunction 被执行的次数
    LabKit.sleep(100);               // 模拟昂贵的构建过程
    return "expensive-result";
});

// 3. HashMap 对照组：同样的并发 put，统计每轮最终 size
HashMap<Integer, Integer> hm = new HashMap<>();
// ... 并发 put 后：size < 16000，丢失数千条
```

## 三、运行命令

```bash
export JAVA_HOME="D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"
mvn -pl backend/concurrency-lab compile

java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.concurrenthashmap.ConcurrentHashMapLab

mvn -pl backend/concurrency-lab test
```

## 四、预期现象

- CHM：8 线程 × 2000 条并发 put 后 size == 16000，一条不丢；
- computeIfAbsent：8 个线程都拿到 `expensive-result`，但 mappingFunction **调用次数 = 1**；
- HashMap：每轮 size 都远低于 16000（示例丢了 4158/6570/5017 条），偶发并发异常；
- 单线程 HashMap 一切正常（对照组），说明问题出在"并发使用"。

## 五、原理分析

- **JDK7 分段锁**：CHM 由 Segment（继承 ReentrantLock）数组组成，锁粒度是"段"（默认 16 段）。
  并发度 = 段数，写不同段不互斥。
- **JDK8 CAS + synchronized 桶头**：去掉 Segment。`putVal` 逻辑：
  1. 桶为空 → **CAS** 直接插入（无锁快速路径）；
  2. 桶头是 ForwardingNode（扩容中）→ 协助迁移 `helpTransfer`；
  3. 否则 **synchronized(f) 锁桶头**，在链表/红黑树上插入。
  锁粒度从"段"细化到"桶"，冲突概率进一步降低。`size()` 用 baseCount + CounterCell[] 分段计数
  （思路同 LongAdder），`sumCount` 汇总——size 是"弱一致"的。
- **computeIfAbsent 为什么能防缓存击穿**：JDK8 实现中该 key 所在桶被锁住后才开始计算
  mappingFunction；其他线程对同一 key 的调用会**阻塞等待**计算完成并复用结果，不会重复加载。
  注意两点：mappingFunction 内不要再修改同一个 map（可能死锁或 IllegalStateException）；
  JDK9+ 对存在但映射到 null 的行为有差异，读文档再依赖。
- **HashMap 并发问题的两代版本**：
  - JDK7：`transfer` 头插法迁移链表，并发 resize 使两个线程互相指接形成**环**，后续 get 落到该桶
    死循环，CPU 100%（线上经典事故）。JDK8 改为尾插，不再成环。
  - JDK8：**仍然不是线程安全的**——`putVal` 无锁，两个线程写同一空桶互相覆盖、并发 resize
    搬移丢节点，表现为丢数据（本实验 8×2000 丢数千条）甚至树化竞态异常。
  结论：任何版本的 HashMap 都禁止并发写；读多写少的只读场景（初始化完成后不再写）才可共享。

## 六、实际输出示例（真实运行，JDK 17.0.21.1 / Windows）

```text
==================== 1. ConcurrentHashMap 并发 put：8 线程 x 2000 条不重复 key ====================
期望条数 = 16000，实际 size = 16000

==================== 2. computeIfAbsent 原子性：8 线程并发竞争同一个缓存 key ====================
[cacher-4] mappingFunction 开始执行（昂贵的构建过程 100ms）
[cacher-5] 拿到缓存值 expensive-result
[cacher-3] 拿到缓存值 expensive-result
[cacher-1] 拿到缓存值 expensive-result
[cacher-7] 拿到缓存值 expensive-result
[cacher-2] 拿到缓存值 expensive-result
[cacher-4] 拿到缓存值 expensive-result
[cacher-6] 拿到缓存值 expensive-result
[cacher-0] 拿到缓存值 expensive-result
mappingFunction 调用次数 = 1（期望 1 —— computeIfAbsent 对同一 key 的加载是原子的，天然防缓存击穿）

==================== 3. 对比：HashMap 并发 put（8 线程 x 2000 条，共 3 轮） ====================
第 1 轮: 期望 16000 条，实际 11842 条，丢失 4158 条 <-- 丢数据！
第 2 轮: 期望 16000 条，实际 9430 条，丢失 6570 条 <-- 丢数据！
第 3 轮: 期望 16000 条，实际 10983 条，丢失 5017 条 <-- 丢数据！
```

## 七、常见面试追问

**1. ConcurrentHashMap 在 JDK7 和 JDK8 的实现差异？**
JDK7：Segment 分段锁（默认 16 段，继承 ReentrantLock），锁粒度=段；JDK8：CAS+synchronized，
空桶 CAS 插入、非空桶锁桶头，锁粒度=桶，并发度更高；同时引入红黑树、size 用 CounterCell 分段计数。

**2. JDK8 ConcurrentHashMap 的 get 需要加锁吗？为什么？**
不需要。Node 的 val 和 next 都是 volatile，get 沿着 volatile 读即可看到最新链；Table 数组元素
用 `volatile Node<K,V>[] tab` + Unsafe 读保证可见性。读完全无锁，写才需要锁/CAS。

**3. computeIfAbsent 为什么能防缓存击穿？使用时要注意什么？**
同一 key 的加载在"锁住桶"后原子执行，其他线程等待结果而非重复加载，天然单飞。
注意：mappingFunction 内不能再写同一个 map（可能自死锁）；计算要快（持锁执行，阻塞其他同桶操作）；
value 为 null 的语义在各版本有差异。

**4. HashMap 并发使用会有什么后果？JDK7 和 JDK8 一样吗？**
JDK7：并发扩容头插法成环，get 死循环 CPU 100%；JDK8：尾插不成环，但仍会丢数据、覆盖、
偶发 ClassCastException/树化竞态。两个版本都不允许并发写。

**5. ConcurrentHashMap 的 size 是精确的吗？**
不是强一致的瞬时精确值：`sumCount` 汇总 baseCount + CounterCell[]，统计期间并发写仍在发生，
结果弱一致。需要精确快照要自己加锁或用其他结构。

**6. 为什么 CHM 不允许 null key/value？**
歧义问题：`get(key)` 返回 null 无法区分"key 不存在"和"映射到 null"（该类用于并发环境，
containsKey 与 get 两次调用之间状态可能已变）；HashMap 单线程可用 containsKey 消歧，并发下
不可能，索性禁止 null。

**7. 还有哪些并发容器？和 CHM 什么关系？**
CopyOnWriteArrayList/CopyOnWriteArraySet（读多写少）、ConcurrentLinkedQueue（无锁 CAS 队列）、
ConcurrentSkipListMap（跳表，有序+并发，替代 TreeMap）、BlockingQueue 家族（生产者消费者）。
Collections.synchronizedMap/Hashtable 是"整表一把锁"的老方案，吞吐远不如 CHM。

## 八、测试说明

`ConcurrentHashMapLabTest` 共 4 个用例（`@Timeout(30~60)`）：CHM 并发 put size==16000、
computeIfAbsent 恰好执行 1 次、HashMap 并发多轮内必然丢数据/异常、单线程 HashMap 数据完整（对照）。
