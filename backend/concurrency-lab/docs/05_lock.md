# 05 ReentrantLock 实验：可重入、公平性、tryLock、可中断与 Condition

> 源码：`src/main/java/com/campusflow/lab/concurrent/lock/ReentrantLockLab.java`
> 测试：`src/test/java/com/campusflow/lab/concurrent/lock/ReentrantLockLabTest.java`

## 一、实验目标

1. 验证 **可重入**：`holdCount` 随重入递增、随 unlock 递减，归零才释放；
2. 对比 **公平锁/非公平锁** 的获取序列差异（交替次数指标）；
3. 掌握 **tryLock(timeout)**：拿不到锁限时放弃；
4. 掌握 **lockInterruptibly()**：等待锁期间可被中断；对比普通 `lock()` 不响应中断；
5. 掌握 **Condition await/signal**：等待时自动释放锁、signal 后需重新竞争锁，以及多 Condition 分组唤醒。

## 二、完整源码要点

可重入：

```java
lock.lock();   // holdCount=1
lock.lock();   // holdCount=2（同一线程重入）
lock.unlock(); // holdCount=1
lock.unlock(); // holdCount=0，真正释放
```

公平/非公平对比：两个线程各 500 次快速 lock/unlock，用字符序列记录获取顺序：

```java
String fairSeq   = race(new ReentrantLock(true), 500);   // 'A','B' 交替明显
String unfairSeq = race(new ReentrantLock(false), 500);  // 同一字母连串更多
```

tryLock 超时：

```java
boolean quick = lock.tryLock(100, TimeUnit.MILLISECONDS);  // 持有者未释放 -> false
boolean later = lock.tryLock(1500, TimeUnit.MILLISECONDS); // 等到释放 -> true
```

可中断获取：

```java
try {
    lock.lockInterruptibly();   // 等待期间可被 interrupt -> InterruptedException
} catch (InterruptedException e) { /* 优雅放弃 */ }
```

Condition：

```java
ReentrantLock lock = new ReentrantLock();
Condition condition = lock.newCondition();
// waiter：lock.lock(); condition.await(2, SECONDS);  // await 自动释放锁
// signaler：lock.lock(); condition.signal(); lock.unlock(); // waiter 此时才真正恢复
```

## 三、运行命令

```bash
export JAVA_HOME="D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"
mvn -pl backend/concurrency-lab compile

java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.lock.ReentrantLockLab

mvn -pl backend/concurrency-lab test
```

## 四、预期现象

- holdCount 依次 1→2→1→0；
- 公平锁序列交替次数远高于非公平锁（示例输出 343 vs 164 / 999；两线程同时起步初期有一方尚未入队，
  连续获取属正常，竞争充分后差异才显现）；
- tryLock(100ms) 失败、tryLock(1500ms) 成功；
- lockInterruptibly 的等待线程被中断后抛 InterruptedException 优雅退出；普通 lock() 线程被中断后
  仍在等待，最终照样拿到锁；
- Condition 演示中 signaler 能在 waiter await 期间拿到锁（证明 await 释放了锁），waiter 被唤醒
  等待了约 300ms。

## 五、原理分析

- **ReentrantLock = AQS state + 重入语义**。`lock()` 走 `sync.acquire(1)`；非公平锁的
  `nonfairTryAcquire` 先 CAS 抢一次（`state 0→1`），抢不到再入 CLH 队列；重入即 `state+1`，
  `tryRelease` 减到 0 才 `setExclusiveOwnerThread(null)` 并唤醒后继。这就是 holdCount 的来源。
- **公平 vs 非公平**：公平锁 `tryAcquire` 前先 `hasQueuedPredecessors()`——队列里有先来者就不插队；
  非公平锁直接 CAS 抢（刚释放锁的线程大概率还在 CPU 上跑，立即 re-CAS 往往成功，即"barging"），
  省去了线程挂起/唤醒的调度开销，吞吐更高，但可能"饥饿"。锁唤醒的成本（park/unpark + 调度）
  正是非公平默认策略存在的原因。
- **tryLock(timeout)** 走 `tryAcquireNanos`：限时自旋 + `LockSupport.parkNanos` 挂起，超时返回 false，
  调用方可降级/放弃，避免无限阻塞导致的级联超时。
- **lockInterruptibly** 在 AQS 排队等待期间检查中断标志并抛出，等待中的线程可被取消；
  普通 `lock()` 不检查中断（获得锁后才补设标志位），用于"必须拿到锁"的场景。
- **Condition = AQS 的等待队列**。每个 Condition 维护独立 FIFO 等待队列；`await` 把当前线程从
  AQS 同步队列转移到条件队列并释放锁；`signal` 把条件队列头节点转移回同步队列，等 signaler
  `unlock` 后它重新竞争锁才能从 await 返回。多个 Condition 可实现"生产者/消费者分组唤醒"，
  这是 `Object.wait/notify`（单等待集、notify 随机、notifyAll 惊群）做不到的。
- 与 synchronized 对比：ReentrantLock 是 API 层锁（必须 try-finally unlock），功能更丰富；
  synchronized 是 JVM 内建（自动释放、锁升级优化）。JDK17 上两者性能已接近，按功能需要选择。

## 六、实际输出示例（真实运行，JDK 17.0.21.1 / Windows）

```text
==================== 1. 可重入：lock() 两次、unlock() 两次 ====================
[reentrant-worker] 第一次 lock 成功, holdCount=1
[reentrant-worker] 第二次 lock 成功, holdCount=2（可重入计数 +1）
[reentrant-worker] 第一次 unlock, holdCount=1（锁仍被本线程持有）
[reentrant-worker] 第二次 unlock, holdCount=0（真正释放）

==================== 2. 公平锁 vs 非公平锁：两个线程各 500 次快速 lock/unlock 的获取序列 ====================
公平锁前 60 次获取: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
非公平锁前 60 次获取: AAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
公平锁交替次数 = 343/999，非公平锁交替次数 = 164/999
公平锁近似严格轮流（FIFO 队列）；非公平锁出现"同一线程连续抢到"（释放后立即插队 re-CAS 成功）。

==================== 3. tryLock(timeout)：限时获取，拿不到就放弃 ====================
[trylock-tryer] tryLock(100ms) = false（持有者还没释放 -> 超时失败，不傻等）
[trylock-tryer] tryLock(1500ms) = true（等到持有者释放 -> 成功）

==================== 4. lockInterruptibly：等待锁时可被中断；普通 lock() 不响应中断 ====================
[intr-waiter] t2 在等待锁的过程中被 interrupt -> 抛出 InterruptedException，优雅放弃
t2 被中断退出 = true
t3 被 interrupt 后 100ms 仍在等待锁 = true —— 普通 lock() 不响应中断
[plain-lock-waiter] t3 用普通 lock() 拿到了锁（等待期间被 interrupt 过: true，但 lock() 继续等到了锁）

==================== 5. Condition await/signal ====================
[condition-waiter] waiter 持锁后调用 await（释放锁进入等待队列）
[condition-signaler] signaler 拿到锁（说明 waiter await 时确实释放了锁）, 执行 signal
[condition-waiter] waiter 被唤醒, await 返回 true, 等待了 301 ms, 唤醒后继续持锁执行
要点：signal 不是立即唤醒 —— waiter 要等 signaler 释放锁（unlock）后才能从 await 返回并重新拿锁。
```

注：公平锁前 60 次全是 A，是因为 B 尚未进入竞争（队列空时 A 合法连续获取）；二者都在竞争后，
公平锁的交替次数（343）显著高于非公平锁（164），差异用"交替次数"度量更客观。

## 七、常见面试追问

**1. ReentrantLock 为什么叫"可重入"？实现原理？**
同一线程可以重复获得自己已持有的锁。AQS `state` 计数：重入 +1，释放 -1，归零才真正释放并唤醒后继。
持有者记录在 AQS 的 exclusiveOwnerThread。

**2. 公平锁和非公平锁的区别？为什么默认非公平？**
公平锁严格按等待队列 FIFO（`hasQueuedPredecessors` 检查），无饥饿但每次唤醒都要挂起/恢复线程，
吞吐低；非公平锁允许插队（释放后立即 re-CAS），命中率极高（线程刚在 CPU 上），吞吐高，可能饥饿。
默认非公平是为了减少上下文切换，绝大多数场景更优。

**3. tryLock、lockInterruptibly 分别适合什么场景？**
tryLock：避免死锁的"拿不到就放弃/重试"，限时获取，超时降级；lockInterruptibly：可取消的等待，
例如服务停机时中断持锁等待的线程让其优雅退出；两者也是打破"死锁互相等待"的工程手段。

**4. Condition 和 Object.wait/notify 的区别？**
必须配合 Lock 使用；一个 Lock 可创建多个 Condition（分组等待/分组唤醒，如 ArrayBlockingQueue 的
notEmpty/notFull）；await 支持定时/可中断/不响应中断（awaitUninterruptibly）等多个变体；
signal 语义可控。wait/notify 只有一个监视器等待集，notify 随机、notifyAll 惊群。

**5. ReentrantLock 和 synchronized 怎么选？**
默认 synchronized（简洁、自动释放、JIT 优化足够）；需要 tryLock/可中断/公平/多 Condition、
或需要拿到锁的等待统计（getQueuedThreads 等）时用 ReentrantLock；务必 try-finally unlock。

**6. 如果 unlock 次数少于 lock 会怎样？**
锁永远不释放（state>0），其他线程永久阻塞；unlock 多了会抛 IllegalMonitorStateException。
所以 ReentrantLock 必须在 finally 中 unlock，且加解锁次数严格配对（重入也一样）。

**7. 公平锁一定"更公平"吗？生产上该开吗？**
FIFO 只是"获取顺序公平"，不等于整体延迟公平；公平锁吞吐显著下降，多数业务用非公平+超时/重试
机制来防饥饿，而不是开公平锁。只有确有"严格排队"语义需求（如批量任务轮转）才用。

## 八、测试说明

`ReentrantLockLabTest` 共 6 个用例（`@Timeout(30~60)`）：holdCount 重入配对、tryLock 超时失败/等锁成功、
lockInterruptibly 中断响应、普通 lock() 中断后继续等待并最终拿锁、Condition signal 唤醒、
公平/非公平各 600 次获取全部完成。
