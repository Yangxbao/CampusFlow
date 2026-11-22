# Day 17 · 候补队列：入队与出队

> 阶段五（17-19 候补与签到）第 1 天。今天聚焦"怎么排队"与"怎么出队"。

## 一、今天学习什么

**设计总纲**（`WaitlistService` 类注释）：队列权威数据在 **Redis ZSet**
（score=入队时间戳，保证真实顺序）；`waitlist` 表为持久化依据（重启重建 + 审计）。

**入队 join 的四道闸**（顺序读代码）：
1. 业务前置：活动可报/窗口内/未报名/未排队（WAITING|PROMOTED 拒绝 WAITLIST_DUPLICATE）；
2. 库存校验：stock==null 拒绝（库存暂不可用）；stock>0 引导直接报名（WAITLIST_NOT_ALLOWED）；
3. Redis：`ZADD NX`（addIfAbsent 原子防重复入队）+ `INCR waitlist:seq` 排队号；
   NX 命中但 DB 记录缺失 → 修复 DB 记录（重建场景）；
4. DB：insert 撞 uk_activity_user 抛 DuplicateKeyException → WAITLIST_DUPLICATE 兜底。

**出队**（day18 详述）：取消释放名额 → tryPromoteNext。

**通知**：入队成功发站内信带当前排位（`ZRANK` + 1）——体验细节也是考点
（"排队系统怎么让用户安心？"）。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-business/src/main/java/com/campusflow/biz/waitlist/service/WaitlistService.java` | join/cancel 全文（今天主教材） |
| `backend/campusflow-common/src/main/java/com/campusflow/common/enums/WaitlistStatus.java` | 五状态：WAITING/PROMOTED/CONFIRMED/EXPIRED/CANCELLED |
| `backend/campusflow-common/src/main/java/com/campusflow/common/constant/RedisKeyConstants.java` | ACTIVITY_WAITLIST / ACTIVITY_WAITLIST_SEQ 定义 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/waitlist/controller/WaitlistController.java` | 接口清单 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/waitlist/dto/WaitlistVO.java` | aheadCount 排位字段 |

## 三、搞懂哪些八股

- ZSet vs List vs Set 做队列：ZSet 支持 O(logN) 插入、按 score 有序、ZPOPMIN O(1) 弹队首、
  ZRANK 查排位——"有序 + 可插队查位"是排队系统刚需；
- 双写一致性：Redis 是顺序权威（性能），DB 是事实权威（持久化）——
  两者职责不同所以是"双轨"不是"主从同步"；
- addIfAbsent（ZADD NX）与 SETNX 同源：原子性来自 Redis 单命令；
- 排队号 INCR 与 ZSet rank 的区别：seq 是发放编号（不因出队变化），rank 是实时排位。

## 四、运行哪些命令

```bash
# 抢满一个名额少的活动，然后排队
# （准备: 创建 capacity=1 的活动，用户A报名占满，用户B加入候补）
redis-cli -a 123321 ZRANGE campusflow:activity:1:waitlist 0 -1 WITHSCORES
redis-cli -a 123321 ZRANK campusflow:activity:1:waitlist "2"    # 排位
redis-cli -a 123321 GET campusflow:activity:1:waitlist:seq      # 发号

# 观察通知
mysql -e "SELECT title,content FROM notify_message ORDER BY id DESC LIMIT 3;"
```

## 五、完成什么实验

1. **并发入队实验**：写临时接口或用 IT 风格代码，20 线程并发 join 同一活动候补，
   断言：ZSet 成员数 = DB 行数 = 去重用户数；seq 连续发放；
2. **NX 幂等验证**：同一用户重复 join，观察返回 WAITLIST_DUPLICATE 且 ZSet 不重复；
3. **排位实验**：三人依次入队，用 ZRANK 验证顺序与入队时间一致（score 时间戳）。

## 六、面试自测（口头回答）

1. **候补队列为什么用 Redis ZSet？**
   （有序性（真实到达顺序）、O(logN) 插入、ZPOPMIN O(1) 出队、ZRANK 实时排位、
   ZADD NX 原子去重；List 做不到原子去重与实时排位，DB 排序扛不住并发。）
2. **Redis 重启队列丢了怎么办？**
   （DB waitlist 表是持久化依据：WAITING 状态的记录可重建队列（ZADD 重灌）；
   双轨设计里 Redis 只负责"顺序"这一易失状态。）
3. **怎么保证不会重复入队？**
   （前置状态校验 + ZADD NX 原子 + DB uk_activity_user 唯一约束三层，
   与报名"一人一单"同构。）
4. **排队号和排位有什么区别？**
   （seq=INCR 发号，全局递增不回退；rank=ZRANK，前面有人退出会前移；
   通知里给用户的是 rank（体感），日志/审计用 seq。）

## 七、自我检查标准

- [ ] join 四道闸顺序默写；
- [ ] 并发入队实验完成（数据一致截图）；
- [ ] WaitlistStatus 五状态流转图完成（含谁触发迁移）；
- [ ] 双轨设计一句话讲清："Redis 管顺序，DB 管事实"。

## 附一、知识点详解

**1. join 四道闸的"校验成本递增"设计**
业务状态（DB 查询）→ 库存判断（Redis）→ 原子入队（ZADD NX）→ DB 持久化（INSERT）。
Redis 校验放在 DB 持久化之前：**先贵的说"有没有资格"，再便宜的说"排上没有"**——
与 Lua 预留的顺序思想同源（便宜的校验在前）。

**2. ZADD NX 命中但 DB 记录缺失的"重建分支"**
源码逻辑：addIfAbsent 返回 false（已在队列）但 existing 状态已关闭 →
重置 WAITING + 清 closed_at——这是"Redis 为权威、DB 为投影"的修复动作：
以队列事实为准修复 DB。注意顺序：**先试原子入队，再据结果修复 DB**，避免竞态。

**3. 排队号 seq 的 Key 治理**
`campusflow:activity:{id}:waitlist:seq` 是 INCR 计数器，活动结束后随活动 Key 体系
一起过期（成员/队列都带活动 TTL）。**计数器 Key 的生命周期要与业务对象对齐**，
否则活动删了计数器还在，新活动复用 ID 会撞号。

**4. 为什么 join 要返回 aheadCount（ZRANK+1）**
体验设计也是并发设计：让用户看到排位会显著降低"反复点击报名"的无效流量——
候补排位页在洪峰后是最热的页面之一，所以它也走了缓存与轻量查询（VO 组装时实时 ZRANK）。

## 附二、并发入队实验断言清单

```
20 线程 × 同一用户 join → 期望: WAITLIST_DUPLICATE, ZSet 成员=1, DB 行=1
20 线程 × 20 个不同用户 join → 期望: 全部成功, ZSet 成员=20, seq=20
同用户 join→cancel→join → 期望: 复用 DB 行(状态回 WAITING), seq 递增不回退
```

---

## 附三、面试加练（3 题）

**1. 候补要不要支持"优先级插队"？**
要点：ZSet score 是"业务可编程位"——把入队时间改成优先级权重即可实现
（如老用户/组织者好友）；但要警惕公平性争议，本项目坚持纯时间序（可解释、
可审计）。**队序即产品决策**。

**2. 候补队列要不要设容量上限？**
要点：要——无限排队会造成虚假预期与内存/表膨胀；方案：ZCARD 上限检查
+ 满员提示"排队人数已满"；本项目未设硬上限（校园规模可控），但列为
可演进点。

**3. ZSet 很大（10 万人排队）会怎样？**
要点：ZADD/ZPOPMIN/ZRANK 都是 O(logN)，10 万成员约 17 层跳表仍微秒级；
瓶颈在成员集合内存（~10MB）与 ZRANK 高频调用——可本地缓存 rank 快照 1s。
**跳表让 ZSet 天生适合队列**。

**今日金句**：排队系统的核心不是先进先出，而是"顺序可信 + 位置可见 + 退出有序"。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 候补设计 | 14_面试题 Q7 |
| 双轨设计 | 05_高并发报名 候补章节 |

**容易说错的表述**：
- ✗ "候补就是一个 List" → ✓ "Redis ZSet（score=入队时间）+ waitlist 表双轨"；
- ✗ "排队号就是位置" → ✓ "seq 是发放编号（不变），ZRANK 是实时排位（前移）"。

---

## 明日预告

Day 18 候补晋级与超时闭环：ZPOPMIN + 锁 + 三个兜底任务。
