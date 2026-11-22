# 02 synchronized 实验：对象锁/类锁、可重入、失效场景与锁升级

> 源码：`src/main/java/com/campusflow/lab/concurrent/sync/SynchronizedLab.java`
> 测试：`src/test/java/com/campusflow/lab/concurrent/sync/SynchronizedLabTest.java`

## 一、实验目标

1. 区分**对象锁**（`synchronized` 实例方法 / `synchronized(this)`）与**类锁**（静态 synchronized 方法 /
   `synchronized(Xxx.class)`），验证二者互不阻塞；
2. 验证 synchronized 的**可重入性**（嵌套三层同锁不死锁）；
3. 复现两个典型**同步失效场景**：锁对象被运行期更换、每个线程各自 new 锁对象；
4. 理解**锁升级**路径：无锁→偏向锁→轻量级锁→重量级锁（JDK17 默认禁用偏向锁）。

## 二、完整源码要点

对象锁 vs 类锁：

```java
private static synchronized void staticSyncMethod(String who) { ... } // 类锁：SynchronizedLab.class
synchronized void instanceSyncMethod(String who, long holdMillis) {   // 对象锁：this
    LabKit.sleep(holdMillis);   // 持锁期间休眠，便于观察另一线程的行为
}
```

可重入（嵌套三层同一把锁）：

```java
public int nestedSynchronized() {
    int depth = 0;
    synchronized (reentrantLock) { depth++;
        synchronized (reentrantLock) { depth++;
            synchronized (reentrantLock) { depth++; /* 第三层也能进入 */ } } }
    return depth;
}
```

失效场景一：锁对象被更换（用 `AtomicInteger` 统计临界区最大并发进入数）：

```java
private static Object swapLock = new Object();

Thread t1: synchronized (swapLock) { enter(); sleep(300); exit(); }  // 锁的是【旧对象】
main: swapLock = new Object();                                       // 更换引用
Thread t2: synchronized (swapLock) { enter(); exit(); }              // 锁的是【新对象】，直接进入！
// 结果 MAX_INSIDE == 2，同步失效
```

失效场景二：每个线程各自 new 锁对象 —— `synchronized (new Object())` 等于没加锁。

并发统计工具：

```java
int now = INSIDE.incrementAndGet();
MAX_INSIDE.accumulateAndGet(now, Math::max);   // 记录最大并发数
```

## 三、运行命令

```bash
export JAVA_HOME="D:/.zcode/workspace/draft-1c0422d8-5748-4570-9ad7-ffdedaf0b31a/tools/jdk17/jdk-17.0.21.1+2"
mvn -pl backend/concurrency-lab compile

java -Dfile.encoding=UTF-8 -cp backend/concurrency-lab/target/classes \
     com.campusflow.lab.concurrent.sync.SynchronizedLab

mvn -pl backend/concurrency-lab test
```

## 四、预期现象

- 场景一：t1 持有对象锁 300ms 期间，t2 使用类锁照常进入，互不等待；而 t3/t4 抢**同一实例**的对象锁时
  严格排队；
- 可重入：嵌套三层 synchronized 全部进入，返回深度 3，没有死锁；
- 失效场景一：t1 还在临界区时 t2 就进来了，`临界区最大并发进入数 = 2`；
- 失效场景二：两个线程同时进入，`最大并发进入数 = 2`。

## 五、原理分析

- **锁的是对象，不是代码**。`synchronized` 语义是"获取某个对象的 monitor"，同一把锁的判据是
  **同一个对象实例**。对象锁（this/实例）与类锁（Class 对象）是两个不同的 monitor，因此互不阻塞。
  静态 synchronized 方法锁的是 `Xxx.class` 这个全局唯一对象，用于保护静态资源。
- **可重入的实现**：HotSpot 对象头 Mark Word 记录锁持有线程（轻量级锁在栈帧 Lock Record、重量级锁
  在 ObjectMonitor 的 `_owner`），重入时校验持有者是自己则计数+1，退出时计数-1，归零才真正释放。
  这也是 `ReentrantLock` 名字的由来（synchronized 天然可重入，无需额外操作）。
- **失效场景的共同根因**：
  1. 锁对象被更换：`synchronized(swapLock)` 在字节码层面是把当前引用压栈后执行 monitorenter，
     t1 进入时捕获的是旧对象，引用被替换后 t2 拿到的是新对象——两个 monitor 互不相干；
  2. 各自 new：每次 new 都是全新 monitor。
  **正确姿势**：锁对象声明为 `private final Object lock = new Object()`，或不裸用对象锁而用
  ReentrantLock/JUC 工具。String/Integer 等缓存对象和装箱对象做锁尤其危险（常量池共享+自动装箱）。
- **锁升级**：Mark Word 低 2 位标志锁状态。无锁→偏向锁（记录线程 ID，无竞争时免 CAS）→轻量级锁
  （交替竞争，栈上 Lock Record + CAS 自旋，失败者自旋几次）→重量级锁（ObjectMonitor，依赖 OS mutex，
  竞争线程挂起，涉及内核态切换）。JDK15 起 JEP 374 废弃偏向锁，**JDK17 默认关闭**（-XX:-UseBiasedLocking），
  所以本实验运行在 JDK17 上时实际路径是：无锁→轻量级→重量级。
- 字节码：同步代码块 = `monitorenter/monitorexit`（编译器保证异常路径也配对释放）；同步方法 =
  `ACC_SYNCHRONIZED` 标志，JVM 调用时自动加解锁。可用 `javap -v` 观察。

## 六、实际输出示例（真实运行，JDK 17.0.21.1 / Windows）

```text
==================== 1. 对象锁 vs 类锁：两把不同的锁互不阻塞；同一把对象锁才互斥 ====================
[obj-lock-thread] t1 拿到【对象锁 this】，持锁 300 ms
[class-lock-thread] t2 持有【类锁 SynchronizedLab.class】执行静态同步方法
[obj-lock-thread] t1 释放对象锁
现象：t1 持对象锁 300ms 期间，t2 用类锁照常进入 —— 对象锁与类锁是两把不同的锁。

[same-lock-1] t3 拿到【对象锁 this】，持锁 200 ms
[same-lock-2] t4 尝试获取同一个实例的对象锁（应等待 t3 释放）...
[same-lock-1] t3 释放对象锁
[same-lock-2] t4 拿到【对象锁 this】，持锁 100 ms
现象：t4 必须等 t3 释放【同一实例的对象锁】后才能进入 —— 同一把锁互斥。

==================== 2. 可重入：同一方法链上嵌套获取同一把锁 ====================
[reentrant-thread] 第 3 层 synchronized 成功进入 —— 同一线程重复获取同一把锁不会阻塞（可重入）
嵌套 3 层 synchronized 全部进入，没有死锁 —— monitor 拥有者+计数机制保证可重入。

==================== 3. synchronized 失效场景一：锁对象在运行期被更换 ====================
[swap-t1] t1 持有【旧锁对象】进入临界区（300ms）
main 已将 swapLock 指向新对象（t1 仍持有旧对象的锁）
[swap-t2] t2 拿到【新锁对象】，在 t1 还未退出时就进入了"同一逻辑临界区"！
现象：临界区最大并发进入数 = 2（=2 说明同步失效，正确实现应为 1）

==================== 4. synchronized 失效场景二：每个线程各自 new 一个锁对象 ====================
现象：临界区最大并发进入数 = 2（=2，两把锁互不相干，等于没加锁）
```

## 七、常见面试追问

**1. synchronized 锁对象、锁实例、锁类有什么区别？**
`synchronized(obj)`/实例方法：锁 monitor 是该实例，保护实例状态；静态方法/`Xxx.class`：锁 Class
对象（全局唯一），保护静态状态。二者互不阻塞；一把锁要生效，所有竞争方必须用**同一个对象**。

**2. synchronized 是可重入的吗？原理是什么？**
是。monitor 拥有者记录在 Mark Word/ObjectMonitor._owner，重入校验持有者是自己则计数累加，退出等次数
才释放。可重入避免了"对象方法链上互相调用导致自死锁"。

**3. 有哪些场景会让 synchronized "失效"？**
锁对象被替换（字段引用重指向）；每个线程 new 各自的锁；锁了装箱类型/字面量（缓存共享导致意外的锁共享）；
锁了可变集合对象但修改的是引用；读写不同实例（共享状态本应在静态/共享对象上）。统一解法：`private final`
锁对象或直接用 JUC 锁。

**4. 锁升级过程是怎样的？JDK17 还偏向锁吗？**
无锁→偏向→轻量级→重量级（只升不降）。偏向锁在无竞争场景免 CAS，出现第二个线程竞争即撤销升级；
轻量级锁 CAS 自旋；重量级锁走 OS mutex。JDK15 废弃偏向锁（维护成本>收益，撤销要 safepoint），
JDK17 默认关闭偏向锁，轻竞争直接轻量级锁。因此"偏向锁"问题要按 JDK 版本回答。

**5. synchronized 与 ReentrantLock 怎么选？**
synchronized：JVM 级优化（锁消除/锁粗化/升级）、语法简洁、忘 unlock 也不会泄漏（字节码保证）；
ReentrantLock：可中断、tryLock 超时、公平锁、多 Condition、更好 metrics。一般场景 synchronized
优先，需要高级特性再换 ReentrantLock。

**6. monitorenter 会不会被优化掉？**
会。逃逸分析 + 锁消除（如方法内局部 StringBuffer 的同步被删）与锁粗化（相邻同步块合并）都是 JIT 优化；
这也是为什么微基准测 synchronized 要小心假优化。

**7. synchronized 修饰静态方法和实例方法，两个线程分别调用会互斥吗？**
不会。一个用类锁一个用对象锁。若业务上两者保护同一份共享数据，就会产生并发 bug——本实验场景 1
正是这个现象的可视化。

## 八、测试说明

`SynchronizedLabTest` 共 4 个用例（`@Timeout(30)`）：嵌套三层可重入（深度=3）、对象锁与类锁独立
（t2 在 t1 持对象锁期间完成类锁操作）、锁对象更换后最大并发=2、各自 new 锁对象最大并发=2。
