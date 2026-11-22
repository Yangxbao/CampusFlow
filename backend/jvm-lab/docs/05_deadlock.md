# 05 · 死锁实验讲义：制造、检测与排查（DeadlockLab）

> 模块路径：`backend/jvm-lab`
> 包名：`com.campusflow.lab.jvm.deadlock`

---

## 一、实验目标

1. 两个线程交叉持锁制造真实死锁（互斥、请求保持、不可剥夺、循环等待四要素齐备）；
2. 用 `java.lang.management.ThreadMXBean.findDeadlockedThreads()` **自动检测**死锁；
3. 打印两个死锁线程的名称、状态、等待锁与持有者、调用栈；
4. `System.exit(0)` 主动退出，避免非 daemon 死锁线程永久挂住进程。

---

## 二、源码要点

```java
// 交叉持锁：t1 先拿 A 后要 B，t2 先拿 B 后要 A（中间 sleep 200ms 保证交叉时序）
static void holdALockThenB(Object lockA, Object lockB) {
    synchronized (lockA) {
        sleepQuietly(200);
        synchronized (lockB) { /* 永远 BLOCKED */ }
    }
}
static void holdBLockThenA(Object lockA, Object lockB) {
    synchronized (lockB) {
        synchronized (lockA) { /* 永远 BLOCKED */ }
    }
}

// 自动检测：轮询 ThreadMXBean，最长等待 maxWaitSeconds
ThreadMXBean tmxb = ManagementFactory.getThreadMXBean();
long[] deadlockedIds = tmxb.findDeadlockedThreads();   // monitor + ownable synchronizer 死锁
for (long id : deadlockedIds) {
    ThreadInfo info = tmxb.getThreadInfo(id, 25);      // 栈 + getLockName/getLockOwnerName
    // 打印 state=BLOCKED、lockName、lockOwnerName、栈帧
}

// 死锁线程设为 daemon：main 结束前 System.exit(0) 双保险，进程绝不挂死
```

**实现细节**：
- 每次场景使用**全新锁对象**（`new Object()`），否则多次调用/并发测试会共享锁而互相污染
  （开发时真实踩到：第一个测试的死锁线程占住静态锁，第二个测试的线程永远拿不到锁，
  死锁环变成了前者）；
- `findDeadlockedThreads()` 与老接口 `findMonitorDeadlockedThreads()` 的区别：
  前者还覆盖 `java.util.concurrent` ownable synchronizer（如 ReentrantLock）死锁。

---

## 三、运行命令与真实输出

```bash
java -Dfile.encoding=UTF-8 -cp target/classes com.campusflow.lab.jvm.deadlock.DeadlockLab
```

真实输出（JDK17，Temurin 17.0.21.1+2）：

```text
== DeadlockLab：死锁制造、自动检测与栈打印 ==
提示：另开终端可用 jstack <pid> / jcmd <pid> Thread.print 看到 "Found one Java-level deadlock"

[1] 启动两个交叉持锁的线程...
[2] 线程已就位: t1 state=BLOCKED, t2 state=BLOCKED
[3] 用 ThreadMXBean 轮询 findDeadlockedThreads()（最长 15s）...
[4] 检测到死锁！等待耗时 1ms，死锁线程 2 个：

    ---- 线程 "Deadlock-T1-HolderA" (id=23) state=BLOCKED ----
    等待的锁 lockName=java.lang.Object@7106e68e  持有者=Deadlock-T2-HolderB(id=24)
        at app//com.campusflow.lab.jvm.deadlock.DeadlockLab.holdALockThenB(DeadlockLab.java:152)
        at app//com.campusflow.lab.jvm.deadlock.DeadlockLab.lambda$startHolderAThenB$0(DeadlockLab.java:133)
        at app//com.campusflow.lab.jvm.deadlock.DeadlockLab$$Lambda$1/0x00000199e2000c08.run(Unknown Source)
        at java.base@17.0.21.1/java.lang.Thread.run(Thread.java:840)

    ---- 线程 "Deadlock-T2-HolderB" (id=24) state=BLOCKED ----
    等待的锁 lockName=java.lang.Object@685f4c2e  持有者=Deadlock-T1-HolderA(id=23)
        at app//com.campusflow.lab.jvm.deadlock.DeadlockLab.holdBLockThenA(DeadlockLab.java:160)
        at app//com.campusflow.lab.jvm.deadlock.DeadlockLab.lambda$startHolderBThenA$1(DeadlockLab.java:141)
        at app//com.campusflow.lab.jvm.deadlock.DeadlockLab$$Lambda$2/0x00000199e2001000.run(Unknown Source)
        at java.base@17.0.21.1/java.lang.Thread.run(Thread.java:840)

    死锁环: Deadlock-T1-HolderA, Deadlock-T2-HolderB  （t1 持 A 等 B，t2 持 B 等 A -> 循环等待）

    等价命令: jstack <pid>  或  jcmd <pid> Thread.print
    输出尾部会有: Found one Java-level deadlock: ... "Deadlock-T1-HolderA" ... "Deadlock-T2-HolderB"

---- 分析要点 ----
1) 检测结果: 检测到死锁: Deadlock-T1-HolderA, Deadlock-T2-HolderB
2) ThreadMXBean.findDeadlockedThreads() 返回处于 monitor 死锁或 ownable synchronizer 死锁的线程 id；
3) synchronized 死锁 JVM 不会自动恢复；java.util.concurrent.locks.Lock 支持 tryLock(timeout) 主动避免；
4) 预防手段：全局锁顺序（按对象 hash/编号排序加锁）、tryLock + 超时回退、缩小锁粒度、无锁结构；
5) 线上排查：jstack <pid> 直接输出 "Found one Java-level deadlock"；jconsole/jvisualvm 也可一键检测。

实验结束，System.exit(0) 退出（避免死锁线程挂住进程）。
```

**输出解读**：
- 检测耗时仅 1ms：synchronized 死锁形成是瞬时的，生产上若进程 hung 住，
  jstack 一次就能看到 `Found one Java-level deadlock` 段落；
- `at app//...`：JDK9+ 栈帧带模块来源前缀（app=类路径应用类），
  `java.base@17.0.21.1` 表示来自 java.base 模块 17.0.21.1 版本；
- `lockOwnerName` 直接给出锁的持有者——死锁环一目了然。

---

## 四、线上排查流程（与 scripts/ 配合）

1. 发现服务卡死（接口全部超时）：`jps -l` 找 pid；
2. `scripts/collect_thread_dump.sh <pid>` 连采 3 份（间隔 2 秒）；
3. 文件尾部找 `Found one Java-level deadlock`；
   或 `scripts/jcmd_diagnose.sh <pid>`（Thread.print 等价输出）；
4. 读死锁环：哪两个线程、各持什么锁、在哪行代码——修复方向是破坏四要素之一；
5. 监控预警：可用 JMX 暴露 `ThreadMXBean.findDeadlockedThreads()` 定时检测并告警。

---

## 五、常见面试追问与参考答案

**Q1：死锁四要素与破坏方法？**
A：互斥（不可破坏，锁的本义）、请求与保持（一次性申请所有资源）、
不可剥夺（可剥夺方案：Lock + 超时/中断）、循环等待（全局锁顺序编号，按序加锁）。
工程上最常用：锁排序 + tryLock 超时回退 + 缩小锁范围。

**Q2：synchronized 死锁和 ReentrantLock 死锁有什么区别？JVM 能救吗？**
A：本质都是循环等待。synchronized 无超时/中断（不响应 interrupt 且阻塞在 monitor），JVM 检测到也不会自动解锁；ReentrantLock 支持 `tryLock(timeout)` 和可中断加锁 `lockInterruptibly()`，配合超时回退即可"自救"。JVM 层面二者都能被 ThreadMXBean 检出（后者记为 ownable synchronizer 死锁）。

**Q3：活锁、饥饿、死锁的区别？**
A：死锁=互相等待、状态不再变化；活锁=不停重试互相让路、状态一直变但无进展（如两个事务回滚重试）；饥饿=调度不公导致某线程长期得不到资源（如 unfair lock 下写多读少、优先级低）。饥饿可用公平锁/限流缓解。

**Q4：如果检测接口 findDeadlockedThreads 频繁返回 null，就一定没有死锁吗？**
A：不一定。它只检测 monitor 与 ownable synchronizer 形成的"循环等待"。
线程间通过 CountDownLatch/Semaphore/阻塞队列/远程调用形成的"逻辑死锁"
（A 等 B 的回调，B 等 A 的资源）不在检测范围——表现为线程永远 WAITING。
这类问题只能靠线程 dump 人工分析 WAITING 链。

**Q5：生产环境发现死锁，处置顺序？**
A：① 先 jstack 固化证据（可反复采集确认环稳定）；② 评估影响面，一般选择重启实例
（synchronized 死锁不会自愈）；③ 根因修复：锁顺序、锁粒度、超时回退；
④ 补监控：JMX 定时 findDeadlockedThreads 告警 + code review 卡点。
注意重启前把 dump/日志归档，否则复现成本极高。

**Q6：什么是"类死锁"/静态初始化死锁？**
A：两个类的静态初始化器相互触发对方初始化：类初始化有 JVM 级别锁，
A 初始化中引用 B、B 初始化中引用 A → 两个 Class 对象锁循环等待，
jstack 显示 BLOCKED on `<class file>`。典型诱因：单例 holder 模式互相引用。
避免静态初始化器中出现相互依赖。
