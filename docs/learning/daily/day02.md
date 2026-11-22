# Day 02 · 架构总览与模块划分

> 阶段一第 2 天。今天的产出是**两张手绘图**：系统架构图、报名请求旅程图。
> 能画出来 = 理解了架构；画不出来 = 回去重读讲义 15 的"阅读总图"。

## 一、今天学习什么

1. 五种角色各就各位：Controller（接口）/ Service（编排）/ TxService（事务边界）/
   Infrastructure（Redis/MQ/锁武器库）/ Compensation（兜底）；
2. 三条关键链路的鸟瞰：
   - 报名写链路：`RegistrationController.signup` → Lua 预留 → 本地消息表 → RocketMQ →
     `RegistrationConfirmConsumer` → DB 确认；
   - 热点读链路：`ActivityPublicController` 详情 → Caffeine → Redis → DB（多级缓存）；
   - 兜底链路：`CompensationScheduler` 六个定时任务（Redisson 锁防重入）；
3. 模块依赖方向：server → business → infrastructure → common（无环）；
   实验模块 jvm-lab / concurrency-lab 完全独立。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/service/RegistrationService.java` | 类头注释 1-49 行：报名主链路五步概览（今天只需要看懂注释） |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/redis/StockRedisService.java` | 类注释：预热内容设计（stock/meta/members） |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/mq/MessageDeliverService.java` | 类注释：本地消息表时序 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/compensation/scheduler/CompensationScheduler.java` | 类注释：六个任务清单 |
| `backend/campusflow-server/src/main/java/com/campusflow/server/config/WebMvcConfig.java` | 拦截器注册，看 PUBLIC_PATHS |

**读注释！** 本项目注释承载设计动机，先读类头注释再读方法体，是全项目阅读的标准姿势。

## 三、搞懂哪些八股

- 分层架构的依赖方向原则：为什么 infrastructure 不能依赖 business（依赖倒置；
  若反向则基础设施感知业务细节，复用性归零）；
- 单体 vs 微服务：本项目"多模块单体"的意义（14 讲 Q21 的三个论据：团队规模/运维成本/边界预留）；
- 编排层与事务层分离：RegistrationService（非事务编排）与 RegistrationTxService（唯一事务入口），
  防的是 @Transactional 同类自调用失效。

## 四、运行哪些命令

```bash
# 从入口找链路：用 Knife4j 文档确认 URL 再 grep Controller
grep -rn "RequestMapping" backend/campusflow-business/src/main/java --include="*Controller.java"

# 数一数业务域（今天建立的目录认知）
ls backend/campusflow-business/src/main/java/com/campusflow/biz/
# activity/admin/audit/checkin/compensation/config/mq/notify/registration/seed/stats/user/waitlist

# 消费者清单（MQ 的另一侧）
grep -rln "DefaultMQPushConsumer" backend --include="*.java" | grep -v target
```

## 五、完成什么实验

**实验：手绘两张图（今天最重要的产出）**

图 1：系统架构图——应用（含模块）+ MySQL + Redis（列出 stock/meta/members/waitlist 四类 Key）+
RocketMQ（列出 6 个 Topic）+ 前端，箭头标注协议（HTTP/Redis 协议/MQ）。

图 2：一次报名的旅程（黑盒视角）——浏览器点击报名 → 网关 → Lua 校验扣减 →
"报名已受理"返回 → MQ → 消费落库 → 用户轮询看到"已报名"。
先凭理解画，画完对照 `RegistrationService` 类注释五步自评。

## 六、面试自测（口头回答）

1. **画一下你的项目架构？**（分层单体：接入层 Controller → 编排 Service → 事务 TxService →
   基础设施 Redis/MQ/MySQL；旁路补偿 Scheduler；面试时边画边讲 3 分钟。）
2. **为什么是"Redis 前置挡流 + MQ 异步落库"，而不是直接写数据库？**
   （场景 A：1000 用户抢 100 名额，997 个请求只碰 Redis（2.3ms），写库由 MQ 削峰异步完成，
   DB 瞬时压力从 1000 降到 100 且摊平——压测报告第二节的直接结论。）
3. **基础设施层都有哪些"武器"？**
   （锁：RedissonLockService + @DistributedLock；缓存：MultiLevelCacheService 多级 +
   BloomFilterService；限流：RateLimiterService；MQ：MessageDeliverService 本地消息表 +
   MqConsumerFactory；线程池：ThreadPoolConfig 三池——说得出名字和用途。）
4. **用户看到"报名已受理"时，数据落库了吗？**
   （没有；落的是 Redis 预留 + 本地消息表 NEW 记录，DB 确认由消费端数秒内完成——
   接口语义是"受理"而非"成功"，前端轮询兜底。）

## 七、自我检查标准

- [ ] 两张手绘图完成，图 2 与 RegistrationService 注释五步一一对应；
- [ ] 说出 6 个 Topic 名字（registration-confirm/cancel-release/waitlist-promote/notify/checkin/cache-evict）；
- [ ] 能解释"编排层/事务层"两层 Service 的分工；
- [ ] 用 Knife4j 找到报名接口的完整 URL 并手写 curl 成功（带 token）。

## 常见误区（今天就要纠正）

1. 以为"报名成功"是同步写库——错，是异步确认（SignupResultVO.result = PENDING_CONFIRM）；
2. 以为 Redis 只存一个库存数——错，stock/meta/members/reservation/ratelimit 五类 Key 各司其职；
3. 把 CompensationScheduler 当"定时统计任务"——错，它是**一致性兜底**，是最终一致的关键。

## 附一、知识点详解

**1. 为什么"编排层/事务层"两层 Service 是必要的（展开）**
Spring 的 @Transactional 基于 AOP 代理：同类内 `this.persistReservation()` 不经过代理，
事务切面不生效。CampusFlow 的对策是结构性的——`RegistrationService` 只做编排
（调 Lua → 调 TxService → 立即尝试发送），所有 DB 变更集中在 `RegistrationTxService`
且被外部类调用。这同时带来第二个收益：事务边界一眼可见（一个类 = 一个域的事务集合）。

**2. Redis 五类 Key 在读链路与写链路的分工**
写链路（报名）只碰 stock/members/meta/reservation/ratelimit 五个 Key（全在 Lua 里）；
读链路（活动详情）碰 info Key（多级缓存）与 viewcount（INCR 计数）。
两套 Key 互不干扰，所以热点读的 1677/s 不会与报名抢名额互相影响——
"读写分离的 Key 设计"是 Redis 侧的隐性架构点。

**3. 六个 Topic 的消费语义差异**
registration-confirm / cancel-release / waitlist-promote / notify / checkin 五个是
**集群模式**（消费组内负载均衡，一条消息只被一个实例消费）；cache-evict 是
**广播模式**（每个实例都必须消费，用于失效各自本地缓存）。区分标准：消息是"任务"
（做一次）还是"事件通知"（人人都要知道）。`MqConsumerFactory.create` 的 broadcast
参数就是这个开关。

**4. 面试官常追问的"模块间怎么通信"**
单体应用内是方法调用（进程内），跨进程只有两处：HTTP（前端→应用）与 MQ（应用→消费者）。
数据库与 Redis 是"共享状态"不是通信。答架构题时把这个边界讲清楚，能避免被
"你们服务间怎么调用"这种模棱两可的问题带偏。

## 附二、两张图的自评标准

- 架构图：含 4 个应用模块 + 3 个存储 + 6 个 Topic + 2 条外部通道（HTTP/MQ）；
- 旅程图：从"点击报名"到"轮询到已报名"≤ 10 个节点，Lua 与本地消息表两个关键节点必须出现。

---

## 附三、面试加练（3 题）

**1. 单体到微服务的演进触发条件是什么？**
要点：团队规模（>8 人协作冲突）、部署节奏互相阻塞、模块资源画像差异大
（报名写密集 vs 统计读密集）需要独立扩缩容——三个条件同时满足才拆；
CampusFlow 用 Maven 模块预留了拆分边界，但当前规模拆了纯增成本。

**2. 为什么读写用不同的 Redis 数据结构策略？**
要点：写侧（报名）用 String/Set/Hash 组合进 Lua 保原子；读侧（详情）用
Cache Aside JSON；计数用 INCR 原子自增——按"操作语义"选结构而不是按"数据形状"。

**3. Topic 命名规范的价值？**
要点：campusflow-{业务}-{动作}-topic 三段式让 Broker 控制台一眼可读；
消费组 campusflow-{业务}-group 与 Topic 对齐；广播类 Topic 单独列出防误用。

**今日金句**：架构图是讲给别人听的，代码是写给三个月后的自己的——两者都要能自洽。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 架构总览 | 01_项目业务与整体架构 全篇 |
| 报名链路鸟瞰 | 05_高并发报名与RedisLua 第 1-2 节 |
| 兜底体系 | 06_RocketMQ消息可靠性 + 07_分布式锁与一致性 |

**容易说错的表述**：
- ✗ "用了微服务架构" → ✓ "多模块单体，预留了服务化边界"；
- ✗ "消息队列削峰" → ✓ "本地消息表事务性发件箱 + MQ 异步落库削峰"；
- ✗ "补偿就是重试" → ✓ "重试是同一动作，补偿是业务级反向修复"。

---

## 明日预告

Day 03 数据库设计与领域模型：17 张表怎么读、状态机长什么样。
