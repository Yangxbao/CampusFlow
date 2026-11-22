# Day 12 · 报名主链路总览（状态机视角）

> 阶段四（12-16 高并发报名主链路）第 1 天。这五天是项目的灵魂，
> 讲义 13-14（Redis+MQ 高并发实战）是主教材。今天先立骨架。

## 一、今天学习什么

报名主链路五步（`RegistrationService` 类注释原文）：

```
1. 用户级限流（Lua 内固定窗口）
2. Redis Lua 原子预留（校验+扣减+一人一单，一次原子执行）
3. 预留记录 + 发件箱消息 同事务落库（RegistrationTxService.persistReservation）
4. 立即同步发送 MQ（失败由本地消息表扫描重投）
5. 消费端异步落库确认（RegistrationConfirmConsumer -> confirmRegistration）
```

状态机视角：**Redis 侧** `REDIS_RESERVED`（TTL 15min）→ **消息侧** NEW→SENT→CONFIRMED →
**DB 侧** RESERVED→CONFIRMED；异常 → COMPENSATING → RELEASED。
三个视角在 `campusflow:reservation:{aid}:{uid}`、message_deliver、registration 三处载体上同步推进。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/service/RegistrationService.java` | signup + doPersistReservation 全文（今天重点是流程编排） |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/controller/RegistrationController.java` | 接口定义与 @Operation 注释 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/dto/SignupResultVO.java` | 异步语义的返回值设计 |
| `backend/campusflow-common/src/main/java/com/campusflow/common/enums/ReservationStatus.java` | 预留状态枚举 |
| `docs/learning/13_JMeter性能测试与优化.md` 第七节 | 场景 A 时序图（今天的骨架图参考） |

## 三、搞懂哪些八股

- 异步受理语义：接口返回 PENDING_CONFIRM，前端轮询——高并发下 API 语义从"结果"变"受理"；
- 三视角状态推进的失败组合（今天只列清单，day15 深挖）：
  Lua 成功但 TxService 失败 → 预留 TTL 过期 → 补偿释放；
  消息发送失败 → 本地消息表 NEW/FAILED → messageRetry 重投；
  消费失败 → RECONSUME_LATER → 上限转补偿任务；
- 一人一单的三层防线清单（Day03 已背，今天在链路上定位它们出现的位置）。

## 四、运行哪些命令

```bash
# 报名一次并全程追踪
TOKEN=$(...登录...) 
curl -s -X POST http://127.0.0.1:8080/api/v1/activities/1/registrations \
  -H "Authorization: Bearer $TOKEN"

# 立刻三连看状态
redis-cli -a 123321 GET campusflow:reservation:1:2                  # REDIS_RESERVED
mysql -e "SELECT status FROM message_deliver ORDER BY id DESC LIMIT 1;"
sleep 3; mysql -e "SELECT status FROM registration ORDER BY id DESC LIMIT 1;"

# 日志关键字（按顺序应都能 grep 到）
grep -E "Lua 报名预留|预留落库完成|消息发送成功|报名确认消费成功|预留.*CONFIRMED" backend/logs/campusflow.log
```

## 五、完成什么实验

1. **五步追踪实验**：上面命令跑一遍，把五个日志关键字的行按时间序抄下来，
   与五步一一对应（这是今天最重要的产出）；
2. **轮询视角**：报名后立刻 GET /my/registrations，观察"确认中→已报名"的状态变化
   （体会异步语义的客户端表现）；
3. 画报名全链路时序图（参与者：用户/Controller/Service/Lua/TxService/本地消息表/MQ/消费者/MySQL），
   与讲义 13 第七节 mermaid 对照。

## 六、面试自测（口头回答）

1. **完整讲一遍报名链路（3 分钟版）**
   要点：五步顺序 + 每步的失败出口 + 压测数字（997 请求 2.3ms / P99 5ms）；
2. **为什么 Lua 成功后不直接写 DB？**
   （洪峰下写库是瓶颈：1000 请求中只有 100 个会成功，把 100 次 INSERT 交给 MQ 摊平，
   其余 900 个请求 0 次 DB 访问——削峰的本质是"区分赢家与输家，输家不碰 DB"。）
3. **报名返回什么？前端怎么知道最终结果？**
   （SignupResultVO：PENDING_CONFIRM + reservationId；轮询 /my/registrations 看状态机终态。）
4. **这条链路上如果 MQ 全程不可用，用户最终看到什么？**
   （预留成功受理 → 本地消息表 FAILED 重投 → 预留 15min TTL 过期 → reservationRecovery
   幂等释放名额 + 通知"报名确认失败，欢迎重新报名"——体验有损但数据无损。）

## 七、自我检查标准

- [ ] 五步追踪日志抓齐并按序归档；
- [ ] 时序图完成且与代码行为一致；
- [ ] "三个状态视角"能各自说出载体与推进条件；
- [ ] 3 分钟脱稿讲解录音一次，回放无逻辑断点。

## 附一、知识点详解

**1. 三视角状态推进的"对齐点"（易忽略的细节）**
三个视角的推进不是自动同步的，对齐靠两类动作：
- 正向对齐：消费成功回调 `confirmConsumed`（消息侧）+ `markReservationConfirmed`
  （预留侧）+ INSERT registration（DB 侧）在消费事务里完成；
- 反向对齐：`releaseReservationInternal` 同事务里 markReleased（预留）+
  RESERVED→CONFIRM_FAILED（DB）——任何一侧先坏，补偿都能把另一侧拉回来。

**2. 为什么 doPersistReservation 要在 Lua 之后、发送之前做"防御性校验"**
Lua 的 members 集合与 DB 是两个存储，存在极端窗口（Redis 成员丢失/手工清库）。
`RegistrationService.doPersistReservation` 开头的 existing 检查是"宁可慢不可错"的双保险
——**性能优化（Lua 前置）之后仍然保留 DB 层校验，是防御深度的体现**。

**3. SignupResultVO 为什么同时返回两个 ID**
registrationId（可能为 null，因为异步确认还没生成）与 reservationId（必有，
预留已落库）——前端可以用 reservationId 做幂等重试锚点。
**异步接口的返回值设计要区分"已存在的事实"与"将产生的事实"**。

**4. 一次报名会产生多少条消息？**
主链路 1 条（REGISTRATION_CONFIRM）；成功后 afterConfirm 再发 1 条通知（NOTIFY）；
若取消则 1 条 CANCEL_RELEASE + 1 条 NOTIFY；候补链路另有 WAITLIST_PROMOTE。
**消息量 = 业务事件 × 事件扇出**，容量估算与 Topic 规划都依赖这个口径。

## 附二、五步日志追踪记录

```
步骤1 Lua 预留:  日志行 ___, code=___
步骤2 预留落库:  reservationId=___, msgKey=___
步骤3 消息发送:  msgId=___, 耗时___ms
步骤4 消费确认:  消费耗时___ms, registrationId=___
步骤5 状态闭环:  message_deliver.status=___ (CONFIRMED)
```

---

## 附三、面试加练（3 题）

**1. 前端轮询"我的报名"怎么设计才不压垮后端？**
要点：指数退避轮询（2s→4s→8s，上限 30s）+ 状态终态后停止；接口轻量
（走主键索引 + 分页）；服务端另可加"确认完成"的推送通道（16 讲练习 10）。

**2. 客户端重复点击报名，除了服务端防线还能做什么？**
要点：前端按钮 loading 置灰（体验层）+ 请求端幂等键（可选）+ 服务端
42901 CONCURRENT_REQUEST（同请求处理中再次请求直接拒绝）——
**分层防抖，服务端防线是唯一可信的最后边界**。

**3. 受理接口的超时怎么定？**
要点：报名接口 P99=5ms，超时 3s 已绰绰有余（application.yml send-timeout 3s
是 MQ 发送的）；网关层超时 > 接口超时 > 内部依赖超时的"超时阶梯"。

**今日金句**：异步受理接口的返回值设计，决定了前端体验的上限。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 主链路五步 | 05_高并发报名与RedisLua 第 1-2 节 |
| 场景 A 时序 | 13_JMeter 第 7 节（mermaid） |

**容易说错的表述**：
- ✗ "Lua 返回成功就是报名成功" → ✓ "Lua 返回 1 是预留成功，DB 确认才算报名成功"；
- ✗ "MQ 发完就完事" → ✓ "发件箱记录 CONFIRMED 才是闭环"。

---

## 明日预告

Day 13 深入 Lua：reserve.lua 逐行精读 + 竞态改造实验。
