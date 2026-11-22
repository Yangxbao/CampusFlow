# 07 Semaphore 实验：活动场馆限流

> 源码：`src/main/java/com/campusflow/lab/concurrent/semaphore/SemaphoreLab.java`
> 测试：`src/test/java/com/campusflow/lab/concurrent/semaphore/SemaphoreLabTest.java`

## 一、实验目标

1. 用 `Semaphore` 模拟"活动场馆同时只允许 N 人进入"：10 名游客竞争 3 个许可；
2. 观察 `acquire/release` 的阻塞-放行节奏：在场人数峰值始终 <= 许可数；
3. 掌握 `tryAcquire(timeout)`：拿不到许可限时放弃（快速失败/降级）；
4. 理解 Semaphore 基于 AQS **共享模式**的实现原理。

## 二、完整源码要点

```java
Semaphore venue = new Semaphore(capacity);   // capacity=3，默认非公平

// 游客线程
venue.acquire();          // 没有许可 -> 阻塞排队
try {
    int now = inside.incrementAndGet();
    maxInside.accumulateAndGet(now, Math::max);  // 统计在场峰值
    LabKit.sleep(stayMillis);                    // 参观
} finally {
    venue.release();      // 必须归还许可，否则后来者永远进不来（许可泄漏）
}

// 超时获取
boolean got = semaphore.tryAcquire(300, TimeUnit.MILLISECONDS); // false -> 走降级
```

## 三、运行命令

```bash
export JAVA_HOME="D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"
mvn -pl backend/concurrency-lab compile

java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.semaphore.SemaphoreLab

mvn -pl backend/concurrency-lab test
```

## 四、预期现象

- 游客 0/1/2 先进入，在场人数依次 1→2→3；后续游客阻塞；
- 每当有人离开（release），队列头部的下一位立即补位，在场人数始终不超过 3；
- 最终 10 人全部进入过（排队但不丢弃），在场峰值恰好 3；
- tryAcquire 场景：许可被占满时等待 300ms 超时失败；release 后立即成功（0ms）。

## 五、原理分析

- **语义**：Semaphore 维护"剩余许可数"。`acquire()` 无许可则阻塞；`release()` 归还许可并唤醒
  等待者。它限制的是**同时访问的线程数量**，与锁的区别：锁是 permits=1 的特殊 Semaphore，
  且锁有"持有者"概念，Semaphore 没有（任何线程都能 release）。
- **AQS 共享模式实现**：`Sync extends AbstractQueuedSynchronizer`，state 即许可数。
  `tryAcquireShared`：CAS 把 state 减 1，若结果 >=0 返回正数（成功），否则负数（入队 park）；
  `tryReleaseShared`：CAS 加 1，成功后 AQS `doReleaseShared` 唤醒队首。共享模式特有的
  **传播唤醒**：一个许可释放后，AQS 会级联唤醒队列中后续的共享节点。
  公平版 `fairTryAcquireShared` 先检查 `hasQueuedPredecessors`，非公平版直接 CAS。
- **典型应用**：接口限流（permits=并发配额）、连接池配额、限流+降级（tryAcquire 失败走兜底）、
  二元信号量做"可跨线程释放的锁"。
- **常见坑**：
  1. release 必须放 finally，否则一次异常就永久少一个许可；
  2. `acquire(n)/release(n)` 不对称会改变许可总量；
  3. permits 初始为 0 的用法：当"等待某事件发生"的倒计时（先 acquire 阻塞，事件回调 release）；
  4. 与 CountDownLatch 区别：Latch 是一次性（await 等归零），Semaphore 可反复 acquire/release。

## 六、实际输出示例（真实运行，JDK 17.0.21.1 / Windows）

```text
==================== 1. 活动场馆限流：同时只允许 3 人进入，共 10 名游客 ====================
[visitor-0] 游客 0 进入场馆，在场人数=1
[visitor-1] 游客 1 进入场馆，在场人数=2
[visitor-2] 游客 2 进入场馆，在场人数=3
[visitor-0] 游客 0 离开
[visitor-3] 游客 3 进入场馆，在场人数=3
[visitor-1] 游客 1 离开
[visitor-4] 游客 4 进入场馆，在场人数=3
[visitor-2] 游客 2 离开
[visitor-5] 游客 5 进入场馆，在场人数=3
...
[visitor-9] 游客 9 进入场馆，在场人数=3
结果：成功进入 10 人，在场峰值 3 人（许可数=3，峰值始终<=许可数）

==================== 2. tryAcquire 超时：拿不到许可就快速失败（不无限排队） ====================
main 已占满 2 个许可
tryAcquire(300ms) = false，等待 300 ms（超时快速失败，可走降级逻辑）
release 一个许可后 tryAcquire(1s) = true，等待 0 ms
```

## 七、常见面试追问

**1. Semaphore 的原理？state 代表什么？**
state=剩余许可数（AQS 共享模式）。acquire 对 state CAS 减 1，减完 <0 入队挂起；release 加 1
并唤醒队首等待者。共享模式唤醒会级联传播给连续的共享节点。

**2. Semaphore 和锁的区别？**
锁（permits=1 + 持有者语义）保护互斥访问；Semaphore 只限制并发数量，没有持有者——A 线程 acquire
B 线程可以 release，因此能做"生产-消费"式的配额传递；acquire/release 也不要求配对持有。

**3. tryAcquire 和 acquire 怎么选？**
acquire 无限等待（必须进来的场景，如买票排队）；tryAcquire(timeout) 快速失败（限流降级：拿不到
配额直接返回"系统繁忙"），Web 高并发下必须快速失败而不是让请求无限堆积。

**4. 为什么 release 一定要放 finally？**
release 不在 finally 时，业务抛异常会吞掉一次 release，许可泄漏导致后续 acquire 永久阻塞
（可用许可数单调减少）。这和 Lock.unlock 放 finally 是同一纪律。

**5. 公平/非公平 Semaphore 的区别？**
非公平（默认）：新来线程直接 CAS 抢许可，可能插队，吞吐高；公平：先查等待队列有无前驱，
严格 FIFO。和 ReentrantLock 的权衡完全一致。

**6. 如何用 Semaphore 实现接口限流？局限是什么？**
`new Semaphore(N)`，请求进来 tryAcquire，失败即 429/降级，处理完 finally release。
局限：**单机内**的内存态限流，多实例部署需 Redis+Lua（令牌桶/滑动窗口）等分布式限流；
且它限制的是并发数不是 QPS。令牌桶（Guava RateLimiter）才是速率语义。

**7. 初始 permits=0 有什么用？**
经典"等待事件"模式：业务线程 acquire() 阻塞，事件回调（如网络包到达、任务完成）release() 放行。
比 wait/notify 更简洁，还可配合 tryAcquire(timeout) 做"限时等待事件"。

## 八、测试说明

`SemaphoreLabTest` 共 2 个用例（`@Timeout(30~60)`）：10 游客 3 许可，断言成功进入 10 人且在场
峰值恰为 3；tryAcquire 占满时超时失败、release 后成功。
