# 06 AQS 实验：自定义不可重入独占锁

> 源码：`src/main/java/com/campusflow/lab/concurrent/aqs/AqsLab.java`
> 测试：`src/test/java/com/campusflow/lab/concurrent/aqs/AqsLabTest.java`

## 一、实验目标

1. 继承 `AbstractQueuedSynchronizer`（AQS）实现 `tryAcquire/tryRelease/isHeldExclusively`，
   自定义一把**不可重入独占锁**；
2. 验证不可重入语义：持有者自己 `tryLock` 也失败（并说明若 `lock()` 会永久阻塞死锁）；
3. 演示两个线程的**互斥排队**：受锁保护的计数不丢失、临界区最大并发恒为 1、释放后按队列顺序获得；
4. 建立"ReentrantLock/Semaphore/CountDownLatch 都是 AQS 不同用法"的整体认知。

## 二、完整源码要点

```java
static class NonReentrantLock implements Lock {

    private final Sync sync = new Sync();

    private static final class Sync extends AbstractQueuedSynchronizer {

        @Override
        protected boolean isHeldExclusively() {
            return getState() == 1;            // state=1 表示已锁定
        }

        @Override
        protected boolean tryAcquire(int acquires) {
            if (compareAndSetState(0, 1)) {    // 只看 state，不检查 owner
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;                      // 失败 -> AQS 包装成 Node 入 CLH 队列并 park
        }

        @Override
        protected boolean tryRelease(int releases) {
            if (getState() == 0) {
                throw new IllegalMonitorStateException("锁未被持有，不能释放");
            }
            setExclusiveOwnerThread(null);
            setState(0);                       // release 会 unpark 后继节点
            return true;
        }

        Condition newCondition() { return new ConditionObject(); }
    }

    public void lock()  { sync.acquire(1); }
    public void unlock(){ sync.release(1); }
    public boolean tryLock() { return sync.tryAcquire(1); }
    // lockInterruptibly -> acquireInterruptibly；tryLock(time) -> tryAcquireNanos
}
```

互斥验证（受锁保护的非原子计数）：

```java
lock.lock();
try {
    inside.incrementAndGet();          // 统计临界区并发
    maxInside.accumulateAndGet(now, Math::max);
    guardCounter.incrementAndGet();    // 若互斥被破坏会丢更新
    LabKit.sleep(1);
    inside.decrementAndGet();
} finally { lock.unlock(); }
```

## 三、运行命令

```bash
export JAVA_HOME="D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"
mvn -pl backend/concurrency-lab compile

java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.aqs.AqsLab

mvn -pl backend/concurrency-lab test
```

## 四、预期现象

- 持有者自己 `tryLock()` 返回 false（不可重入），释放后返回 true；
- 2 线程 × 200 次临界区：受锁保护计数恰好 400（无丢失），临界区最大并发 = 1；
- 排队时间线：main 持锁 300ms，t2/t3 依次阻塞；main 释放后 t2、t3 **按排队顺序**依次获得锁。

## 五、原理分析

- **AQS 的三大组件**：
  1. `volatile int state` —— 同步状态，所有读写都经 CAS（`getState/setState/compareAndSetState`）；
  2. **CLH 变体的双向等待队列** —— 获取失败的线程包装成 Node 追加到队尾（自旋 CAS 入队），
     然后 `LockSupport.park` 挂起；头节点释放时 `unparkSuccessor` 唤醒队首后继；
  3. **模板方法模式** —— AQS 实现全部排队/挂起/唤醒/超时/中断逻辑，子类只实现决定"state 怎样算
     获取成功"的钩子：独占 `tryAcquire/tryRelease`，共享 `tryAcquireShared/tryReleaseShared`。
- **本实验锁为什么不可重入**：`tryAcquire` 只做 `CAS(0→1)`，不比较 exclusiveOwnerThread 是否为
  当前线程——持有者再进来时 state 已是 1，CAS 失败入队等自己释放，`lock()` 即永久阻塞（自死锁）。
  对比 ReentrantLock 的 `tryAcquire`：先判 `current == getExclusiveOwnerThread()` 则 `state+c`。
  一行代码之差就是可重入与否的全部语义。
- **acquire(1) 的完整流程**：`tryAcquire` 失败 → `addWaiter(Node.EXCLUSIVE)` CAS 入队 →
  `acquireQueued` 循环中仅当**前驱是头节点**才再次尝试获取，否则 `park`；被唤醒后继续循环。
  释放：`tryRelease` 成功 → `unparkSuccessor` 唤醒队首后继。
- **公平性从哪来**：ReentrantLock 公平版在 `tryAcquire` 里多查 `hasQueuedPredecessors()`；
  本实验锁的行为介于两者之间——释放瞬间新来的线程也可能 CAS 抢到（非公平），而已在队列中的线程
  按顺序被唤醒。
- **生态位**：ReentrantLock（独占+重入+Condition）、Semaphore（共享，state=许可数）、
  CountDownLatch（共享一次性）、ReentrantReadWriteLock（两套 state 位段）、
  ThreadPoolExecutor 的 Worker 也继承 AQS——掌握 AQS 就掌握了 JUC 锁体系的一半。

## 六、实际输出示例（真实运行，JDK 17.0.21.1 / Windows）

```text
==================== 1. 自定义不可重入锁：持有者再次 tryLock 也会失败 ====================
main 已 lock() 一次（state=1）
同一线程再次 tryLock() = false  <- 不可重入：即使是持有者自己也会被拒绝；若此时调用 lock() 将永久阻塞（死锁）
释放后 tryLock() = true

==================== 2. 两线程互斥排队：受锁保护的计数不丢失，临界区并发恒为 1 ====================
2 线程 x 200 次临界区，总耗时 523 ms
前 20 次持锁线程次序: [aqs-t1, aqs-t1, aqs-t1, aqs-t1, aqs-t1, aqs-t2, aqs-t2, aqs-t2, aqs-t2, aqs-t2, aqs-t2, aqs-t2, aqs-t2, aqs-t2, aqs-t2, aqs-t2, aqs-t2, aqs-t1, aqs-t1, aqs-t1]
互斥统计：受锁保护计数=400（期望 400），临界区最大并发=1（应为 1）

==================== 3. 互斥排队时间线：main 持锁 300ms，t2/t3 依次阻塞，释放后按队列顺序获得 ====================
main 持有锁 300ms ...
[aqs-q2] 开始 lock()（应阻塞排队）
[aqs-q3] 开始 lock()（应阻塞排队）
main release 锁 ...
[aqs-q2] 拿到锁（main 释放后才获得）
[aqs-q3] 拿到锁（在 q2 之后）
获得锁顺序: [aqs-q2, aqs-q3] —— 与排队顺序一致（AQS 队列 FIFO 唤醒）。
```

## 七、常见面试追问

**1. AQS 的核心结构是什么？**
`volatile int state`（同步状态，CAS 修改）+ CLH 变体双向等待队列（Node 持有线程引用、等待状态
ws、前后指针）+ 独占/共享两种模式 + ConditionObject 条件队列。排队的获取方 park，释放方 unpark
后继节点。

**2. 独占模式和共享模式的区别？**
独占：一次只有一个线程持有（ReentrantLock），实现 tryAcquire/tryRelease；共享：多个线程可同时
通过（Semaphore/CountDownLatch/读写锁的读锁），实现 tryAcquireShared/tryReleaseShared，成功后
AQS 还会**传播唤醒**后续共享节点（setHeadAndPropagate）。

**3. 用 AQS 写一个锁，最少要实现哪些方法？**
独占锁：`tryAcquire` + `tryRelease`（`isHeldExclusively` 供 Condition 判断）。加解锁的排队、
挂起、唤醒、超时、中断全部由 AQS 框架完成；本实验的不可重入锁总共约 30 行。

**4. CLH 队列是怎么入队和出队的？**
入队：`addWaiter` 用 CAS 把新 Node 追加到队尾（自旋直到成功），再返回前驱；出队：acquireQueued
循环中只有前驱是 head 时才 tryAcquire，成功则 `setHead`（自己变成新 head，原 head 出队）。
park/unpark 用 LockSupport 实现，ws 状态 SIGNAL 表示"我释放时会叫醒后继"。

**5. 为什么 tryAcquire 里的状态修改必须用 CAS？**
state 是被所有线程竞争的共享变量，"检查+修改"必须原子，否则两个线程同时判 state==0 都认为
获取成功，互斥即被破坏。AQS 全部状态迁移（入队、state 修改、ws 修改）都基于 CAS。

**6. 如何基于 AQS 实现一个Semaphore？**
state 初始化为许可数；`tryAcquireShared`：`state>0` 时 CAS 减 1，减后仍 >=0 返回正数（成功），
否则负数（入队）；`tryReleaseShared`：CAS 加 1 并返回 true 触发唤醒。公平版先查队列。
这就是 `Semaphore` 的真实实现（Sync/FairSync/NonfairSync）。

**7. LockSupport.park 和 Thread.sleep/wait 的区别？**
park/unpark 以"许可"为单位：unpark 可先于 park 调用（许可先存着），不响应 spurious wake；
park 不需要持有 monitor，可指定 deadline；响应中断但不抛异常（需自行检查 Thread.interrupted）。
AQS 全靠它实现线程挂起唤醒，比 wait/notify 更底层、更灵活。

## 八、测试说明

`AqsLabTest` 共 3 个用例（`@Timeout(30~60)`）：持有者 tryLock 失败/释放后成功；
2×200 次临界区受保护计数恰为 400 且最大并发为 1；排队两线程按 [q2, q3] 顺序获得锁。
