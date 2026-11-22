# Day 13 · Lua 原子预留（逐行精读）

> 阶段四第 2 天。今天的主教材是 68 行的 `reserve.lua`——全项目最重要的一段代码。

## 一、今天学习什么

`backend/campusflow-infrastructure/src/main/resources/lua/reserve.lua` 逐行精读：

```
KEYS[1] stock      campusflow:activity:{id}:stock     剩余名额(String)
KEYS[2] members    campusflow:activity:{id}:members   已报名成员(Set)
KEYS[3] meta       campusflow:activity:{id}:meta      Hash: status/signupStart/signupEnd
KEYS[4] reserve    campusflow:reservation:{aid}:{uid} 预留状态(TTL=预留期)
KEYS[5] rate       campusflow:ratelimit:signup:{aid}:{uid} 限流计数
返回码: 1成功 / 0满员 / -1状态不可报 / -2不在窗口 / -3重复 / -4限流 / -5元数据缺失
```

执行顺序（顺序本身是设计）：
① meta 校验（未预热直接拒绝，防脏读）→ ② 时间窗 → ③ 限流 INCR+EXPIRE（cnt==1 才设 TTL，
窗口内第一次初始化）→ ④ SISMEMBER 一人一单 → ⑤ GET/DECR 库存 → ⑥ SADD + SET 预留(EX TTL)。

为什么这个顺序：把"便宜且多数请求会挂"的校验放前面（限流/重复），
库存扣减放后面——被拒绝的请求不碰库存计数。

Java 侧配对代码：`StockRedisService.reserve`——DefaultRedisScript 缓存脚本摘要
走 EVALSHA 减少网络传输；`ReserveResult` 枚举把返回码映射成语义。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-infrastructure/src/main/resources/lua/reserve.lua` | 全文 + 头注释（为什么不用 GET+DECR） |
| `backend/campusflow-infrastructure/src/main/resources/lua/cancel_release.lua` | SREM 幂等释放（回补库存仅当曾是成员） |
| `backend/campusflow-infrastructure/src/main/resources/lua/waitlist_serve.lua` | ZPOPMIN 出队+扣减+登记原子 |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/redis/StockRedisService.java` | reserve/releaseReservation/serveWaitlistHead 的 Java 封装 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/service/RegistrationService.java` | signup 的 META_MISSING 自愈分支 |

## 三、搞懂哪些八股

- Redis 为什么单线程还能扛 10 万 QPS（内存操作/IO 多路复用/无锁设计），
  以及 Lua 脚本的原子性来源（脚本执行期间不插入其他命令）；
- EVALSHA vs EVAL（脚本摘要缓存）；脚本必须无副作用/幂等可重试；
- INCR + EXPIRE 的窗口初始化竞态（cnt==1 才 EXPIRE 是固定窗口的标准写法）；
- Lua 返回码设计哲学：把业务分支编码成整数，Java 侧枚举映射——脚本保持纯粹。

## 四、运行哪些命令

```bash
# 直接在 redis-cli 里演练 reserve.lua 的核心逻辑（体会原子性）
redis-cli -a 123321 <<'EOF'
SET campusflow:activity:900:stock 2
HSET campusflow:activity:900:meta status 3 signupStart 0 signupEnd 9999999999999
SADD campusflow:activity:900:members 1
GET campusflow:activity:900:stock
EOF
# 应用内报名两个用户后观察库存归 0、第三个返回 23002
```

## 五、完成什么实验

1. **竞态改造实验（关键）**：把 Lua 里"校验后扣减"改成 Java 侧两步
   （`GET stock` → Java 判断 → `DECR`），写个临时接口用 30 线程抢 1 个名额，
   观察 DECR 后库存出现负数——**亲手复现脚本头注释描述的超卖**；改回 Lua 恢复正确；
2. **限流窗口实验**：连续报名同一用户 10 次，观察返回码序列
   （首次成功 → SISMEMBER -3 → 后续 -4 限流），对照场景 B 的 1/7/992 分布；
3. **META_MISSING 自愈**：手动 DEL 活动的三个预热 Key 再报名，观察日志
   "库存元数据缺失，触发懒预热自愈" 与重试成功。

## 六、面试自测（口头回答）

1. **为什么必须用 Lua 而不是 GET+DECR？**
   （脚本头注释原文：两条命令间存在并发窗口，1000 个并发同时 GET 到 stock=1 再各自 DECR
   会变成 -999；Lua 在 Redis 单线程内原子执行，检查与扣减之间不插入其他命令。）
2. **Lua 里为什么先校验限流和重复，最后才扣库存？**
   （成本排序：SISMEMBER/INCR 不改变库存计数，被拒请求零库存副作用；
   若先扣后校验，拒绝请求要回滚 DECR，增加失败模式。）
3. **取消释放的 Lua 怎么做到幂等？**
   （SREM 只有"此前确实是成员"才返回 1 并 INCR 库存；重复调用第二次 SREM 返回 0
   直接 no-op——库存不会被重复加回。）
4. **脚本执行中途 Redis 宕机会怎样？**
   （Lua 原子执行无半提交；客户端失败重试幂等；最坏情况是预留已写入但消息未发出，
   由预留 TTL + reservationRecovery 释放兜底——"预留状态可过期"是整条链路的安全阀。）

## 七、自我检查标准

- [ ] reserve.lua 可以默写（至少注释级别的伪代码）；
- [ ] 竞态改造实验完成（亲眼见过负库存）；
- [ ] 三个 Lua 脚本的 KEYS/返回码全部背出；
- [ ] EVALSHA 与脚本报摘要机制能讲清。

## 附一、知识点详解

**1. reserve.lua 的"成本排序"为什么是这样**
顺序：meta 校验（HGET 1 次）→ 时间窗（HGET 2 次）→ 限流（INCR+可能 EXPIRE）→
成员查重（SISMEMBER）→ 库存（GET/DECR）→ 登记（SADD+SET）。
把"多数请求会挂的校验"放前面，意味着被拒请求的 Redis 操作次数最少；
**且前四步都不改库存计数**——失败无副作用，脚本天然可安全重试。

**2. 预留 Key 的 TTL 是整条链路的"安全阀"（重点理解）**
`SET reserveKey 'REDIS_RESERVED' EX reserveTtl`（15 分钟）——如果后续一切失败
（消息没发出、消费者全挂、补偿任务也挂），这个 Key 最多 15 分钟自动消失；
期间 reservationRecovery 有 7 轮机会（20s 间隔）重发或释放。
**任何"卡住的状态"都要有自动过期出口**，这是防僵死的通用设计。

**3. 限流的 INCR+EXPIRE 为什么不能写反**
若先 EXPIRE 后 INCR：首次请求时 Key 不存在，EXPIRE 作用于空 Key 无效，
后续 INCR 出来的 Key 永不过期（泄漏）。标准写法 cnt==1 才 EXPIRE，
且 INCR 原子保证只有一个线程看到 cnt==1。

**4. EVALSHA 失败怎么办（NOSCRIPT 场景）**
Redis 重启后脚本缓存丢失，EVALSHA 返回 NOSCRIPT 错误。Spring 的
DefaultRedisScript 会自动降级重发 EVAL——知道这个机制，才能解释
"为什么 DefaultRedisScript 生产可用"。

## 附二、竞态改造实验记录

```
改造: Lua扣减 → Java两步(GET判断后DECR)
结果: 30线程抢1名额, 最终库存=___ (负数?)
原因: GET到1的线程有___个, 都执行了DECR
恢复: 改回Lua后四断言是否恢复? Y/N
```

---

## 附三、面试加练（3 题）

**1. Lua 脚本怎么调试？**
要点：redis-cli --eval 可单跑脚本（echo/redis.return 打印中间值）；
单元测试对脚本行为断言（jvm-lab 思路移植）；sql-show 类似的 redis MONITOR
看脚本内命令执行序列。**脚本要小、注释要全**——本项目三个脚本都有
KEYS/ARGV/返回码的完整头注释。

**2. 脚本在主从/集群下怎么复制？**
要点：主节点执行后以"脚本效果"写入复制流（不是重放脚本）；Cluster 模式下
脚本内所有 Key 必须同 slot（hashtag），否则 CROSSSLOT 错误——day21 呼应。

**3. 为什么项目用 StringRedisTemplate.execute(script) 而不是每次 EVAL 全文？**
要点：DefaultRedisScript 构造时计算 SHA1，execute 自动先 EVALSHA，
NOSCRIPT 时降级 EVAL——减少重复传输脚本全文的网络开销。

**今日金句**：把"检查与扣减"放进一个原子块——竞争窗口消失的那一刻，超卖就消失了。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| Lua 精读 | 05_高并发报名与RedisLua 第 3-4 节 |
| 返回码语义 | 14_面试题 Q1/Q2 |

**容易说错的表述**：
- ✗ "Redis 分布式锁保证原子" → ✓ "报名走 Lua 原子（不用锁），锁用于低频串行场景"；
- ✗ "-5 是系统错误" → ✓ "-5 是元数据缺失，触发懒预热自愈后重试"。

---

## 明日预告

Day 14 可靠消息：本地消息表与投递-消费闭环。
