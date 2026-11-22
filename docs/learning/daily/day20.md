# Day 20 · Redisson 分布式锁与看门狗

> 阶段六（20-22 分布式锁与一致性）第 1 天。今天把锁的"三个经典问题"全部钉死在项目代码上。

## 一、今天学习什么

**两种使用形态**（infrastructure/lock 包）：
1. 注解式 `@DistributedLock`（DistributedLockAspect）：
   - key 支持 SpEL（`'activity:publish:' + #activityId`），统一前缀 `campusflow:lock:aspect:`；
   - `leaseTimeMillis<=0`（默认）→ 两参 tryLock → **看门狗模式**；
   - `skipOnFail` 支持静默跳过（定时任务风格）；
2. 编程式 `RedissonLockService`：
   - `executeWithLock(key, waitMillis, supplier)`：拿不到锁抛 LOCK_TIMEOUT(30001)；
   - `tryRun(key, task)`：waitTime=0，抢不到直接返回 false——**定时任务防重入专用**。

**看门狗原理**：两参 `tryLock(waitTime, unit)` 不传 leaseTime → 默认 TTL 30s →
客户端每 10s（TTL/3）检查业务仍在执行则重置 TTL——**业务没跑完，锁不过期**。

**解锁三防护**（DistributedLockAspect 类注释）：
1. 看门狗防"业务超时锁先过期"；
2. Redisson 锁值含 UUID:threadId，删除前校验持有者（防误删他人锁）；
3. finally 中 `isHeldByCurrentThread()` 校验后才 unlock；显式 leaseTime 场景的残余窗口
   由业务幂等兜底。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/lock/DistributedLock.java` | 注解四属性 + 注释（自调用失效警告） |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/lock/DistributedLockAspect.java` | 全文（今天主教材） |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/lock/RedissonLockService.java` | executeWithLock/tryRun 两方法 |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/config/RedissonConfig.java` | 连接池规格与看门狗注释 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/activity/service/ActivityService.java` | 三处 @DistributedLock 实战（publish/status/capacity） |

## 三、搞懂哪些八股

- 锁三经典问题：①锁过期业务未完（看门狗）；②误删他人锁（值校验+Lua 原子删）；
  ③主从切换锁丢失（Redlock 或幂等兜底——本项目选幂等兜底）；
- setnx 自研锁 vs Redisson：自研要手写"值校验+删除"的 Lua、续期线程、可重入——
  Redisson 全部内置（RInstrumentation 之外还支持可重入 RLock）；
- 锁的语义定位：**效率工具不是正确性工具**——正确性必须由 Lua 原子/DB 约束/幂等保证，
  锁只是降低竞争概率（day18 晋级的"锁+CAS"双保险就是范例）；
- tryLock(waitTime=0) 的场景语义：抢不到说明别的实例在跑，跳过比等待更合理（定时任务）。

## 四、运行哪些命令

```bash
# 观察锁的生命周期日志
grep -E "获取分布式锁|释放分布式锁" backend/logs/campusflow.log

# 观察定时任务锁竞争（多实例或重复日志）
redis-cli -a 123321 KEYS 'campusflow:lock:job:*'
redis-cli -a 123321 TTL campusflow:lock:job:stockReconcile

# 锁值结构（Redisson 加密存储，用 MONITOR 看写入）
redis-cli -a 123321 --no-raw GET campusflow:lock:job:waitlistTimeout | head -c 200
```

## 五、完成什么实验

1. **互斥实验**：day06 已做（两线程抢 @DistributedLock），今天升级版——
   写两个并发方法分别用 executeWithLock 与 tryRun，观察 LOCK_TIMEOUT 异常 vs 静默 false 的差异；
2. **看门狗观察**：写一个持锁 40s 的方法（两参 tryLock），另起线程每 5s 查
   `redis-cli TTL <lockKey>`——记录 TTL 始终在 20-30 区间波动（续期生效）；
   改成显式 leaseTime=10s 复跑，观察 10s 后 TTL 归零锁过期；
3. **误删防护推演**：纸面推演"A 持锁 5s 过期 → B 获得锁 → A finally unlock"在
   本项目下会发生什么（isHeldByCurrentThread 返回 false，不删除 B 的锁）。

## 六、面试自测（口头回答）

1. **看门狗是什么？怎么触发的？**
   （两参 tryLock 不传 leaseTime 触发；默认 30s TTL 每 10s 续期；
   显式 leaseTime 则看门狗失效，必须保证 leaseTime > P99 业务耗时——
   DistributedLockAspect 注释三条逐一展开。）
2. **锁误删怎么防？**
   （值含 UUID:threadId + 删除前校验持有者 + isHeldByCurrentThread；
   自研锁则要 Lua"比对值+删"原子脚本。）
3. **你的项目哪些地方用了锁？为什么这些地方必须锁？**
   （预热（防并发重复预热）/候补晋级（串行化长链路）/六定时任务（多实例防重）/
   活动状态切换——共性：**长链路或幂等性弱的低频操作**；报名热路径不用锁（Lua 足够）。）
4. **Redis 主从切换锁会失效吗？你怎么兜底？**
   （会：异步复制丢锁。兜底：本项目锁场景全部幂等或 CAS 兜底，锁失效只多一次竞争，
   不破坏正确性；要更强一致可用 Redlock/ fencing token，但复杂度高需权衡。）

## 七、自我检查标准

- [ ] 锁三经典问题 × 项目防护措施表格默写；
- [ ] 看门狗 TTL 波动实验完成（数据记录）；
- [ ] 两种锁形态的选择标准能说清（注解式常规 / 编程式需返回值或静默跳过）；
- [ ] "锁不是正确性工具"这句能举两个项目实例论证。

## 附一、知识点详解

**1. 项目锁 Key 全景（治理集中化的价值）**

| Key 模板 | 场景 | 获取方式 |
|---|---|---|
| campusflow:lock:stock:preload:{aid} | 库存预热 | @DistributedLock（业务侧模板） |
| campusflow:lock:activity:publish:{aid} | 发布/取消/容量 | @DistributedLock |
| campusflow:lock:waitlist:promote:{aid} | 候补晋级 | RedissonLockService.tryRun |
| campusflow:lock:job:{jobName} | 六个定时任务 | RedissonLockService.tryRun(0) |

全部集中在 RedisKeyConstants——**锁 Key 与数据 Key 一样需要治理**
（排查时 `KEYS campusflow:lock:*` 一目了然）。

**2. executeWithLock 与 tryRun 的返回语义差异**
executeWithLock：拿不到锁抛 LOCK_TIMEOUT——调用方（用户操作）需要明确失败反馈；
tryRun：返回 false 静默跳过——调用方（定时任务）默认"另一实例在跑"是正常态。
**同一个锁组件为两种调用场景提供两种失败语义**，这是 API 设计的体贴。

**3. isHeldByCurrentThread 在 finally 里的必要性**
即使有看门狗，仍存在"进程 GC 长暂停 → 锁过期 → 他人获锁 → 暂停结束执行 unlock"
的窗口；无校验会误删他人锁。Redisson 的 unlock 对非持有者抛
IllegalMonitorStateException，但显式校验让意图更清晰且日志可控。

**4. 为什么预热锁 key 里带活动 ID（粒度选择）**
全局锁（campusflow:lock:stock:preload）会让不同活动的预热互相串行——
发布多个活动时发布接口排队。按活动加锁 = **锁粒度匹配竞争域**，
这是锁设计的第一原则（对照：候补晋级锁也按活动）。

## 附二、看门狗 TTL 观察记录

```
实验(两参tryLock+40s业务): TTL 采样 25/22/28/24/27/26 → 续期生效区间 20-30s
对照(显式leaseTime=10s):    10s 后 TTL 归 0, 另一线程获得锁 → 看门狗未启动
结论: 两参形式启动 watchdog, 三参形式(带leaseTime)关闭
```

---

## 附三、面试加练（3 题）

**1. Redisson 可重入锁怎么实现的？**
要点：锁值是 Hash 结构（UUID:threadId -> 重入计数）；加锁检查"持有者==自己"
则计数+1；解锁计数-1，归零才 DEL——synchronized 可重入语义的 Redis 版。

**2. 获取锁等待期间业务想取消怎么办？**
要点：tryLock 可超时返回（waitTimeMillis）；更精细用
lock.tryLock(wait, lease, unit) + 竞态检查；Redisson 还支持
lockInterruptibly 响应中断——本项目定时任务用 waitTime=0，用户操作用 3s。

**3. 锁的监控指标有哪些？**
要点：获取耗时分布（tryLock 等待时间）、竞争率（失败/成功）、锁持有时长、
锁 Key TTL 异常（长期不释放=看门狗失效嫌疑）——日志打点已有
（"获取分布式锁/释放分布式锁"），可加 metrics。

**今日金句**：锁的三问——谁需要（竞争域多大）、等多久（waitTime）、超了怎么办（幂等兜底）。

---

## 明日预告

Day 21 Redis 持久化、主从与 Cluster：Redis 侧的高可用。
