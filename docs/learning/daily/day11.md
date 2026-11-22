# Day 11 · 缓存三大问题与布隆过滤器

> 阶段三第 4 天（阶段三最后一个新知日）。穿透/击穿/雪崩各配一个项目代码锚点。

## 一、今天学习什么

| 问题 | 定义 | 项目对策（代码锚点） |
|---|---|---|
| 缓存穿透 | 查不存在的数据，缓存永不命中直打 DB | ① Redisson RBloomFilter（BloomFilterService，fpp=0.01、expected=100000）；② 空值占位 TTL 60s（MultiLevelCacheService.get） |
| 缓存击穿 | 热点 Key 过期瞬间并发打 DB | ① Caffeine 先挡 30s；② 预热动作 @DistributedLock（activity:publish:）防并发重复预热；③ DB 回填幂等 |
| 缓存雪崩 | 大量 Key 同时过期/Redis 宕机 | ① TTL 按活动维度散开（活动结束+7 天，非整点对齐）；② 本地缓存独立 TTL；③ Redis 不可用时报名快速失败（Redis 权威扣减，宁可拒绝不脏写——StockRedisService 注释） |

布隆过滤器精读（`BloomFilterService`）：
- 原理：位数组 + k 个 hash；`mightContain=false` 一定不存在；`=true` 可能存在（误判）；
- **安全方向**：只误放不漏拦——作为穿透防线不会漏掉真实数据；
- 局限三条（类注释原文）：不支持删除（活动取消后仍放行，空值兜底）；
  Redis 重启丢失（tryInit 幂等 + rebuildActivityBloom 全量重建）；
  容量预估不准则误判率上升（expected-insertions=100000 的业务依据是活动量级）。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/redis/BloomFilterService.java` | 全文 + 类注释三特点 |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/redis/MultiLevelCacheService.java` | get 的空值占位分支 |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/redis/StockRedisService.java` | 类注释"Redis 异常时报名降级失败（快速失败优于脏数据）" |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/activity/service/ActivityService.java` | 布隆 addActivityId 的调用时机（创建活动后） |
| `backend/campusflow-server/src/test/java/com/campusflow/server/it/IntegrationTestBase.java` | ensureBloom：测试上下文布隆幂等初始化 |

## 三、搞懂哪些八股

- 布隆过滤器误判率公式直觉：位数组越大/元素越少 → 误判越低；fpp=0.01 表示 1% 误放；
- 布隆 vs 缓存空值：布隆省空间抗海量随机攻击，空值占位是"漏网之鱼"的兜底——两者叠加；
- 击穿与穿透区别：击穿的对象**存在**（Key 过期时机问题），穿透的对象**不存在**；
- Redis 高可用与雪崩：主从/哨兵/Cluster 提供可用性（day21 详述），
  应用侧则用"快速失败 + 限流"保护 DB。

## 四、运行哪些命令

```bash
# 1. 布隆 Key 观察
redis-cli -a 123321 MEMORY USAGE campusflow:bloom:activity

# 2. 穿击三连验证
curl -s "http://127.0.0.1:8080/api/v1/activities/99999"    # 不存在 → 布隆放行/空值占位
redis-cli -a 123321 GET campusflow:activity:99999:info     # 空占位

# 3. 观察预热锁
grep "lock:stock:preload\|库存预热完成" backend/logs/campusflow.log
```

## 五、完成什么实验

1. **误判率体感**：往布隆里加 100 个真实 ID，再用 1000 个随机 ID 查 mightContain，
   统计"可能存在"的比例（约 1% 量级）——亲手验证 fpp 参数；
2. **穿透路径验证**：连续请求不存在的活动 ID 50 次，统计日志中 DB 查询次数
   （预期：第一次空值占位后为 0）；
3. **击穿模拟**：删掉热点活动的两级缓存，用 JMeter 50 并发同时请求详情，
   观察日志 DB 查询次数（预期：回源 1 次 + 其余等待结果，Caffeine/Redis 单飞效应）。

## 六、面试自测（口头回答）

1. **布隆过滤器误判了怎么办？**
   （误判方向是"不存在→可能存在"放行，由缓存空值/DB 查询兜底，业务正确性无损；
   不会漏判才是布隆能当防线的根本。要主动说出 fpp=0.01 与容量参数的取舍。）
2. **活动取消后布隆还能放行，怎么办？**
   （不支持删除是布隆固有限制；本项目用缓存空值占位 + 活动查询本身的 status 校验兜底；
   大规模场景可换 Counting Bloom Filter / Cuckoo Filter 支持删除。）
3. **Redis 挂了你的报名链路会怎样？**
   （Lua 执行失败 → 报名快速失败返回 REDIS_ERROR 提示稍后再试——设计上 Redis 是库存权威，
   宁可拒绝也不能落脏数据；预热丢失有懒预热自愈（META_MISSING → warmupStockIfNeeded 重试）。）
4. **雪崩你怎么防？**
   （TTL 天然错峰：按活动结束时间+7 天散开；本地/Redis 双层不同 TTL；
   应用侧快速失败保护 DB；高可用上主从/Cluster——day21。）

## 七、自我检查标准

- [ ] 三大问题 × 项目对策的表格能默写（每格都有类名）；
- [ ] 三个实验完成（误判率/穿透/击穿）；
- [ ] 能画出"请求 → 布隆 → 空值占位 → DB"的穿透拦截链；
- [ ] 快速失败优于脏数据这句设计原则能用自己的话解释。

## 附一、知识点详解

**1. 布隆过滤器的内存账（面试加分计算）**
RBloomFilter expected=100000、fpp=0.01 → 最优位数 m ≈ n × ln(1/fpp)/(ln2)²
≈ 100000 × 9.585 / 0.480 ≈ 200 万 bit ≈ 240KB，hash 函数 k≈7。
**20 万分之一的服务器内存换来 1% 误判的穿透防线**——算一遍账，参数就不神秘了。

**2. 三大问题的"责任链"视角**
一个活动详情请求的完整防护链：布隆（拦截不存在）→ Caffeine（本地 30s）→
Redis（30min）→ 空值占位（DB 无数据的短缓存）→ DB。
每层防的是不同的事故：随机 ID 攻击、过期风暴、不存在数据、真实冷数据。
面试时画出这条链，比背"穿透/击穿/雪崩"三个名词高级得多。

**3. 快速失败优于脏数据的工程依据（StockRedisService 注释展开）**
报名链路 Redis 是"扣减权威"：Redis 挂了继续放行会导致**库存超卖**（校验失败）；
直接拒绝只损失可用性，不损失正确性——CAP 里选了 CP（对库存而言）。
但详情查询挂了可以走 DB（读链路降级）——**同一系统里读写两侧的一致性策略可以不同**。

**4. 预热的幂等与锁的配合**
approvePublish 的 @DistributedLock 防两个管理员同时审核；warmupStockIfNeeded 的
懒预热自愈没有锁（重复预热最多多算一次 DB count，结果幂等）——
**锁用在高代价重复上，幂等用在低代价重复上**。

## 附二、三实验记录

```
实验1 误判率: 1000 次随机 ID 中"可能存在"___次 → fpp≈___
实验2 穿透: 连续 50 次不存在 ID, DB 查询___次
实验3 击穿: 50 并发冷启动, DB 回源___次 (单飞?)
```

---

## 附三、面试加练（3 题）

**1. 布隆过滤器与 Cuckoo Filter 怎么选？**
要点：布隆简单省内存但不支持删除；Cuckoo 支持删除且误判率相近但实现复杂、
插入可能失败需扩容。本项目活动 ID 基本不删（取消≠删除），布隆够用。

**2. 空值占位与布隆的检查顺序怎么定？**
要点：先布隆（内存位运算，纳秒级）拦截明显不存在的；放行后查缓存，
空值占位兜"布隆误判+新建活动"。两层各防一类：布隆防海量随机攻击，
空值防少量误判与新建窗口。

**3. 怎么发现"应该预热而没预热"的活动？**
要点：三路——发布时预热（主动）、META_MISSING 懒预热（被动自愈）、
stockReconcile 巡检重建（兜底）；监控上可加"报名链路 -5 返回率"告警
——三路+监控才是完整方案。

**今日金句**：缓存问题不是"加缓存"能解决的，是"防什么事故"设计的。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 三大问题 | 04_Redis与缓存实战 缓存问题章节 |
| 布隆过滤器 | 04_Redis 布隆章节；14_面试题 Q10 |

**容易说错的表述**：
- ✗ "布隆过滤器会漏判" → ✓ "布隆只会误放（false positive），绝不漏拦"；
- ✗ "Redis 挂了系统就挂了" → ✓ "报名快速失败、详情降级 DB、状态可重建——有损但可控"。

---

## 明日预告

Day 12 进入阶段四（高并发报名主链路）：状态机视角看报名全链路。
