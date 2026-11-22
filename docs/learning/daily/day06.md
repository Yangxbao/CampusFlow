# Day 06 · 事务边界与 AOP 实战

> 阶段二第 3 天。两个主题：@Transactional 的正确用法（含失效全景）与
> 项目里的两个 AOP 切面（分布式锁切面 + 审计切面）。

## 一、今天学习什么

1. **事务边界集中管理**：`RegistrationTxService` 是报名域所有 DB 变更的唯一事务入口
   （类注释原文："本类方法均带 @Transactional 且被外部类调用（避免同类自调用代理失效）"）；
   编排层 `RegistrationService` 无事务，只做"调 Lua → 调 TxService → 调 MQ"的串联；
2. 统一 `rollbackFor = Exception.class`（默认只回滚 RuntimeException，受检异常不回滚）；
3. AOP 切面 1：`DistributedLockAspect`（@Around + SpEL 解析 key + tryLock + isHeldByCurrentThread 解锁）；
4. AOP 切面 2：`AuditLogAspect`（biz/audit 包，操作审计切面，配 SysAuditService 落 sys_audit_log）；
5. 事务 + 锁的组合套路：`ActivityService.approvePublish` 同时挂
   `@DistributedLock("activity:publish:"+id)` 与 @Transactional——锁保护并发审核，事务保证原子。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/service/RegistrationTxService.java` | 类注释 + 三个 @Transactional 方法（persist/confirm/cancel） |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/lock/DistributedLock.java` | 注解定义：key/waitTimeMillis/leaseTimeMillis/skipOnFail |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/lock/DistributedLockAspect.java` | 全文：SpEL 解析、看门狗分支、解锁校验、锁误删三防护注释 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/audit/AuditLogAspect.java` | 第二个切面的写法 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/activity/service/ActivityService.java` | 218 行 approvePublish：锁+事务组合 |

## 三、搞懂哪些八股（事务失效全景，面试高频）

| 失效场景 | 项目对策 |
|---|---|
| 同类自调用（this.method 不走代理） | 编排层/事务层两类拆分 |
| 方法非 public | 事务方法全部 public |
| rollbackFor 未设（受检异常不回滚） | 统一 rollbackFor=Exception.class |
| catch 后吞异常继续 | 事务方法内异常一律上抛（BizException 是 RuntimeException 也回滚） |
| 传播行为误用 | 未用 REQUIRES_NEW 等花活，默认 REQUIRED 足够 |
| 多线程内调用（事务绑定 ThreadLocal 连接） | 消费端串行处理，事务方法内不开线程池 |

## 四、运行哪些命令

```bash
# 验证唯一约束兜底进幂等分支（跑既有 IT）
mvn -pl backend/campusflow-server test -Dtest=IdempotencyIT

# 观察 AOP 生效：发布活动触发 @DistributedLock，日志里搜锁 key
grep "获取分布式锁\|释放分布式锁" backend/logs/campusflow.log
grep "锁:aspect:activity:publish" backend/logs/campusflow.log
```

## 五、完成什么实验

1. **自调用失效复现**：写一个临时 Service，类内 methodA 调 this.methodB（methodB 带
   @Transactional 且内部 throw），运行后 DB 发现 methodA 的插入没回滚（事务没生效）；
   再拆成两个 Bean 调用，回滚生效。记录对比截图，**这是面试的杀手锏素材**；
2. **锁注解实验**：在临时 Controller 写 `@DistributedLock(key="'demo:'+ #id")` 方法，
   两个线程并发调，观察日志只有一把锁获得、另一个等待 3s 后 LOCK_TIMEOUT(30001)；
3. **锁误删防护理解**：读 DistributedLockAspect 注释三条防护，口头复述
   （看门狗 / Redisson 值含 UUID:threadId 校验持有者 / isHeldByCurrentThread）。

## 六、面试自测（口头回答）

1. **@Transactional 失效场景说五个**（按上表，并说出项目怎么防）；
2. **为什么编排层与事务层要拆？**
   （自调用代理失效 + 职责清晰：编排层决定"做什么顺序"，事务层决定"哪些原子"；拆开让
   事务边界一目了然。）
3. **@DistributedLock 的 SpEL key 怎么解析？失败会怎样？**
   （DistributedLockAspect.resolveKey：DefaultParameterNameDiscoverer 拿参数名 +
   SpelExpressionParser 求值；解析失败抛 IllegalStateException 快速失败。）
4. **锁和事务一起用，谁在外层？为什么？**
   （锁在外层：锁先拿到，事务提交后锁才释放，保证"事务可见性 + 并发互斥"同时成立；
   若事务外锁内，事务提交前其他线程可能拿到锁读到未提交数据。）

## 七、自我检查标准

- [ ] 自调用失效实验完成且能讲清代理原理（JDK 动态代理/CGLIB）；
- [ ] 能背出 DistributedLock 四个属性及默认值（waitTimeMillis=3000/leaseTimeMillis=-1/skipOnFail=false）；
- [ ] 说出 leaseTimeMillis<=0 时启用看门狗的分支逻辑；
- [ ] 找到项目中 3 处 @DistributedLock 使用点（activity:publish / activity:status / activity:capacity）。

## 附一、知识点详解

**1. 事务方法内的"写顺序"为什么重要（死锁预防）**
`CheckinService.scan` 固定先更新 checkin_token 再更新 registration——事务内多行更新的
顺序全局一致是死锁预防的基本功（两个事务交叉持锁才会死锁）。同理，所有事务方法都
遵循"先查后改、改动行数最少、事务最短"三原则。

**2. @DistributedLock 的 SpEL key 求值细节**
`DefaultParameterNameDiscoverer` 需要 `-parameters` 编译参数（或 debug 信息）才能拿到
参数名——pom.xml 的 maven-compiler-plugin 已配置。解析失败会抛
`IllegalStateException("分布式锁 SpEL 解析失败")` 快速失败，而不是"锁全串一把"。
这个细节说明：**注解式锁的 key 必须在编译期可确定**。

**3. 看门狗模式的两个适用判断**
- 用看门狗（leaseTime<=0）：业务耗时不定（预热要对账 DB、晋级要走完整链路）；
- 用显式 leaseTime：业务耗时确定且短（抢不到锁快速返回的场景）。
误用的代价：显式 leaseTime 小于业务 P99 时锁提前过期 → 并发进入 → 靠幂等兜底
（DistributedLockAspect 注释第三条明示这个残余窗口）。

**4. AOP 双切面的执行顺序**
@DistributedLock(@Around) 与 @Transactional 在同一方法（approvePublish）上时，
锁在外事务在内才正确。验证方法：看日志"获取分布式锁"先于"事务开始"（若有 SQL 日志）。
若顺序反了，事务提交前锁已释放，其他线程能读到未提交数据。

## 附二、事务失效自调用实验的完整步骤

```
1. 临时类 DemoService { 
     @Transactional void a() { insert(); this.b(); }   // 同类自调用
     @Transactional void b() { throw new RuntimeException(); }
   }
2. 调 a()：b 抛异常但 a 的 insert 不回滚（b 的事务没被代理）
3. 拆成 DemoA{a(){demoB.b()}} / DemoB{@Transactional b()}
4. 再调：a 的 insert 回滚
结论：事务是代理织入的，绕过代理=绕过事务。
```

---

## 附三、面试加练（3 题）

**1. 事务提交后再发消息，代码上怎么保证"提交后才发"？**
要点：本项目把发送放在事务方法返回之后（编排层调用）——结构上天然保证；
另一方案 TransactionSynchronizationManager.registerSynchronization 的
afterCommit 回调。注意 afterCommit 里异常不会回滚事务（已提交），所以本项目
发送失败走的是"本地消息表重投"而不是重抛。

**2. 多个 @Around 切面的顺序怎么控制？**
要点：@Order/@Priority 注解；值越小越外层。本项目锁切面需要在外层
（锁→事务），可通过 @Order(-1) 显式声明或保持类名顺序并加注释——
**切面顺序是隐式契约，必须注释**。

**3. REQUIRED 与 REQUIRES_NEW 各适合什么场景？**
要点：REQUIRED 默认加入外层事务（原子性）；REQUIRES_NEW 挂起外层独立提交
（审计日志即使业务回滚也要留痕）——本项目审计走独立服务+异步，避免了
REQUIRES_NEW 的连接占用问题（挂起事务会占用两个连接，连接池要算上）。

**今日金句**：锁防的是并发，事务防的是半成品，AOP 把这两件事从业务代码里摘出去。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 事务边界 | 02_SpringBoot 事务章节；15_源码路线 Day5 |
| 分布式锁 | 07_分布式锁与一致性 第 1-2 节 |
| 线程池 | 08_JUC 第 1 篇（lab 01） |

**容易说错的表述**：
- ✗ "锁加在事务方法上就行" → ✓ "锁在外层包住事务（切面顺序）"；
- ✗ "rollbackFor 默认全回滚" → ✓ "默认只回滚 RuntimeException，项目统一 Exception.class"。

---

## 明日预告

Day 07 阶段复盘一 + 八股测验：前六天知识自测收网。
