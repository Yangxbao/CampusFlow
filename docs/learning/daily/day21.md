# Day 21 · Redis 持久化、主从与 Cluster

> 阶段六第 2 天。今天回答：**Redis 自己挂了怎么办？**并盘点本项目的"单实例假设"。

## 一、今天学习什么

1. **持久化**：RDB（快照，fork 子进程，恢复快但丢窗口数据）vs AOF（追加写命令，
   everysec 折中）vs 混合持久化（4.0+，RDB 头 + AOF 增量）；
   对本项目的意义：预热/成员/候补都是可重建状态（预热脚本+DB 权威口径），
   丢数据走自愈（META_MISSING → 懒预热；布隆 tryInit/重建）；
2. **主从复制**：全量 RDB + 增量命令流；读写分离的收益与主从延迟风险（day09 联动）；
3. **哨兵**：监控+自动 failover+通知，客户端通过哨兵发现主；
4. **Cluster**：16384 slot 分片、gossip 协议、MOVED/ASK 重定向；
   对本项目的改造点：Lua 跨 Key 需要同 slot（hashtag `{activity:{id}}`，16 讲练习 1）；
5. **本项目当前形态与风险清单**（RedissonConfig 单实例 useSingleServer）：
   - 单点可用性：主挂则报名失败（快速失败语义）→ 升级路径哨兵/Cluster；
   - 数据丢失窗口：无持久化或 everysec 丢 1s → 全部状态可重建（设计上容忍）；
   - 性能上限：单实例写 QPS 10 万级，热点活动库存单 Key → 分桶（16 讲练习 8）。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/config/RedissonConfig.java` | useSingleServer 配置与连接池规格 |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/redis/StockRedisService.java` | META_MISSING 自愈设计（Redis 丢失的应对） |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/redis/BloomFilterService.java` | tryInit 幂等 + rebuildActivityBloom 重建 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/activity/service/ActivityService.java` | warmupStockIfNeeded 懒预热 |
| `docs/learning/16_项目进阶改造练习.md` 练习 1 | Cluster 改造步骤与验收 |

## 三、搞懂哪些八股

- RDB fork 的写时复制原理与大数据量时的抖动；AOF 重写（bgrewriteaof）；
- 主从复制积压缓冲区与部分重同步（psync）；
- Cluster 为什么 16384 个 slot（心跳包携带 bitmap，2KB 控制面开销）；
- hashtag `{...}` 的语义：只有花括号内参与 hash，`{activity:5}:stock` 与
  `{activity:5}:members` 落同 slot——Lua 多 Key 原子性的前提。

## 四、运行哪些命令

```bash
redis-cli -a 123321
> INFO replication        # role:master / connected_slaves
> INFO persistence        # rdb_last_save_time / aof_enabled
> DBSIZE

# 主从延迟观察（若有从库）
redis-cli -a 123321 -h <slave> INFO replication   # master_link_status / master_repl_offset

# 自愈验证：删预热 Key 再报名
redis-cli -a 123321 DEL campusflow:activity:1:stock campusflow:activity:1:meta
curl -X POST .../registrations   # 观察日志"库存元数据缺失，触发懒预热自愈"
```

## 五、完成什么实验

1. **Redis 重启自愈演练**：重启 Redis（或 DEL 全部 campusflow:* Key）→ 报名观察
   META_MISSING → 懒预热 → 重试成功；再报名确认布隆/成员缺失的兜底行为；
2. **持久化配置实验**：给本地 Redis 开 AOF（`--appendonly yes`）重启，
   确认数据恢复；对照无持久化重启全丢——记录两种行为差异；
3. **slot 计算练习**：`redis-cli -c cluster keyslot "campusflow:activity:5:stock"`
   与 `cluster keyslot "{campusflow:activity:5}:stock"` 对比，理解 hashtag 前后差异
   （单实例无 Cluster 可先用 CRC16 计算器推演）。

## 六、面试自测（口头回答）

1. **你的 Redis 挂了会怎样？数据会丢吗？**
   （报名快速失败（Redis 是库存权威，拒绝优于脏写）；预热/成员/候补均可从 DB 重建：
   META_MISSING 懒预热自愈、布隆 tryInit/重建、waitlist 表重建队列——可用性有损，
   数据无损，收敛依赖 TTL+对账。）
2. **Redis Cluster 对你的 Lua 有什么影响？**
   （多 Key 脚本要求所有 Key 同 slot；改造方案 hashtag：RedisKeyConstants 的 Key 全部
   改为 `{activity:{id}}:xxx` 形式，同活动 Key 同 slot，脚本原子性保留——16 讲练习 1。）
3. **RDB 和 AOF 怎么选？**
   （RDB 恢复快丢窗口多，AOF 丢数据少文件大恢复慢，4.0 混合两者兼得；
   本项目状态可重建，持久化主要用于加速冷启动而非强一致。）
4. **热点库存单 Key 是瓶颈吗？**
   （单实例 Redis 写 10 万 QPS 上限，单活动洪峰 2000/s 远未到；若放大 10 倍，
   分桶方案（8 桶随机扣减+聚合对账）——先给数据再给方案，不空谈优化。）

## 七、自我检查标准

- [ ] RDB/AOF/哨兵/Cluster 四者一句话差异说清；
- [ ] 自愈演练完成（META_MISSING → 重试成功全程日志）；
- [ ] 风险清单三条（可用性/丢失窗口/性能上限）各配一个项目代码锚点；
- [ ] hashtag 原理能画图讲清。

## 附一、知识点详解

**1. 本项目"可重建状态"清单（Redis 宕机的完整推演）**

| Key | 丢失影响 | 重建路径 | 自愈触发 |
|---|---|---|---|
| stock/meta/members | 报名拒绝(-5) | preloadStock（DB 权威口径） | META_MISSING 懒预热 |
| info 缓存 | 详情回源 DB | Cache Aside 回填 | 自动 |
| waitlist ZSet | 排队顺序丢失 | waitlist 表 WAITING 记录重灌 | 重启脚本/巡检 |
| 布隆位图 | 穿透防线失效 | tryInit + 全量装载 | 启动初始化 |
| viewcount/seq | 计数回退 | 可接受（统计口径） | - |

结论：**唯一真正"丢失"的是实时计数**，其余全可重建——这是 Redis 用作"缓存+可重建状态"
而非"唯一事实"的架构红利。

**2. 主从复制的异步性与本项目的关联**
Redis 主从是异步复制——主挂从升后"已扣库存"可能回退。本项目防御：
Lua 幂等（SREM/DECR 语义明确）+ 预留 TTL + 对账。**任何"Redis 数据回退"最终都会被
对账收敛，只是窗口内体验受损**——这就是 day16 结论在 Redis 层的再现。

**3. Cluster 改造的本项目清单（练习 1 预告）**
- RedisKeyConstants 全量加 hashtag `{activity:{id}}`；
- RedissonConfig useClusterServers；
- 连接池参数按节点数重算（单机 32 连接 × 3 主）；
- IT 回归（LuaOversellIT 等 6 个）。

**4. 持久化选择的业务判据**
状态可重建 → 持久化只为冷启动加速（RDB 够）；若 Redis 存"不可重建"数据（如订单）
→ AOF everysec + 主从强配置。**先问"丢了会怎样"再选持久化**。

## 附二、自愈演练记录

```
DEL 三 Key 后报名: 返回受理? Y/N; 日志"懒预热自愈"? Y/N
重启 Redis(无持久化): DBSIZE=___; 报名恢复耗时___s(等自愈)
重启 Redis(AOF): DBSIZE=___(数据保留)
```

---

## 附三、面试加练（3 题）

**1. 生产环境为什么禁用 KEYS？用什么替代？**
要点：KEYS O(N) 阻塞单线程（百万 Key 秒级卡顿）；替代 SCAN（游标增量，
O(1) 单步）；本项目排查用 `SCAN 0 MATCH campusflow:activity:1:* COUNT 100`。

**2. 大 Key 怎么发现与治理？**
要点：发现——redis-cli --bigkeys / MEMORY USAGE；本项目大 Key 风险点：
members 集合（万人 Set ~1MB）与 info JSON——治理：members 拆分或到期删除
（活动 TTL 已治理）、info 只存展示字段（大文本明确不入缓存）。

**3. 内存淘汰策略选什么？**
要点：本项目 Key 都有 TTL 且不可丢库存态 → volatile-lru/noeviction 视场景；
缓存库用 allkeys-lru，库存态混布时倾向 noeviction+告警——**淘汰策略
取决于"数据丢了会怎样"**。

**今日金句**：Redis 治理三查——大 Key、热 Key、慢命令，一个都不能漏。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| Redis 高可用 | 04_Redis 持久化/主从章节；12_Docker |
| Cluster 改造 | 16_进阶练习 1 |

**容易说错的表述**：
- ✗ "Redis 挂了数据全丢" → ✓ "可重建状态全部有 DB 权威口径，唯一丢失的是实时计数"；
- ✗ "Cluster 下 Lua 照常跑" → ✓ "多 Key 脚本需 hashtag 同 slot"。

---

## 明日预告

Day 22 一致性专题：对账与审计的设计模式。
