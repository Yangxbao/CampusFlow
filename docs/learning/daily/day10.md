# Day 10 · 缓存模式：Cache Aside 与多级缓存

> 阶段三第 3 天。今天的核心类只有一个：`MultiLevelCacheService`，但要把背后的
> Cache Aside 理论全部吃透。

## 一、今天学习什么

1. Cache Aside 标准姿势：**读**——先缓存后 DB（回填）；**写**——先 DB 后删缓存
   （Del 而非 Update）。为什么删不是改：并发写覆盖、双写不一致窗口更小；
2. 本项目的两级缓存：L1 Caffeine（本地，maximumSize=500，TTL 30s，recordStats）→
   L2 Redis（TTL 30min）→ DB；
3. 空值防穿透：loader 返回 null 时写空字符串占位 TTL 60s；
4. 多实例一致性：写方 evict 两级 + 发广播 MQ（TOPIC_CACHE_EVICT）→
   `CacheEvictConsumer`（BROADCASTING）各实例 `evictLocal`；广播不重投 →
   本地 TTL 30s 是最终兜底（设计上接受秒级脏读——类注释原文）；
5. 压测证据（场景 F）：10000 次热点查询 DB 主表 0 次查询、吞吐 1677/s——
   多级缓存把 DB 从读路径上摘除。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/redis/MultiLevelCacheService.java` | 全文 120 行，逐行读（get/evict/evictLocal/put/deserialize） |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/mq/CacheEvictConsumer.java` | 广播消费与失败不重投的取舍注释 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/activity/service/ActivityService.java` | 找到 evictDetailCache 调用点（发布/取消/容量调整后失效） |
| `backend/campusflow-common/src/main/java/com/campusflow/common/constant/RedisKeyConstants.java` | ACTIVITY_INFO Key 的 TTL 注释 |
| `application.yml` 的 `campusflow.cache` 段 | 参数：local-max-size/local-ttl-seconds/redis-activity-ttl-minutes |

## 三、搞懂哪些八股

- Cache Aside vs Read/Write Through vs Write Behind（本项目标准 Cache Aside）；
- 为什么 TTL 必须存在：即便逻辑完美，也要防"广播丢失/进程重启"导致的永久脏数据；
- Caffeine 与 Redis 的分工：本地省网络往返（微秒级）扛热点，Redis 共享数据跨实例；
  本地 TTL 必须 << Redis TTL（30s vs 30min），否则本地脏读窗口过长；
- 缓存对象选择：StockRedisService 注释原文——"活动长描述、审核记录等大文本不适合缓存
  （读少写多、体积大，缓存收益低）"；而 stock/meta/members 是高频小数据，必须缓存。

## 四、运行哪些命令

```bash
# 1. 观察两级缓存的 Key
redis-cli -a 123321 TTL campusflow:activity:1:info
redis-cli -a 123321 GET campusflow:activity:1:info | head -c 300

# 2. 广播失效链路：管理员改活动标题后观察日志
grep "broadcast" backend/logs/campusflow.log
# 期望: [broadcast] 本地缓存失效 cacheKey=...

# 3. Caffeine 命中率（recordStats 开启）
curl -s http://127.0.0.1:8080/actuator/metrics 2>/dev/null | head -c 500
```

## 五、完成什么实验

1. **读链路计时**：curl 连续请求同一活动详情 20 次，观察 Redis TTL 变化与响应时间稳定段
   （本地命中期响应更快），记录平均耗时差；
2. **写失效实验**：组织者改活动标题 → 立即读详情 → 确认拿到新值 →
   观察日志"本地缓存失效"；再手动删除 Redis Key 模拟"广播丢失"，
   30s 后本地自然过期——验证"TTL 兜底"成立；
3. **空值占位实验**：请求一个不存在的活动 ID 两次，第二次观察日志/响应，
   `redis-cli GET campusflow:activity:99999:info` 看到空占位与 TTL≤60。

## 六、面试自测（口头回答）

1. **为什么写操作删缓存而不是更新缓存？**
   （并发写 A 先 B 后：A 改 DB → B 改 DB → B 更缓存 → A 更缓存 = 缓存旧值持久；
   删缓存把一致性问题降为"下次读回源"，窗口最小。）
2. **先删缓存还是先改 DB？**
   （先 DB 后删：先删缓存则删后未提交的旧值会被并发读回填；先 DB 有一个经典竞态
   （读旧 DB 回填），由 TTL + 广播失效压制，本项目 TTL 30s 明示接受秒级脏读。）
3. **多级缓存的一致性靠什么？**
   （更新方删两级 + 广播 MQ 让所有实例删本地 + 本地 TTL 30s 兜底；广播模式消费失败
   不重投是明示取舍。）
4. **场景 F 为什么 DB 零查询？**
   （Caffeine 30s 内全命中；本地过期后 Redis 回填；布隆拦截不存在 ID；
   热点单活动 10000 次查询全部止步于应用层/Redis 层。）

## 七、自我检查标准

- [ ] MultiLevelCacheService 五个方法能各用一句话讲清；
- [ ] 三个实验完成（读链路/写失效/空值占位）；
- [ ] 能画出两级缓存读路径时序（L1 miss → L2 hit → 回填 L1）；
- [ ] 背出参数：500/30s/30min/60s 各代表什么。

## 附一、知识点详解

**1. MultiLevelCacheService.get 的完整决策树（背下来）**
```
get(key, loader, clazz)
 ├─ L1 Caffeine 命中 → 反序列化返回（微秒级）
 ├─ L2 Redis 命中 → 回填 L1 → 返回（毫秒级）
 ├─ loader 返回 null → Redis 写空值占位(TTL 60s) → 返回 null（防穿透）
 └─ loader 有值 → JSON 序列化 → Redis(TTL 30min) + 回填 L1 → 返回
```
deserialize 对空串返回 null——空值占位的还原通道。

**2. 广播失效的"消息体"设计**
CacheEvictConsumer 只解析一个字段 `cacheKey`；空串或以 `:` 结尾时 `invalidateAll()`——
留了一个"全量失效"的逃生通道（批量修改活动时用）。**广播消息要设计"最小必要信息"**，
携带太多字段会让消费端耦合生产端的模型。

**3. put 方法的注释为什么说"仍建议 evict 走 Cache Aside"**
put 是直写（双写缓存+DB 分离的窗口更大），只在"确定没有并发写"的场景用（如预热）。
类注释用一句话把"什么时候可以直写"讲清——读源码时要把这类"使用边界注释"收集起来。

**4. 活动详情为什么适合多级缓存而库存不适合**
详情：读多写少、字段多、容忍秒级脏（标题改了 30s 生效可接受）；
库存：高频原子变更（DECR/INCR），必须精确——所以库存不走 MultiLevelCacheService，
走 StockRedisService 的 Lua。**"什么进缓存"和"用什么方式缓存"是两个独立决策**。

## 附二、命中行为实验记录

```
实验1 读链路: 首次(回源)耗时 ___ms, 后续本地命中 ___ms, 差值≈___
实验2 写失效: 改标题后 GET 立即读到新值? Y/N; 广播日志出现? Y/N
实验3 空值占位: 第二次请求 DB 查询次数 0? Y/N; 占位 TTL=___s
```

---

## 附三、面试加练（3 题）

**1. Caffeine 为什么比手写 HashMap 缓存好？**
要点：内置淘汰（W-TinyLFU 比容量 LRU 命中率高）、过期（expireAfterWrite）、
统计（recordStats）、并发分段——手写版本四个都要自己实现且容易漏清理。
项目参数 maximumSize=500 + 30s TTL 是"小而准"的定位。

**2. 缓存 Key 的治理怎么防"Key 散落各处"？**
要点：RedisKeyConstants 集中定义 + 模板字符串（activityStock(id)）+ TTL 注释；
本地缓存 Key 与 Redis Key 同名——**一套 Key 体系管两级缓存**。

**3. 本地缓存对 GC 有什么影响？**
要点：500 个 JSON 字符串占用可控（几 MB）；但过期前常驻老年代，
频繁刷新会产生中等寿命对象——GC 日志（day26）里表现为 Survivor 间流动，
验证过无 GC 报警。**缓存大小要算进 JVM 内存账**。

**今日金句**：删缓存不是更新缓存——"下次读回源"是最便宜的一致性。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 多级缓存 | 04_Redis与缓存实战 第 2-3 节 |
| 广播失效 | 06_RocketMQ 第 5 节（CacheEvictConsumer） |

**容易说错的表述**：
- ✗ "先更新缓存再更新数据库" → ✓ "先 DB 后删缓存（Cache Aside 标准序）"；
- ✗ "本地缓存和 Redis 数据一致" → ✓ "本地 30s TTL 明示接受秒级脏读，广播+TTL 双兜底"。

---

## 明日预告

Day 11 缓存三大问题（穿透/击穿/雪崩）与布隆过滤器。
