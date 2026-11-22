# Day 18 · 候补晋级与超时闭环

> 阶段五第 2 天。今天的主线：**名额释放后，队首用户如何拿到名额、超时后名额如何流转**。

## 一、今天学习什么

**触发晋级的三条路径**（`WaitlistPromoteService` 类注释）：
1. 同步触发：取消报名后 `RegistrationService.tryPromoteNextQuietly`（函数注入解除循环依赖）；
2. 消息兜底：`CancelReleaseConsumer` 消费取消释放消息再触发一次；
3. 巡检兜底：`CompensationScheduler.waitlistBackfill` 每 60s 扫"库存>0 且队列非空"漏晋情况。

**晋级主流程**（promoteLoop）：
- 活动级 Redisson 锁 `lockWaitlistPromote(aid)` 内循环；
- `waitlist_serve.lua`：GET stock → 判空 → **ZPOPMIN 弹队首** → DECR → SADD → SET 预留(WAITLIST_RESERVED, EX TTL)；
- 出队后 DB 校验：findActiveWaitlist 为空（并发取消候补）→ **回滚 Redis 服务**（releaseReservation）继续下一位；
- markPromoted CAS 失败（并发晋级）→ 回滚 Redis 继续下一位；
- 成功 → 发晋级通知（30 分钟确认窗口 waitlist-confirm-minutes）。

**超时闭环**：`waitlistTimeout` 每 30s 扫 PROMOTED 且 deadline 过期 → Lua 幂等释放 →
markExpired → 审计 → 通知 → 晋级下一位——**名额像热水一样在队列里滚动，谁也不占着**。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-business/src/main/java/com/campusflow/biz/waitlist/service/WaitlistPromoteService.java` | tryPromoteNext + promoteLoop 全文 |
| `backend/campusflow-infrastructure/src/main/resources/lua/waitlist_serve.lua` | 12 行脚本全文（ZPOPMIN 原子） |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/waitlist/listener/WaitlistPromoteConsumer.java` | 晋级消息的消费（通知主通道） |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/waitlist/service/WaitlistService.java` | confirm（晋级确认 → 复用 doPersistReservation 转 waitlistId>0） |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/compensation/scheduler/CompensationScheduler.java` | waitlistTimeout / waitlistBackfill 两任务 |

## 三、搞懂哪些八股

- ZPOPMIN 的语义：弹出并移除 score 最小成员，O(1)——"弹出即消费"的原子语义；
- 为什么晋级要活动级锁：ZPOPMIN 虽原子，但"出队→写 DB→通知"是长链路，
  锁串行化避免两实例同时服务同一段队列（类注释：无锁时同一用户可能被双重服务）；
- "Redis 成功 DB 失败"的回滚模式：releaseReservation（SREM 幂等）回滚 Redis 侧，
  继续服务下一位——**降级不是回滚到起点，而是跳过失败者**；
- 超时释放与预留 TTL 的双保险：TTL 是 Redis 侧安全阀，waitlistTimeout 是业务侧清理。

## 四、运行哪些命令

```bash
# 全流程演练（用户A取消 → 用户B晋级）
# 取消 A 的报名
curl -s -X DELETE http://127.0.0.1:8080/api/v1/my/registrations/<regId> -H "Authorization: Bearer $TOKEN_A"
# 观察晋级
redis-cli -a 123321 ZRANGE campusflow:activity:1:waitlist 0 -1        # B 已出队
grep "候补晋级\|WAITLIST" backend/logs/campusflow.log | tail -5

# 模拟超时：把 B 的 confirm_deadline 改到过去，等 30s
mysql -e "UPDATE waitlist SET confirm_deadline=NOW()-INTERVAL 1 HOUR WHERE id=<wlId>;"
sleep 35; grep "候补晋级超时" backend/logs/campusflow.log
```

## 五、完成什么实验

1. **晋级链路实验**：capacity=1 活动，A 报名、B/C 候补 → A 取消 →
   观察 B 被晋级（通知 + PROMOTED + Redis 预留）→ B 不确认 → 30s 超时释放 →
   C 晋级——把完整时间线记成日志清单；
2. **确认转正实验**：B 在窗口内 confirm → 观察 waitlistId 传入 doPersistReservation →
   消费端 markConfirmed 联动（RegistrationTxService.afterConfirm）；
3. **双实例思考题（无环境则纸面推演）**：两个实例同时 tryPromoteNext，
   锁保证只有一个进入 promoteLoop；若锁服务不可用，Lua 与 markPromoted CAS 如何兜底？

## 六、面试自测（口头回答）

1. **讲一遍候补晋级链路（2 分钟）**
   要点：三触发路径 → 锁内 ZPOPMIN 原子出队 → DB 校验失败回滚跳过 → 通知+窗口 → 超时闭环；
2. **怎么防止同一用户被双重晋级？**
   （活动级锁串行化 + markPromoted CAS（affected=0 回滚 Redis 跳过）+ Lua 出队原子——
   锁是效率保障，CAS 是正确性保障。）
3. **晋级者不确认名额怎么回收？**
   （双保险：预留 Key EX TTL 自动失效（Redis 侧），waitlistTimeout 30s 扫描幂等释放
   （业务侧）并自动晋级下一位——回收后名额立即滚动到队列。）
4. **为什么确认要复用报名链路而不是直写 DB？**
   （waitlistId>0 传入 doPersistReservation：预留+发件箱+消费联动 markConfirmed 全部复用，
   一套幂等与补偿机制覆盖两条入口——避免复制粘贴链路导致的不一致。）

## 七、自我检查标准

- [ ] 三触发路径 + 超时闭环的完整时序图完成；
- [ ] 实验 1 的全程日志清单归档；
- [ ] waitlist_serve.lua 能默写；
- [ ] "锁防重入 + CAS 防并发 + TTL 防滞留"三位一体能脱稿讲。

## 附一、知识点详解

**1. promoteLoop 的"滚动服务"与"跳过失败者"（设计精髓）**
循环结构：serve Lua 出队 → DB 校验/标记 → 成功则继续下一位，失败（记录关闭/标记冲突）
则 releaseReservation 回滚 Redis 并 **continue 下一位**——不是整体回滚重来。
这保证单个失效候补用户不会阻塞整个队列（僵尸用户是排队系统的真实常态）。

**2. tryPromoteNext 为什么用 tryRun(waitTime=0) 而不是等待锁**
取消触发的晋级是"可延迟"操作：拿不到锁说明另一实例正在晋级同一活动，
它会把队列服务完——本实例无需排队等待（等待只会重复劳动）。
漏晋风险由 waitlistBackfill 巡检兜底——**"锁失败即放弃 + 巡检兜底"组合
替代"锁等待"，降低取消接口的延迟耦合**。

**3. WAITLIST_PROMOTE 消息的消费侧职责**
WaitlistPromoteConsumer 消费晋级消息做异步通知/统计——注意与同步侧
（promoteLoop 里 createAndSend 的 NOTIFY）并行存在：一条给系统（消息通道），
一条给用户（站内信）。**同域双通道：事件消息（可靠性）+ 通知消息（即时性）**。

**4. 确认窗口 30 分钟的产品-技术协同**
waitlist-confirm-minutes=30 同时决定三处：预留 Key 的 EX TTL、
confirmDeadline 字段、通知文案。**一个业务参数在三处生效**——改它必须三处同改，
这正是集中到 CampusflowProperties 的理由（一处改，处处读）。

## 附二、晋级全链路日志清单（实验 1 的归档格式）

```
[T0] 用户A取消: "取消报名事务提交 registrationId=___"
[T0+δ] 同步触发: "候补晋级完成 activityId=___ served=1"
[T0+δ] Lua: "候补晋级出队 userId=B"
[T0+δ] 通知: NOTIFY_MSG "候补晋级通知...30 分钟内确认"
[T0+30min] 未确认: "候补晋级超时释放 waitlistId=___"
[T0+30min] 顺延: "候补晋级完成 served=1 (C)"
```

---

## 附三、面试加练（3 题）

**1. 一次取消可能晋级多人吗？通知会不会风暴？**
要点：promoteLoop 是循环——只要库存>0 且队列非空就连续晋级（served 计数）；
一次容量调整释放 50 个名额会连续通知 50 人——通知走异步 MQ 摊平，
同步链路只承担出队与标记。**批量晋级的通知风暴在异步层消化**。

**2. 双实例同时晋升同一用户会怎样？（纸面推演）**
要点：锁互斥（只有一个进 promoteLoop）；极端锁失效时——Lua ZPOPMIN 原子保证
只出一个 userId；markPromoted CAS 保证 DB 只标记一次，败者回滚 Redis 服务；
**三层递进，最坏结果是"服务顺序交换"而非"双重占用"**。

**3. 为什么晋级通知要带 deadline 时间戳？**
要点：消息投递有延迟，通知文案里的"30 分钟"必须以 confirmDeadline（权威死线）
为准——用绝对时间戳而非相对时长，避免投递延迟导致用户误判。

**今日金句**：名额像接力棒——出队、确认、超时、再出队，系统永远把名额交给"此刻最前面的人"。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 晋级闭环 | 07_分布式锁与一致性；14_面试题 Q7 |
| Lua 出队 | 05_高并发报名 waitlist_serve 章节 |

**容易说错的表述**：
- ✗ "晋级就是修改状态" → ✓ "ZPOPMIN 出队+扣库存+写预留原子执行，DB 侧 CAS 联动"；
- ✗ "超时名额浪费了" → ✓ "waitlistTimeout 30s 自动释放并晋级下一位，名额滚动利用"。

---

## 明日预告

Day 19 签到：电子凭证签发与三层核销防线。
