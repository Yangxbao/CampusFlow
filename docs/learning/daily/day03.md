# Day 03 · 数据库设计与领域模型

> 阶段一第 3 天。今天把 17 张表读薄成"4 个域 + 1 状态机 + 3 张辅助表"。

## 一、今天学习什么

1. 表按业务域归组：
   - 用户域：`t_user / t_role / t_user_role`（RBAC）；
   - 活动域：`activity`（capacity/confirmed_count/checked_in_count/version）；
   - 报名域：`registration / registration_state_log / registration_reservation`；
   - 候补签到域：`waitlist / checkin_token / checkin_record`；
   - 基础设施域：`message_deliver / message_consume_log / compensation_task /
     stock_audit_log / sys_audit_log / notify_message / export_task`；
2. 报名状态机（`RegistrationStatus`）：RESERVED(0) → CONFIRMED(1) → CHECKED_IN(2)；
   CONFIRMED → CANCELLED(3)（可复用行重报）；RESERVED → CONFIRM_FAILED(4)（补偿终态）；
3. 关键设计模式：`registration` 一行承载完整生命周期（含 reservation_id 外键关联），
   `registration_state_log` 全量轨迹，`registration_reservation` 记录 Redis 预留的 DB 凭证。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-server/src/main/resources/db/migration/V1__init_schema.sql` | 逐表过：主键/唯一键/索引/注释 |
| `backend/campusflow-common/src/main/java/com/campusflow/common/enums/RegistrationStatus.java` | 状态机 + occupiesStock/canCancel |
| `backend/campusflow-common/src/main/java/com/campusflow/common/enums/WaitlistStatus.java` | WAITING/PROMOTED/CONFIRMED/EXPIRED/CANCELLED |
| `backend/campusflow-common/src/main/java/com/campusflow/common/enums/ActivityStatus.java` | 活动状态流转（草稿→待审→发布→进行中→结束/取消） |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/entity/RegistrationDO.java` | DO 与表字段对应；version 乐观锁字段 |

## 三、必须记住的约束（面试直接引用）

```sql
-- registration 表（V1__init_schema.sql 90 行起）
PRIMARY KEY (id),                              -- 雪花 ID
UNIQUE KEY uk_activity_user (activity_id, user_id),   -- 一人一单最终防线
KEY idx_user_status (user_id, status),                -- "我的报名"查询（也是分片键索引）
-- version INT 乐观锁；status 状态机；registered_at/cancelled_at/checkin_at 全轨迹
```

- `message_consume_log`：`uk_msg_group(msg_key, consumer_group)`——消费幂等 DB 防线；
- `compensation_task`：`uk_biz_key`——补偿任务幂等创建；
- `waitlist`：`uk_activity_user`——候补唯一；
- `checkin_record`：`uk_registration`——签到唯一；
- 逻辑删除：`deleted` 字段（mybatis-plus global-config logic-delete-field，见 application.yml）。

## 四、搞懂哪些八股

- 为什么"一人一单"用唯一索引而不是应用层判断？（并发窗口 + 不依赖中间件状态，
  `IdempotencyIT.数据库唯一约束拦截绕过Redis的重复报名` 直接验证）；
- 乐观锁字段 version 的标准用法（affected=0 即冲突，MyBatis-Plus 自动拼 WHERE version=?）；
- 逻辑删除 vs 物理删除：CANCELLED 行保留用于审计与重报复用（idempotentConfirm 状态回迁）；
- DATETIME vs TIMESTAMP、TINYINT 存状态码的取舍。

## 五、运行哪些命令

```bash
mysql -uroot -p campusflow -e "SHOW TABLES;"
mysql -uroot -p campusflow -e "SHOW CREATE TABLE registration\G"
mysql -uroot -p campusflow -e "SELECT status, COUNT(*) FROM registration GROUP BY status;"

# 种子数据里有哪些账号（V2__seed_base_data.sql）
grep -n "INSERT" backend/campusflow-server/src/main/resources/db/migration/V2__seed_base_data.sql | head
```

## 六、完成什么实验

1. **状态机具象化**：手工执行一次报名（curl），随后在 DB 里看三张表的变化：
   `registration_reservation` 出现 1 行 PENDING_CONFIRM → 消费后 `registration` 出现 CONFIRMED 行 →
   `registration_state_log` 出现轨迹行。截三张表快照，今天交付；
2. **约束验证**：手动对同一 (activity_id,user_id) 插第二条 registration，
   观察 `Duplicate entry ... uk_activity_user` 报错；
3. 画 ER 图（核心 8 张即可），标出所有 UNIQUE KEY。

## 七、面试自测（口头回答）

1. **报名表为什么还要一张 reservation 表？**
   （Redis 预留成功到 MQ 确认之间需要一个 DB 凭证：对账/补偿的锚点，messageKey 挂在它身上，
   超时由 CompensationScheduler.reservationRecovery 扫描。）
2. **取消报名的记录会被删掉吗？为什么？**
   （不删；status=CANCELLED 保留行用于审计，重新报名复用同一行状态回迁，
   唯一索引不冲突——RegistrationTxService.idempotentConfirm。）
3. **一人一单到底靠什么保证？说三层。**
   （Lua SISMEMBER 前置 → Service 防御性校验 → MySQL uk_activity_user 最终防线。）
4. **registration_state_log 解决什么问题？**
   （每次状态迁移写 from/to/reason/operatorId，出问题能回放操作序列——状态机配审计的标配。）

## 八、自我检查标准

- [ ] 17 张表能按 5 个域分组默写；
- [ ] registration 的三个 UNIQUE/索引和用途说得出；
- [ ] ER 图完成（8 张核心表）；
- [ ] 实验 1 的三表快照完成，能解释每张表何时插入。

## 附一、知识点详解

**1. 17 张表的三层分类法（比"背表名"有效）**
- 事实表（业务核心，数据最多）：registration、checkin_record、notify_message；
- 状态/凭证表（生命周期载体）：activity、registration_reservation、registration_state_log、
  waitlist、checkin_token；
- 治理表（系统自用，面试容易被忽略）：message_deliver、message_consume_log、
  compensation_task、stock_audit_log、sys_audit_log、export_task、t_user/t_role/t_user_role。
面试亮点：治理表的存在本身就是设计——**消息轨迹、消费轨迹、补偿轨迹、库存轨迹、操作轨迹**
五类轨迹全部落库，这是"最终一致"能被审计的前提。

**2. registration_reservation 与 registration 的关系（易混淆点）**
reservation 是"Redis 预留成功 → MQ 确认完成"这段窗口期的 DB 凭证：
- messageKey 挂在它身上（REG-{id}），用于事务提交后立即检索消息记录重发；
- 超时由 reservationRecovery 扫描（expire_at 字段）；
- 确认完成后它被 markConfirmed（不删，留审计）。
registration 则是"最终事实"，确认前可能不存在（还在窗口期）。

**3. version 乐观锁字段在哪三张表出现**
activity（审核/状态流转）、registration（取消/回迁）、waitlist（晋级 CAS 用
markPromoted 的 WHERE 条件实现）。统一用"影响行数=0 即冲突"的语义处理。

**4. 为什么 status 用 TINYINT 而不是 VARCHAR**
存储与索引效率 + 枚举映射（RegistrationStatus.ofCode）保证代码侧类型安全；
desc 文案放枚举不放库——展示文案与数据解耦，改文案不动数据。

## 附二、ER 图必须标注的 5 个"关键点"

1. activity.capacity 与 confirmed_count 的口径差（confirmed_count 是 MQ 消费端维护的对账值）；
2. registration.reservation_id → registration_reservation 的单向关联；
3. message_deliver.msg_key 与 registration_reservation.message_key 的同值关联（闭环核对用）；
4. waitlist.confirm_deadline（PROMOTED 后的确认死线）；
5. checkin_record.uk_registration 唯一约束（核销恰好一次）。

---

## 附三、面试加练（3 题）

**1. 逻辑删除字段与唯一索引会冲突吗？**
要点：会——deleted=1 的旧行与 deleted=0 的新行同键。本项目 uk_activity_user
不含 deleted（一人一行是硬约束，取消后重报靠"行复用/状态回迁"而非删行重建），
这是有意设计；若用"含 deleted 的可变唯一索引"方案则允许历史多行，审计口径变复杂。

**2. registration_reservation 以后怎么归档？**
要点：状态 CONFIRMED/RELEASED 的记录按月归档（保留字段即可追溯）；
活跃扫描只针对 PENDING_CONFIRM——扫描查询与归档策略配套设计。

**3. t_user 的密码字段怎么设计？**
要点：BCrypt 摘要（自带盐，60 字符），无明文无 MD5；登录失败计数/锁定策略
可加列；审计上密码变更走 sys_audit_log——安全设计是表设计的一部分。

**今日金句**：看一张表的 UNIQUE KEY 就知道它防什么事故——约束是写下来的架构。

---

## 明日预告

Day 04 进入阶段二（Spring 核心与工程规范）：Bean 装配与启动过程。
