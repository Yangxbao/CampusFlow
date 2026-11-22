# Day 15 · 状态机、乐观锁与消费幂等

> 阶段四第 4 天。三个主题环环相扣：状态机定义合法迁移，乐观锁实现迁移 CAS，
> 消费幂等保证重复消息不触发非法迁移。

## 一、今天学习什么

1. **状态机**（`RegistrationStatus` + `RegistrationTxService`）：
   - 确认链路 RESERVED→CONFIRMED→CHECKED_IN；
   - 取消链路 CONFIRMED→CANCELLED（行复用，重新报名状态回迁）；
   - 异常链路 RESERVED→CONFIRM_FAILED（补偿终态）；
   - `occupiesStock()`：RESERVED/CONFIRMED/CHECKED_IN 占名额——所有对账口径的基准；
   - 每次迁移写 `registration_state_log`(from/to/reason/operatorId)。
2. **乐观锁**：version 字段 + updateById 影响 0 行 = 冲突。
   - cancel：冲突即幂等（另一请求已成功）；
   - confirm 的状态回迁：affected==0 抛 REGISTRATION_STATE_CONFLICT 留给下次投递重试；
3. **消费幂等三层**（`MessageConsumeLogService.tryBeginConsume`）：
   L1a Redis 认领锁 SETNX 60s（并发重复消息互斥进入）→ L1b Redis processed 标记 24h
   （快速跳过）→ L2 DB uk_msg_group（Redis 丢失后最终防线）；
   markFailed 后允许重试（状态可回），markSuccess 永久拦截。
4. 底层业务幂等（第二层）：`confirmRegistration` 三保险——唯一约束 / 幂等分支
   （CONFIRMED 直接返回）/ 取消后回迁。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-common/src/main/java/com/campusflow/common/enums/RegistrationStatus.java` | 状态机全文 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/service/RegistrationTxService.java` | confirmRegistration + idempotentConfirm + cancelRegistration |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/mq/MessageConsumeLogService.java` | tryBeginConsume 全文（注释逐行） |
| `backend/campusflow-server/src/test/java/com/campusflow/server/it/IdempotencyIT.java` | 两个测试：唯一约束拦截 / 重复消费安全 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/entity/RegistrationStateLogDO.java` | 状态日志字段 |

## 三、搞懂哪些八股

- 状态机的价值：**定义"合法迁移集合"**，非法迁移直接拒绝（canCancel/occupiesStock 就是机器可读的规则）；
- 幂等的本质分类：天然幂等（SETNX/唯一约束）、CAS 幂等（状态推进）、补偿幂等（bizKey 审计）；
- "消息至少一次投递 + 业务幂等 = 恰好一次效果"的因果（At-least-once 不可避免，
  用幂等把重复变成 no-op）；
- 乐观锁与状态机的关系：乐观锁是迁移的原子执行器，version 失败=迁移竞争失败=按语义幂等或重试。

## 四、运行哪些命令

```bash
# 状态日志时间线
mysql -e "SELECT registration_id,from_status,to_status,reason,operator_id,created_at
          FROM registration_state_log ORDER BY id DESC LIMIT 10;"

# 消费幂等表
mysql -e "SELECT msg_key,consumer_group,status,retry_count FROM message_consume_log
          ORDER BY id DESC LIMIT 10;"

# 跑幂等 IT
mvn -pl backend/campusflow-server test -Dtest=IdempotencyIT
```

## 五、完成什么实验

1. **重复消费实验**：跑 IdempotencyIT 第二个测试——同一确认消息执行两次 confirmRegistration，
   确认 DB 只有一行；在日志里找到"报名确认幂等命中"；
2. **状态回迁实验**：取消一个已确认报名（状态 CANCELLED），再对同一用户发一条
   RegistrationConfirmMessage 消费——观察同一行状态回迁 CONFIRMED（唯一索引不冲突的设计）；
3. **认领锁观察**：手动把同一 msgKey 的消息发两次（producer.send），观察日志
   "消费认领锁命中（并发重复消息）"或"消费幂等命中(Redis)"。

## 六、面试自测（口头回答）

1. **消费幂等为什么要三层？只留 DB 唯一约束不行吗？**
   （只靠 DB：并发重复投递存在检查-插入竞态窗口（先查后插不是原子的），
   且每条消息都打 DB 浪费；L1a 认领锁解决并发互斥、L1b 解决已处理快速跳过、
   L2 是 Redis 失效后的兜底——快/省/稳三层各司其职。）
2. **取消后重新报名，为什么唯一索引不会冲突？**
   （行复用：idempotentConfirm 对 CANCELLED 行做状态回迁（UPDATE 而非 INSERT），
   registered_at/cancelled_at 重置——一个用户在一张表永远最多一行，审计也连续。）
3. **消息重试时业务状态已经推进了怎么办？**
   （幂等命中直接返回成功，消费标记 SUCCESS——"状态机已到目标态"与"消息重复"
   两个事实合并成一次 no-op；这就是幂等设计要兜住的第一场景。）
4. **乐观锁冲突在报名确认里怎么处理？**
   （回迁分支 affected==0 → 抛 REGISTRATION_STATE_CONFLICT → 消费端按普通失败
   RECONSUME_LATER 下次重投再试——冲突不是错误，是"请再排队"。）

## 七、自我检查标准

- [ ] 状态机图（含 5 状态 + 3 链路）默写完成；
- [ ] 三个实验完成并留下日志证据；
- [ ] tryBeginConsume 三层防线逐行讲清（含 60s 认领锁的为什么）；
- [ ] 能把"幂等三层 + 业务三保险"合并成一张 2×3 表格背出来。

## 附一、知识点详解

**1. 认领锁 60s 的"为什么是 60"**
处理上限 = consumeTimeout 5 分钟（Broker 侧）与认领锁 60s（应用侧）的协调：
业务处理一般秒级完成；崩溃后 Broker 递增间隔重投（默认首次 10s+），
60s 锁过期保证重投消息不被旧锁拦截。**锁 TTL 不是拍脑袋，是"处理时长 P99 + 重投节奏"推导**。

**2. 幂等回迁 vs 新插入：为什么复用同一行**
新插入会撞 uk_activity_user（同用户唯一）；复用行做 UPDATE 状态回迁既绕开约束
又保持审计连续（同一 registration_id 的日志能串起"报名-取消-重报"全过程）。
副作用：registered_at 被重置为最新一次报名时间——审计上"当前有效报名"口径更合理。

**3. 状态机的"非法迁移"清单（机器可读的规则）**
- CANNOT_CANCEL_CURRENT_STATUS：CHECKED_IN/FINISHED 不可取消（canCancel()=false）；
- idempotentConfirm 对未知状态抛 IllegalStateException（防御编程）；
- confirmRegistration 对已取消活动走释放而非确认——**活动状态与报名状态的联动规则**。

**4. 消费幂等表为什么按 (msg_key, consumer_group) 联合唯一**
同一消息可能被多个消费组消费（例如后续加一个"统计组"）——幂等是"组内"语义。
如果只按 msg_key 唯一，第二个组永远消费不到。**唯一键的粒度 = 幂等的范围**，
这是个通用设计问题（关联：waitlist 的 uk_activity_user、checkin 的 uk_registration）。

## 附二、实验观察点

```
实验1 重复消费: 两次 confirmRegistration 后行数=1? 日志"幂等命中"出现? Y/N
实验2 状态回迁: 取消→重投消息→同一行回 CONFIRMED? state_log 新增条数=___
实验3 认领锁: 双发同 msgKey 时"认领锁命中"日志出现? processed 标记 TTL=___h
```

---

## 附三、面试加练（3 题）

**1. 幂等标记（Redis processed）的 TTL 设 24h 合理吗？**
要点：够——幂等标记只是"快路径"，真正的永久防线是 DB 唯一约束与状态 CAS；
24h 覆盖了 Broker 重投的全部窗口（延迟级别最高 2h×次数）。**快路径 TTL 可短，
慢路径必须永久**。

**2. 用唯一约束做幂等和用分布式锁做幂等，区别？**
要点：唯一约束是"结果幂等"（重复插入必然失败），锁是"过程幂等"（重复进入
被串行）——约束更强且无锁成本；锁适合"约束表达不了"的场景（如防并发预热）。
本项目两层都用且分工明确。

**3. 状态机要不要用工作流引擎（如 Flowable）？**
要点：本项目 5 状态 3 链路，手写状态机 + 枚举规则方法（occupiesStock）
更轻更直观；流程复杂（审批多级/人工介入/动态分支）才上引擎。
**复杂度不到，别引入框架**。

**今日金句**：消息至少一次是宿命，业务幂等是修养——两者相遇才有"恰好一次"。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 状态机 | 03_数据库设计 / 14_面试题 Q12 |
| 消费幂等 | 06_RocketMQ 第 4 节；14_面试题 Q5 |

**容易说错的表述**：
- ✗ "幂等就是 setnx" → ✓ "三层：认领锁/processed 标记/DB 唯一约束，业务层还有状态 CAS"；
- ✗ "消息重复会重复报名" → ✓ "唯一约束+幂等分支保证重复消费是 no-op"。

---

## 明日预告

Day 16 补偿体系与最终一致：六个定时任务总收网。
