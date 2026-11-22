# Day 22 · 一致性专题：对账与审计（阶段六收网）

> 阶段六第 3 天。今天把散落各处的"一致性手段"收拢成一个可复用的方法论。

## 一、今天学习什么

**一致性工具箱盘点（本项目全部实战过）**：

| 手段 | 位置 | 解决什么 |
|---|---|---|
| Lua 原子执行 | reserve/cancel_release/waitlist_serve | 检查与变更的原子性（防竞态） |
| DB 唯一约束 | uk_activity_user/uk_msg_group/uk_biz_key/uk_registration | 不依赖中间件的最终防线 |
| 乐观锁 CAS | version 字段 | 并发状态迁移恰一次 |
| TTL 安全阀 | 预留 Key EX / 空值占位 / 布隆重建 | 状态滞留自愈 |
| 对账修复 | stockReconcile（DB 权威口径） | 漂移收敛 |
| 审计留痕 | stock_audit_log(bizKey 幂等) / registration_state_log / sys_audit_log | 可追溯、防重复记账 |
| 发件箱 | message_deliver + CONFIRMED 闭环 | 投递轨迹持久化 |

**审计系统的三个设计点**（StockAuditService）：
1. bizKey 幂等：`CANCEL-{regId}` / `COMP-REL-{reservationId}` / `RECONCILE-{aid}-{窗口}`——
   同一业务事件重复补偿只记一次账；
2. source 分型：CANCEL/COMPENSATE/WAITLIST_TIMEOUT/RECONCILE——对账时可按来源分解漂移成因；
3. 变更量与操作人：change 数值 + operatorId，审计报表直接可用。

**状态日志**：registration_state_log 每次迁移一行（from/to/reason/operatorId），
配合状态机形成"操作回放"能力。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-business/src/main/java/com/campusflow/biz/audit/StockAuditService.java` | bizKey 幂等记账 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/audit/entity/StockAuditLogDO.java` | source 常量与字段 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/audit/SysAuditService.java` | 管理操作审计（AuditLogAspect 落库） |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/compensation/scheduler/CompensationScheduler.java` | doStockReconcile 修复+审计+告警三连 |
| `docs/learning/15_项目源码阅读路线.md` Day6 | 补偿体系阅读笔记对照 |

## 三、搞懂哪些八股

- CAP 再认识：本项目取 AP（可用+分区容忍），用"对账+TTL"收敛 C——
  面试时避免把 CAP 背成教条，结合方案讲取舍；
- 对账的两种模式：定时全量（本项目 5min）vs 事件驱动实时对账（binlog/流水比对）；
- 幂等键（bizKey）设计三原则：业务语义唯一、时间窗防碰撞、可反查（从键能定位业务对象）；
- 审计与日志的区别：日志给人看（排障），审计给系统/合规看（记账、追责、报表）。

## 四、运行哪些命令

```bash
# 审计时间线（按来源分解）
mysql -e "SELECT source,COUNT(*),SUM(change_count) FROM stock_audit_log GROUP BY source;"
mysql -e "SELECT * FROM stock_audit_log WHERE biz_key LIKE 'RECONCILE-%' ORDER BY id DESC LIMIT 5;"

# 状态日志回放某用户
mysql -e "SELECT from_status,to_status,reason,created_at FROM registration_state_log
          WHERE user_id=2 ORDER BY id;"

# 对账任务现场
grep "库存漂移\|库存对账" backend/logs/campusflow.log | tail
```

## 五、完成什么实验

1. **重复补偿幂等验证**：把同一条预留手动触发两次释放（改 expire_at 两次到过去），
   观察 stock_audit_log 只有 1 条 `COMP-REL-{id}`（bizKey 去重）——亲手验证审计幂等；
2. **漂移分解实验**：人为制造两种漂移（①报名后手动改 Redis 库存；②直接改 DB registration
   状态），对账后按 source 看记录差异，写 5 行复盘；
3. **口径确认**：手算某活动 expected = capacity - CONFIRMED/CHECKED_IN 数，
   与 stockReconcile 的计算逻辑对照（activityServiceCountActive）。

## 六、面试自测（口头回答）

1. **如果 Redis 和 DB 数据不一致了，你怎么发现和修复？**
   （发现：5min 对账任务（阈值 3 才修，容忍微抖动）；修复：DB 权威口径重建预热；
   留痕：审计 bizKey 唯一 + 告警任务；根因：按 source 分解审计定位漂移来源。）
2. **审计为什么也要幂等？**
   （补偿/重试可能重复执行，业务幂等只保证状态不错，审计若重复记账则报表失真——
   bizKey 唯一让"副作用唯一"覆盖到记账层。）
3. **你的系统有没有"强一致"需求的地方？**
   （有：同一用户的报名唯一性——由 DB 唯一约束保证（当前读+约束即强一致）；
   其余（库存展示/统计）均为最终一致。区分"唯一性强一致"与"数值最终一致"是关键。）
4. **怎么设计一个通用对账框架？**
   （抽象三要素：对账口径（SQL/表达式）、周期调度（分布式锁）、差异处理（修复+告警+审计）；
   本项目的 stockReconcile 就是这三要素的具象化，可推广到消息表/候补/统计。）

## 七、阶段六总复盘（day20-22 收网测验）

- [ ] 一致性工具箱 7 行表格默写（每行有类名）；
- [ ] 审计幂等实验完成；
- [ ] 讲义 14 的 Q8/Q16/Q17/Q18 口头回答达标；
- [ ] 综合题自测："面试官说你的方案依赖太多中间件，怎么辩护？"——
      答法：每层中间件都对应一类故障（Lua 防竞态/MQ 防峰值/锁防重入/对账防漂移），
      且每层都有"中间件失效时的降级路径"（快速失败/DB 约束/幂等跳过），不是依赖堆砌。

## 附一、知识点详解

**1. 五类轨迹表的"审计价值"对照（项目治理设计总览）**

| 轨迹表 | 记什么 | 回答什么问题 |
|---|---|---|
| message_deliver | 投递状态机 NEW→SENT→CONFIRMED | 消息到底发出去了吗 |
| message_consume_log | 消费结果与重试次数 | 这条消息消费了几次 |
| registration_state_log | 状态迁移 from→to | 这个报名经历过什么 |
| stock_audit_log | 库存变更+来源+操作人 | 名额为什么会变成这样 |
| compensation_task | 补偿任务与重试 | 系统替用户做过哪些修复 |

面试表达："我的项目里**每一个异步副作用都有轨迹表**"——这是最终一致系统可运维的前提。

**2. bizKey 命名规范的三段式**
`来源-业务对象-防碰撞`：CANCEL-{regId} / COMP-REL-{reservationId} /
WLTO-{waitlistId} / RECONCILE-{aid}-{时间窗}。可反查性是关键：
从审计行能直接定位到业务记录（不用二次关联）。

**3. 漂移的"健康容忍带"**
reconcile-threshold=3：漂移 <3 不修——**对账本身有代价（COUNT 全表），
微小漂移多为计数窗口内的正常时序差**，修复反而制造抖动。阈值是"修正成本 vs 风险"
的权衡参数，不是 0。

**4. 一致性方案答辩的"三层递进"话术**
① 正确性层：Lua 原子 + 唯一约束（永不超卖——强保证）；
② 时效层：MQ 异步 + TTL（状态流转有界延迟）；
③ 收敛层：对账 + 审计（任何漂移最终被修复且可追溯）。
把方案拆成三层，面试官的每个追问都能落到一层。

## 附二、漂移分解实验记录

```
制造漂移①: 手改 Redis 库存 → 对账 source=RECONCILE 记录 change=___
制造漂移②: 手改 DB 状态 → 对账 expected 重算 → 修复方向___
重复触发: 两次释放同一预留 → audit 行数=1 (bizKey=___)
```

---

## 附三、面试加练（3 题）

**1. 对账任务自己也会失败，怎么办？**
要点：对账动作本身幂等（bizKey 时间窗）+ 异常单活动隔离（try-catch per activity）
+ 下一轮自然重试；持续失败进入 compensation_task 人工通道——**兜底组件自身
也要有兜底**。

**2. 审计数据的合规要求有哪些？**
要点：不可篡改（只插入不更新）、保留期（sys_audit_log 按合规 180 天+）、
敏感信息脱敏（手机号打码）、查询权限管控——审计表的设计目标是"经得起翻旧账"。

**3. 双写（写两处）和对账，什么时候选哪个？**
要点：双写一致性窗口小但代码侵入大、失败模式多；对账无侵入但收敛有延迟。
本项目主数据单写（DB 权威）+ 派生数据对账（Redis/统计）——**能单写就别双写**。

**今日金句**：最终一致不是"事后不管"，而是"事后必管且管得可追溯"。

---

## 明日预告

Day 23 进入阶段七（JUC 实验周）：线程池与 synchronized。
