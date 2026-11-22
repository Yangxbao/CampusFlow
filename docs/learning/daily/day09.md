# Day 09 · MySQL 事务隔离级别与锁

> 阶段三第 2 天。今天回答：**并发插入/更新在 DB 层到底怎么表现的？**

## 一、今天学习什么

1. 隔离级别：读未提交/读已提交/可重复读（RR，MySQL 默认）/串行化；MVCC 快照读 vs 当前读；
2. 三类锁：行锁（Record Lock）、间隙锁（Gap Lock）、临键锁（Next-Key Lock）——RR 下防幻读的组合拳；
3. 本项目最相关的两个 DB 并发事实：
   - **唯一约束插入竞态**：两个事务同时插相同 (activity_id,user_id)，后者必然收到
     DuplicateKeyException（`confirmRegistration` 捕获后转幂等分支回查已有记录）；
   - **乐观锁并发更新**：UPDATE ... WHERE version=? 影响行数为 0 即冲突
     （cancelRegistration / CheckinService.scan / approvePublish 三处使用）；
4. 消费端为什么敢用"先查后插"：查（快照读）→ 插（当前读+唯一约束）的竞态窗口
   由唯一索引封死，这正是"DB 是最终防线"的含义。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/service/RegistrationTxService.java` | confirmRegistration 的 try/catch(DuplicateKeyException) 幂等分支；cancelRegistration 的 affected==0 分支 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/checkin/service/CheckinService.java` | scan 中 token CAS + registration CAS + uk_registration 兜底 |
| `backend/campusflow-server/src/test/java/com/campusflow/server/it/CancelConcurrencyIT.java` | 10 线程并发取消的测试构造 |
| `backend/campusflow-server/src/test/java/com/campusflow/server/it/CheckinConcurrencyIT.java` | 10 并发核销同一凭证 |

## 三、搞懂哪些八股

- RR 防幻读：快照读靠 MVCC（ReadView），当前读靠 Next-Key Lock；
- 乐观锁 vs 悲观锁选型（14 讲 Q26：洪峰下 select for update 占满连接池）；
- 死锁的典型形态：两个事务交叉更新两行；本项目规避方式——事务内更新顺序固定
  （先 token 后 registration，CheckinService 注释）、事务小而快；
- innodb_lock_wait_timeout 与超时重试语义。

## 四、运行哪些命令

```bash
mysql -uroot -p campusflow
-- 看当前隔离级别
SELECT @@transaction_isolation;    -- REPEATABLE-READ

-- 幻读实验：开两个会话
-- 会话A: BEGIN; SELECT * FROM registration WHERE activity_id=1;   -- 快照
-- 会话B: INSERT INTO registration ... (activity_id=1); COMMIT;
-- 会话A: 再 SELECT —— 看不到新行（RR 快照）；UPDATE 却能"看见"（当前读）
```

## 五、完成什么实验

1. **MVCC 快照 vs 当前读**：按上面双会话脚本做一遍，记录"SELECT 不可见 / COUNT 可见性 /
   UPDATE 影响 1 行"的完整现象；
2. **唯一约束竞态**：两个 mysql 会话同时 BEGIN、同时 INSERT 相同 (activity_id,user_id)，
   记录后者报错 `Duplicate entry`——这就是 confirmRegistration 幂等分支的前提；
3. **乐观锁竞态**：跑 `mvn -pl backend/campusflow-server test -Dtest=CancelConcurrencyIT`，
   读测试断言（名额只恢复一次、审计只一条），把 10 线程结果表贴进笔记。

## 六、面试自测（口头回答）

1. **消费端"先查后插"有并发窗口，为什么你的项目不怕？**
   （窗口内并发插入由 uk_activity_user 唯一约束拦截，捕获 DuplicateKeyException 后回查
   已有记录走幂等返回——约束把竞态变成确定性的分支， RegistrationTxService.confirmRegistration。）
2. **取消报名并发点两次会重复释放名额吗？**
   （不会：乐观锁 version 保证只有一个 UPDATE 成功，另一个 affected=0 幂等返回 false；
   Redis 侧 Lua SREM 幂等保证库存只 INCR 一次——CancelConcurrencyIT 双侧断言。）
3. **RR 下你怎么制造并观察一次幻读防护？**
   （会话 A 快照读两次结果一致；A 试图 UPDATE 其他会话新插入的行——当前读能看到并更新，
   因为 Next-Key Lock 在 A 当前读时已阻止不了已提交数据，讲解 RR 的边界与 1064 死锁检测。）
4. **什么情况下 MySQL 行锁会升级成表锁？**
   （没走索引的 UPDATE/DELETE 会锁全表扫描到的所有行——这也是 day08 强调索引的原因。）

## 七、自我检查标准

- [ ] 两个双会话实验完成并记录现象；
- [ ] CancelConcurrencyIT 跑通并能解释每个断言对应的防线；
- [ ] 能画出"先查后插+唯一约束"的并发时序图（两条插入线 + 一条被拒）；
- [ ] 说出项目三处乐观锁的位置与冲突后的行为。

## 附一、知识点详解

**1. "先查后插"竞态的三种封法（对比记忆）**
- 封法 A：唯一约束（本项目）——INSERT 撞 uk_activity_user，捕获转幂等；
  优点：不依赖额外组件；缺点：依赖 DB 行为；
- 封法 B：Redis 认领锁（tryBeginConsume L1a）——SETNX 60s 让并发串行进入；
  优点：不打 DB；缺点：锁过期时间与处理时长要协调；
- 封法 C：INSERT IGNORE / ON DUPLICATE KEY UPDATE——语义合并为原子；
  缺点：吞掉真实错误（区分不了约束冲突与其他失败）。
本项目消费幂等三层 = B(快) + A(稳) 的组合，C 不用（错误语义不清晰）。

**2. 乐观锁 affected==0 的三种处理策略（项目全用到）**
- 幂等返回（cancel：另一请求已成功，直接 return false）；
- 抛业务异常留给重试（idempotentConfirm 回迁失败 → REGISTRATION_STATE_CONFLICT →
  消息下轮重投）；
- 转换语义（approvePublish：审核并发冲突 → ACTIVITY_STATUS_CONFLICT 让用户刷新）。
选型依据：**操作是否可重试、冲突对用户是否可见**。

**3. RR 隔离级别对本项目的影响面**
消费端事务（confirmRegistration）：先查 activity（快照）再插 registration（当前读+约束）
——活动状态用的是事务开始时的快照，若恰逢活动被并发取消，DB 约束拦不住"按旧状态确认"。
防御在哪？消费者对"活动已取消"的 BizException 有专门分支（业务性拒绝），
且 releaseReservationInternal 会修正状态——**隔离级别不是万能，业务分支补位**。

**4. 死锁排查入口**
`SHOW ENGINE INNODB STATUS` 的 LATEST DETECTED DEADLOCK 段——能直接看到两个事务
各自持有的锁与等待的锁。项目通过"固定更新顺序"预防，无需依赖死锁检测重试。

## 附二、双会话实验记录格式

```
实验1 MVCC: A快照读两次结果: 一致/不一致? → ____
      A当前读(UPDATE)影响行数: ____ → 说明什么
实验2 唯一约束: 后插入的事务报错码: ____ (1216/1062?)
实验3 CancelConcurrencyIT: 成功取消次数=1, 释放库存次数=1, 审计条数=1
```

---

## 附三、面试加练（3 题）

**1. UPDATE 语句在 RR 下的"半一致读"是什么？**
要点：UPDATE 的 WHERE 扫描对不匹配行可能读旧版本（减少锁冲突），
匹配行仍走当前读加锁——这是 RR 的实现细节，解释了"UPDATE 看到的数据可能
与 SELECT 不完全一致"。本项目规避方式：状态变更全部基于主键/唯一键定位。

**2. innodb_deadlock_detect 开不开？**
要点：默认 ON（实时检测回滚代价小的事务）；热点行高并发场景（如秒杀单行更新）
可 OFF 换超时重试——本项目热点在 Redis，DB 无单行热点，保持默认。

**3. 间隙锁什么时候会伤性能？**
要点：RR + 范围当前读（SELECT ... FOR UPDATE）锁住间隙阻塞插入；
本项目事务都是主键/唯一键等值操作，无间隙锁负担——这也是"用唯一约束
代替范围锁"的设计理由。

**今日金句**：并发写入的正确性不靠祈祷，靠"约束+CAS+幂等"三件套。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 隔离级别/MVCC | 03_MySQL 事务章节 |
| 乐观锁实践 | 14_面试题 Q26 |

**容易说错的表述**：
- ✗ "RR 就没有幻读了" → ✓ "快照读靠 MVCC、当前读靠 Next-Key Lock，各有机制"；
- ✗ "乐观锁不会阻塞" → ✓ "不阻塞但冲突要处理，本项目冲突即幂等"。

---

## 明日预告

Day 10 缓存模式：Cache Aside 与多级缓存读链路。
