# Day 16 · 补偿体系与最终一致（阶段四收网）

> 阶段四第 5 天。今天把 `CompensationScheduler` 的六个任务读成一张"故障-兜底"对照表，
> 然后做一次阶段复盘。

## 一、今天学习什么

六任务对照表（`CompensationScheduler`）：

| 任务 | 频率 | 兜什么故障 | 幂等依据 |
|---|---|---|---|
| reservationRecovery | 20s | 预留超时未确认（消息丢失/消费失败） | 分支：已确认→修预留状态；重发消息；超 reservation-max-retry(5)→幂等释放+通知 |
| messageRetry | 15s | 消息发送失败 | 本地消息表状态 + 指数退避 next_retry_time |
| waitlistTimeout | 30s | 候补晋级超时未确认 | Lua SREM 幂等释放 + markExpired CAS + 晋级下一位 |
| waitlistBackfill | 60s | 漏晋级（库存>0 且队列非空） | tryPromoteNext 本身幂等（锁内 ZPOPMIN） |
| stockReconcile | 5min | Redis/DB 库存漂移、预热丢失 | DB 权威口径重建 + 审计 bizKey(RECONCILE-aid-时间窗) 唯一 + 告警任务 |
| activityStatusSync | 60s | 活动状态滞留（未自动开始/结束） | 状态 CAS + evictStockKeys |

三个设计原则（背下来）：
1. **锁防重入，幂等防叠加**——多实例用 Redisson tryRun 抢锁（抢不到静默跳过），
   任务内部所有操作幂等，重复执行不产生副作用叠加；
2. **DB 是对账权威**——所有修复以 `capacity - 有效报名数` 为准，Redis 只是热路径；
3. **修复必留痕**——stock_audit_log 记账 + compensation_task 告警，异常漂移可追溯。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-business/src/main/java/com/campusflow/biz/compensation/scheduler/CompensationScheduler.java` | 全文六任务（今天主教材，约 320 行） |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/compensation/service/CompensationTaskService.java` | createTask 幂等 + processDueTasks |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/listener/CancelReleaseConsumer.java` | 取消释放消息的消费端兜底（与同步触发互补） |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/audit/StockAuditService.java` | bizKey 幂等记账实现 |
| `backend/campusflow-server/src/test/java/com/campusflow/server/it/CompensationRecoveryIT.java` | 重试耗尽转补偿/预留释放/对账修复的自动化验证 |

## 三、搞懂哪些八股

- 最终一致 vs 强一致：本项目"Redis 权威 + MQ 异步 + 对账收敛"，窗口期内可短暂不一致，
  任何时刻 DB+审计可重建真相；
- 分布式定时任务的三大方案：分布式锁（本项目）/ 分片调度（xxl-job）/ 纯调度去重（quartz 集群）；
- 对账系统的三要素：对账口径（谁权威）、对账周期、漂移处理（修复+告警）；
- TTL 作为安全阀：预留状态 EX TTL 15min——即使所有补偿失效，Key 过期后自愈窗口闭合。

## 四、运行哪些命令

```bash
# 六任务的锁 Key 观察锁竞争
redis-cli -a 123321 KEYS 'campusflow:lock:job:*'
grep "库存对账\|预留恢复\|本地消息表重投" backend/logs/campusflow.log | tail -20

# 手工制造一个"消息丢失"场景：删掉某条 message_deliver 记录（实验用）
mysql -e "SELECT id,msg_key FROM message_deliver ORDER BY id DESC LIMIT 1;"
# 等待 reservationRecovery 发现本地消息表缺记录 → 重新构建消息（代码 100 行分支）
grep "本地消息表记录丢失" backend/logs/campusflow.log
```

## 五、完成什么实验

1. **对账触发实验**：把某活动的 Redis 库存手动改成与 DB 差 5（超过阈值 3），
   等 5 分钟看日志"库存漂移" + stock_audit_log 新增 RECONCILE 记录 + compensation_task
   新增 RECONCILE-ALERT 任务；
2. **预留超时实验**：把某条 PENDING_CONFIRM 预留的 expire_at 改到过去、
   retry_count 改成 5 → 等 20s 观察"预留补偿释放完成" + Redis 库存回补 + 通知表新增；
3. 跑 `mvn -pl backend/campusflow-server test -Dtest=CompensationRecoveryIT`，
   对着测试注释理解三个子场景。

## 六、面试自测（口头回答）

1. **你们的一致性方案怎么设计的？**
   要点：Redis 权威扣减（热路径）+ DB 消费端事务口径 + 五分钟对账修复 + 审计留痕；
   窗口期不一致可接受，因为报名正确性由 Lua 原子 + 唯一约束保证，对账只修漂移；
2. **预留超时释放后，用户又付了钱（类比）怎么办？**
   （通用答案框架：释放前先查业务终态（已确认→修复预留状态而非释放）；
   释放是幂等 CAS；通知用户。本项目 reservationRecovery 的三分支就是这个框架。）
3. **多实例下定时任务怎么防重？**
   （Redisson tryLock(0) 抢锁 + 任务级幂等双保险；锁 Key 集中在 RedisKeyConstants。）
4. **如果补偿任务也失败了怎么办？**
   （任务本身带 retryCount/maxRetry + nextRetryTime 退避；后台监控
   compensation_task 待处理数；TTL 安全阀保证最坏情况自动收敛。）

## 七、阶段四总复盘（day12-16 收网测验）

- [ ] 白板画出报名链路全图：五步 + 每步失败出口 + 对应补偿任务；
- [ ] 六任务对照表默写（频率/故障/幂等依据三列）；
- [ ] 两个实验（对账/预留超时）完成；
- [ ] 讲义 14 的 Q1/Q3/Q5/Q6 四题口头过一遍；
- [ ] 自查：能说出"系统里所有数据最终由谁收敛"（DB 权威 + 对账 + TTL 阀）。

## 附一、知识点详解

**1. 六任务的"调度参数"为什么各不相同**
- messageRetry 15s：重投要快（用户等着确认）但受指数退避控制不轰炸；
- reservationRecovery 20s：预留窗口 15min，7 轮机会内要能救回；
- waitlistTimeout 30s：确认窗口 30min，30s 粒度足够；
- waitlistBackfill 60s：巡检兜底，不怕晚；
- stockReconcile 300s：对账代价高（全活动 COUNT），低频；
- activityStatusSync 60s：状态流转分钟级精度可接受。
**周期 = 业务时效要求 ÷ 代价承受度**，不是统一的"每分钟跑一次"。

**2. reservationRecovery 三分支的判定顺序（读代码时容易漏）**
先查 registration 是否已有效（occupiesStock 且非 RESERVED）→ 是则"静默确认"修复
预留状态（**对账修复**，不重发消息不释放）；否则按 retryCount 分流重发/释放。
顺序错误（先释放）会把已成功的报名释放掉——**补偿的每一次行动前都要先问"事实是什么"**。

**3. 对账 bizKey 里的时间窗防碰撞**
`RECONCILE-{aid}-{now/300_000}`——同一 5 分钟窗口内重复执行生成同一 bizKey（幂等跳过），
跨窗口自然生成新键。**用整除时间戳做幂等键**是对账任务的通用技巧。

**4. 为什么告警也走 compensation_task**
RECONCILE-ALERT 任务让"漂移"出现在与业务异常同一个监控面上——运维只需要盯一张表
（待处理补偿任务数），不用为每种异常建独立告警通道。**告警收敛比告警齐全更重要**。

## 附二、故障-兜底总对照（阶段四收网背诵版）

```
Redis重启     → META_MISSING → 懒预热自愈
消息发送失败   → FAILED+退避 → messageRetry 15s
消费失败      → RECONSUME_LATER → 上限转补偿任务
消费完成未确认 → Broker重投 → 幂等拦截
预留超时      → reservationRecovery → 重发或释放+通知
库存漂移      → stockReconcile 5min → DB权威重建+审计
活动状态滞留   → activityStatusSync 60s → 自动流转
```

---

## 附三、面试加练（3 题）

**1. 补偿、重试、对账三者的边界？**
要点：重试=同一动作自动再执行（消息重投）；补偿=业务级反向修复（释放名额）；
对账=定期全量校验修正（库存漂移）——递进的兜底层次，代价依次升高，
所以触发条件依次放宽。

**2. 扫描任务的数据量增长后怎么办？**
要点：扫描 SQL 必须命中索引（status+next_retry_time）；分批 LIMIT；
按时间分片（每实例负责一个时间段）；终极方案改延迟队列（RocketMQ 延迟级别）
——本项目量级下 15s 扫描+LIMIT 50 已够，知道演进路径即可。

**3. 为什么不用 xxl-job/Quartz？**
要点：自研 @Scheduled+Redisson 锁零额外部署（单体形态匹配）；
xxl-job 带来调度中心/分片/可视化的价值在大规模任务时才兑现；
Quartz 集群模式依赖 DB 锁且无分片——**先单体匹配规模，留演进路径**。

**今日金句**：补偿系统是"系统对自己错误的态度"——每一次修复都留痕，才算闭环。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 六任务 | 07_分布式锁与一致性 对账章节 |
| 一致性方法论 | 14_面试题 Q8/Q13 |

**容易说错的表述**：
- ✗ "数据强一致" → ✓ "Redis 权威扣减+DB 对账收敛的最终一致，唯一性由 DB 约束强保证"；
- ✗ "定时任务每个实例都跑" → ✓ "Redisson tryRun 抢锁，抢不到的实例静默跳过"。

---

## 明日预告

Day 17 进入阶段五（候补与签到）：候补队列的设计。
