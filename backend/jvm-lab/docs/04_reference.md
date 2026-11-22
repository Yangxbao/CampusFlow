# 04 · 引用实验讲义：强/软/弱/虚引用与可达性分析（ReferenceLab）

> 模块路径：`backend/jvm-lab`
> 包名：`com.campusflow.lab.jvm.reference`

---

## 一、实验目标

对比四种引用在 GC 下的行为差异，并配合 `System.gc()` 与 `ReferenceQueue` 观察回收与通知机制：
强引用（不回收）、软引用（内存不足才回收）、弱引用（下次 GC 必回收）、虚引用（get 恒 null，仅回收事件通知）。

---

## 二、四种引用与可达性分析（原理）

**可达性分析**：GC 从 GC Roots 出发沿引用链标记。引用强度从高到低：

| 引用 | 声明方式 | 回收时机 | 典型用途 |
|---|---|---|---|
| 强 Strong | `Object o = new Object()` | 永不（可达时） | 普通业务对象 |
| 软 Soft | `new SoftReference<>(obj)` | GC 后内存仍不足时（先于 OOM） | 内存敏感缓存 |
| 弱 Weak | `new WeakReference<>(obj)` | 下一次 GC 必然回收 | WeakHashMap、ThreadLocalMap 的 key |
| 虚 Phantom | `new PhantomReference<>(obj, queue)` | get() 恒 null；回收后入队 | 堆外内存回收联动（DirectByteBuffer/Cleaner） |

**软引用回收策略**：`-XX:SoftRefLRUPolicyMSPerMB=1000`（默认）——
软引用对象在"最后一次可达"后还能存活 `空闲堆MB × 1000ms`；
GC 时按空闲内存决定是否清除，内存越紧张清得越狠。

**ReferenceQueue 机制**：Reference 对象感知到 referent 被回收后，
由 JVM 的 Reference Handler 线程把 Reference 放入队列；
`poll()` 非阻塞，`remove()` 阻塞。软/弱可显式传 queue（不传也能 get），
**虚引用必须传 queue**，且唯一用途就是轮询这个队列。

**堆外内存回收范式**：DirectByteBuffer 的内存不归 GC 管，
JDK 用 Cleaner（PhantomReference 子类）在 ByteBuffer 对象被回收后把
堆外内存清理任务入队，由 Reference Handler 线程执行——Netty 的
`io.netty.util.internal.Cleaner` 与 DirectMemory 泄漏检测同理。

---

## 三、源码要点

```java
// 弱引用：移除强引用后，gc() 循环（gc 只是"建议"，循环增强健壮性）
byte[] payload = new byte[PAYLOAD_SIZE];
WeakReference<byte[]> weak = new WeakReference<>(payload, queue);
payload = null;                       // 移除强引用
boolean cleared = gcUntilCleared(weak, 20);
// 此时 weak.get() == null 且 queue 里收到 weak 本身

// 软引用：内存充足时长期存活；压力测试观察
SoftReference<byte[]> soft = new SoftReference<>(payload);
payload = null;
while (soft.get() != null && rounds < 200) {
    byte[] garbage = new byte[PAYLOAD_SIZE];   // 每轮制造 1MB 垃圾
    rounds++;
}

// 虚引用：必须配 queue，get() 恒 null
PhantomReference<byte[]> phantom = new PhantomReference<>(payload, queue);
// payload = null + System.gc() 后 queue.poll() == phantom
```

`gcUntilCleared` 的健壮性设计：`System.gc()` 只是建议，HotSpot 默认会响应
（`-XX:+DisableExplicitGC` 可禁用，生产慎开——Netty 堆外池依赖它触发 DirectByteBuffer 回收）；
循环里制造少量垃圾促使真实 GC 发生，最多尝试 N 次后放弃，保证测试确定性。

---

## 四、运行命令与真实输出

```bash
java -Dfile.encoding=UTF-8 -Xmx64m -cp target/classes com.campusflow.lab.jvm.reference.ReferenceLab
```

真实输出（JDK17，Temurin 17.0.21.1+2，-Xmx64m）：

```text
== ReferenceLab：四种引用行为对比 ==

[1] 强引用：只要引用还在，System.gc() 也不会回收
    gc() 后对象仍存活（引用还在栈帧里）: true
    还有 notCleared 强引用 => 仍可达，不会被回收（GC Roots 可达性分析）
    教训：静态集合/缓存只增不减 = 制造强引用泄漏，最终 Java heap space OOM

[2] 弱引用 WeakReference：下一次 GC 后必然被回收（无论内存是否充足）
    gc 前 weak.get() != null : true
    移除强引用并 System.gc() 后 weak.get() == null : true（尝试 gc 成功）
    ReferenceQueue 收到回收通知: true

[3] 软引用 SoftReference：内存充足时活得好好的；内存紧张（GC 后仍不足）才回收
    内存充足时 gc() 后 soft.get() != null : true
    制造分配压力 200 轮后 soft.get() == null : false（小堆 -Xmx64m 下通常为 true；大堆下可能仍为 false —— 均属正常现象）
    软引用被回收的时机由 JVM 的空闲内存策略决定，这正是它适合做缓存的原因

[4] 虚引用 PhantomReference：get() 恒为 null，只用于回收事件通知
    phantom.get() = null（永远为 null，连对象活着时也是 null）
    移除强引用 + gc() 后，虚引用进入 ReferenceQueue : true
```

**真实输出的关键观察**：
1. 弱引用与虚引用行为完全确定（true），软引用在 200 轮压力下**没有被清除（false）**——
   因为每轮 1MB 垃圾立刻可回收，GC 后空闲堆依然充足，软引用策略判定"还不够紧张"。
   这正是软引用语义的真实体现：**它只在"GC 后仍然不足"时才让路**；
2. 想让软引用稳定被清：把压力对象改成"存活集合"（引用不释放），
   让 GC 后的空闲堆持续缩小；或显式 `-XX:SoftRefLRUPolicyMSPerMB=50` 缩短软引用寿命。

---

## 五、常见面试追问与参考答案

**Q1：ThreadLocal 为什么会内存泄漏？弱引用怎么参与？**
A：ThreadLocalMap 的 Entry 继承 WeakReference（key=ThreadLocal 是弱引用，value 是强引用）。
ThreadLocal 被回收后 key 变 null，但 value 仍被 `Thread→ThreadLocalMap→Entry` 强引用；
线程不死（线程池常驻）则 value 永不释放。弱引用只是"提前通知 key 已死"的哨兵，
真正解决泄漏要靠 `try { ... } finally { threadLocal.remove(); }`；
Map 的 `get/set` 也会顺手清理 stale entry（探测式清理）。

**Q2：软引用缓存和 WeakHashMap 的区别？**
A：软引用按"内存余量"回收——适合"尽量多缓存、内存紧张再丢"的场景（图片缓存）；
WeakHashMap 的 key 按"下一次 GC"回收——适合"随宿主生命周期自动清理"的关联数据
（如把对象元数据挂在宿主对象上）。两者都不该当通用缓存主键策略用——
生产缓存用带容量上限与过期策略的 Caffeine/Guava。

**Q3：虚引用 get() 为什么必须返回 null？**
A：规范如此（finalize 时代的教训）：若虚引用能拿到对象，应用可能在回收判定后复活它，
破坏 GC 不变性。虚引用的定位就是"回收事件信号"，配合 ReferenceQueue 实现
比 finalize 更可控的清理（finalize 在 JDK9 标记 deprecated，JDK18 起最终废弃）。

**Q4：System.gc() 会立刻发生 GC 吗？生产环境该禁吗？**
A：只是建议。HotSpot 默认响应并触发一次 Full GC（Parallel/G1 下）；
`-XX:+DisableExplicitGC` 可禁止，但 Netty/DirectByteBuffer 的堆外内存
依赖 System.gc() 兜底清理——通常推荐 `-XX:+ExplicitGCInvokesConcurrent`（G1 下并发执行）
而不是直接禁用；用 NIO 堆外池的服务禁用它可能造成 Direct buffer memory OOM。

**Q5：finalize 为什么被淘汰？替代方案？**
A：finalize 由 GC 决定调用时机，不可预测、可能复活对象、异常被吞、性能差
（对象至少多一轮回收）。JDK9 起标记 deprecated。替代：
`try-with-resources`/`AutoCloseable`（主动关闭）、`java.lang.ref.Cleaner`
（基于虚引用 + ReferenceQueue，注册清理回调）、PhantomReference 自己实现。

**Q6：引用强度顺序和 GC Roots 有哪些？**
A：强 > 软 > 弱 > 虚。GC Roots：虚拟机栈帧中的引用、方法区静态变量、
常量引用（如字符串常量池）、JNI 引用、活跃线程对象、
同步监视器持有的对象、JMXBean/系统类加载器持有的类等。
可达性分析按强度分层：对某个对象来说，"最弱的可达路径"决定它按哪种引用语义处理。
