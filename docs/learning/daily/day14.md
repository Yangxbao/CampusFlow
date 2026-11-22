# Day 14 · 可靠消息：本地消息表与投递-消费闭环

> 阶段四第 3 天。今天把"消息不丢"的三个环节全部落到代码。

## 一、今天学习什么

**发送端**（`MessageDeliverService`）：
1. 事务内：`createDeliverRecordWithKey` insert message_deliver(NEW)——**加入调用方事务**
   （本方法无 @Transactional，借用业务事务原子性）；
2. 事务提交后：`trySendDeliver` 同步发送并等待 SendStatus.SEND_OK → markSent；
   非 SEND_OK 或异常 → markFailed + **指数退避**（baseSeconds=30 × 2^retry，封顶 2^6）；
3. 兜底扫描：`listRetryDue`（status IN (NEW,FAILED) AND next_retry_time<=now AND retry<max）
   由 `CompensationScheduler.messageRetry` 每 15s 重投 50 条。

**消费端**（`RegistrationConfirmConsumer`）：
- 幂等前置 `tryBeginConsume`；解析失败 → 直接转补偿任务（避免毒消息无限重投）；
- 业务终态拒绝（活动已取消/不存在）→ markSuccess 确认消费防无效重投；
- 其他异常 → RECONSUME_LATER；`reconsumeTimes >= max` → **转补偿任务 + markSuccess**
  （业务级死信处理，不依赖裸 DLQ）；
- 成功后 `confirmConsumed` 把本地消息表推到 CONFIRMED——**投递-消费闭环**。

**工厂**（`MqConsumerFactory`）：消费组后缀隔离（groupSuffix，测试组 it 与运行中应用隔离）；
线程数 5-20 有界；consumeTimeout 5 分钟；广播模式开关。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/mq/MessageDeliverService.java` | 全文（今天主教材） |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/listener/RegistrationConfirmConsumer.java` | 监听器四分支 + handleFailure |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/mq/MqProducerConfig.java` | producer 装配（send-retry-times=2） |
| `backend/campusflow-common/src/main/java/com/campusflow/common/constant/MqConstants.java` | Topic/Tag/Group 规划 |
| `application.yml` 的 `campusflow.mq` 段 | 每个参数的含义（message-retry-base-seconds=30 等） |

## 三、搞懂哪些八股

- "消息不丢"三段论：生产端（本地消息表，先落库后发送，失败重投）、
  存储端（同步刷盘/主从复制配置）、消费端（手动确认语义，处理完成才 ACK）；
- RocketMQ 消费确认约定：CONSUME_SUCCESS / RECONSUME_LATER / 延迟级别递增重投 /
  达上限进 %DLQ%{group}（本项目改走业务补偿表）；
- 指数退避公式与封顶：`30s * 2^min(retry,6)` ——避免重试风暴；
- 消息堆积的排查路径：Broker 控制台看 lag → 消费线程数/下游 DB 压力 →
  本地消息表 CONFIRMED 缺口。

## 四、运行哪些命令

```bash
# 消息表状态机观察
mysql -e "SELECT msg_key,biz_type,status,retry_count,next_retry_time
          FROM message_deliver ORDER BY id DESC LIMIT 10;"

# 人造发送失败：停 RocketMQ 后报名，观察 FAILED + 退避时间
mysql -e "SELECT msg_key,status,retry_count,next_retry_time FROM message_deliver
          WHERE status=2 ORDER BY id DESC LIMIT 5;"      # 2=FAILED

# 恢复 MQ，等 15s 扫描重投
grep "本地消息表重投完成" backend/logs/campusflow.log
```

## 五、完成什么实验

1. **状态流转全记录**：正常报名一次，抓 message_deliver 的 NEW→SENT→CONFIRMED 三态时间线
   （每态一次查询截图）；
2. **MQ 宕机演练**：停 RocketMQ（或改错 name-server）报名 → 观察 FAILED 与
   next_retry_time 逐次翻倍（30s→60s→120s…）→ 恢复 MQ → 等重投成功 → 全链路闭环；
3. **闭环验证**：确认 CONFIRMED 状态的记录都是消费成功回调的（对照
   "报名确认消费成功"日志条数）。

## 六、面试自测（口头回答）

1. **怎么保证消息一定发出去？**
   （先 DB 后发送：业务事务内落 message_deliver(NEW)，事务提交后同步发送等 SEND_OK；
   失败标 FAILED 并指数退避重投；极端情况记录仍在 DB，扫描兜底——本地消息表优于
   "发完再记"，因为后者发送后崩溃就丢了。）
2. **消费失败会怎样？一直失败呢？**
   （RECONSUME_LATER → Broker 延迟递增重投 → reconsumeTimes 达 5 → 业务代码转
   compensation_task（bizKey 幂等，maxRetry=5）并确认消费防堆积——比裸 DLQ 强在
   有重试节奏/有对账入口/逻辑与业务同步演进。）
3. **为什么不用 RocketMQ 事务消息？**
   （MessageDeliverService 类注释：事务消息适合"只关心原子性+接受半消息反查"；
   本项目要投递轨迹持久化（对账/审计/可视化）+ 重试策略自主可控，本地消息表两者兼得；
   事务消息作为对比实验（16 讲练习 2）。）
4. **毒消息（永远解析失败）怎么处理？**
   （payload==null 分支：立即转补偿任务 + markSuccess，不进入重投循环——
   死信的处理原则是"隔离留证，不阻塞队列"。）

## 七、自我检查标准

- [ ] 三段论每段能指出项目代码位置；
- [ ] MQ 宕机演练完整跑通（FAILED→退避→重投→CONFIRMED）；
- [ ] 指数退避公式能写出并解释封顶；
- [ ] handleFailure 两个参数（reconsumeTimes 判断 + 转补偿）能复述。

## 附一、知识点详解

**1. "先落库后发送"与"先发送后落库"的崩溃窗口对比（面试推演题）**
- 先发送后落库：发送成功后进程崩溃 → 消息已出但无记录 → 无从对账重发 → **消息可能丢**；
- 先落库后发送（本项目）：落库后崩溃 → 记录在（NEW 状态）→ messageRetry 扫描重发 →
  **消息最多迟，不会丢**。
代价是多一次 DB 写（在本项目里恰好与预留记录同事务，零额外成本）——
这就是"事务性发件箱"的全部逻辑。

**2. 指数退避的具体节奏（算一遍）**
base=30s，retry=0..8（message-max-retry），封顶 2^6=64 倍 →
30s、1m、2m、4m、8m、16m、32m、32m、32m——8 次重试横跨约 2 小时。
**为什么封顶**：防止 2^30 秒这种荒谬值；**为什么不用线性**：给下游恢复留越来越多的时间。

**3. 消费确认的四个出口（RegistrationConfirmConsumer 全景）**
①重复消息 → 跳过（tryBeginConsume false）；②payload 解析失败 → 转补偿任务 + 成功确认；
③业务终态拒绝（活动取消/不存在）→ markSuccess 确认；④其他异常 → RECONSUME_LATER，
达上限转补偿。**每个出口都有"消息去哪了"的答案**——这就是死信处理的完整答案。

**4. groupSuffix 的运维价值**
application-test.yml 的 `group-suffix: it`——测试消费者组 `campusflow-registration-group-it`
与运行中应用隔离，避免测试消费掉生产环境的确认消息（Broker 负载均衡按组瓜分队列）。
**多环境共用 Broker 时的第一安全措施**。

## 附二、宕机演练时间线记录

```
T0 停 RocketMQ
T1 报名请求: 响应仍受理? Y/N (本地消息表状态=___)
T2 重投观察: retry=1 next_retry=+30s? ___; retry=2 +60s? ___
T3 恢复 MQ
T4 重投成功时刻: ___; 全链路 CONFIRMED? Y/N
```

---

## 附三、面试加练（3 题）

**1. 什么场景需要顺序消息？本项目有吗？**
要点：同一业务键的变更要按序处理时（如同一订单的创建/支付/取消）。本项目
报名确认幂等化后无需顺序（乱序安全）；若加"积分变更流水"才需要 MessageListenerOrderly
+ 按 userId 选队列——**先幂等化再谈顺序，成本差一个量级**。

**2. 通知类消息允许丢吗？怎么做 best-effort？**
要点：通知也走发件箱（NOTIFY Topic），失败退避重投+上限转补偿——
但通知丢失的业务损失低，重试预算可以更少（message-max-retry 全局统一，
进阶可按 bizType 分级）。

**3. 消费端怎么提升吞吐？**
要点：三杠杆——consumeThreadMin/Max（5→20 对照实验）、批量消费
（List<MessageExt> 已支持）、下游 DB 批量写（rewriteBatchedStatements 已开）。
先测瓶颈在哪（Broker 投递/消费处理/DB 写入）再动。

**今日金句**：消息不丢的秘诀是"先让消息有据可查"——发件箱把"发送"变成"状态"。

---

## 明日预告

Day 15 状态机、乐观锁与消费幂等：把一致性细节钉死。
