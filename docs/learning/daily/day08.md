# Day 08 · MySQL 索引与 SQL 优化

> 阶段三（08-11 MySQL 与缓存）第 1 天。今天从本项目表结构出发学索引。

## 一、今天学习什么

1. B+ 树结构回顾：非叶节点导航、叶节点存数据（聚簇）或主键（二级索引）；
2. 本项目索引设计逐个剖析（V1__init_schema.sql）：
   - `registration`：PK(id 雪花) + `uk_activity_user(activity_id,user_id)`（业务唯一约束）
     + `idx_user_status(user_id,status)`（我的报名列表，也是未来分片键索引）；
   - `registration_state_log`：按 registration_id 关联查询；
   - `message_deliver`：按 status+next_retry_time 扫描重投（补偿扫描的查询模式决定索引）；
3. 最左前缀 / 覆盖索引 / 回表三个概念在 `idx_user_status` 上的体现：
   `SELECT * FROM registration WHERE user_id=? AND status IN (1,2) ORDER BY registered_at DESC`
   ——回表取全行；若只查 count 则索引覆盖；
4. 写 SQL 的习惯：项目 Mapper 全部走 MyBatis-Plus LambdaQueryWrapper，
   `last("LIMIT 1")` 是显式终止扫描的小技巧（RegistrationTxService 里大量出现）。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-server/src/main/resources/db/migration/V1__init_schema.sql` | 每张表的 KEY 定义逐行读 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/service/RegistrationService.java` | pageMyRegistrations 的查询模式（user_id+status+排序） |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/mq/MessageDeliverService.java` | listRetryDue：status IN + next_retry_time <= now 的扫描查询 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/activity/service/ActivityService.java` | countActiveRegistrations：activity_id + status IN 的统计查询 |

## 三、搞懂哪些八股

- 为什么雪花 ID 做主键比 UUID 好？（有序插入避免页分裂，idaknows：UUID 无序导致 B+ 树
  中间插入与页碎片）；
- 唯一索引 vs 普通索引的写性能（唯一索引多一次唯一性检查，但本项目要业务约束，必须唯一）；
- IN 与 OR 的索引使用；status IN (1,2) 属于范围类，idx_user_status 仍可用；
- 深分页问题（`LIMIT 100000,10` 扫描 offset+size 行）与游标方案（16 讲练习 11）。

## 四、运行哪些命令

```bash
mysql -uroot -p campusflow

-- 1. 看"我的报名"查询计划（用种子数据里的用户）
EXPLAIN SELECT * FROM registration WHERE user_id=2 AND status IN (1,2)
        ORDER BY registered_at DESC LIMIT 10;
-- 期望: type=range, key=idx_user_status, rows 较小

-- 2. 看补偿扫描计划
EXPLAIN SELECT * FROM message_deliver WHERE status IN (0,2)
        AND next_retry_time<=NOW() ORDER BY next_retry_time LIMIT 50;

-- 3. 全表扫描对比（故意用无索引列）
EXPLAIN SELECT * FROM registration WHERE registered_at > NOW() - INTERVAL 1 DAY;
```

## 五、完成什么实验

1. EXPLAIN 三连（上面三条），把 type/key/rows/Extra 记录成表；
2. 造 5 万行测试数据（`INSERT ... SELECT ... , (n)` 循环或准备脚本），对比
   `ORDER BY registered_at LIMIT 10` 与 `LIMIT 50000,10` 的执行时间；
3. 慢 SQL 观察：把慢日志阈值调到 0（`SET GLOBAL slow_query_log=1,
   long_query_time=0`）跑一次报名，看记录哪些语句（预期报名主链路无慢查询——
   压测报告结论"报名链路写库由消费者异步完成"）。

## 六、面试自测（口头回答）

1. **"我的报名"查询走了什么索引？为什么？**
   （idx_user_status(user_id,status)：等值 user_id + IN status 走 range，
   最左前缀成立；排序字段不在索引内需 filesort 或回表——量小可接受。）
2. **uk_activity_user 除了约束还有什么作用？**
   （一人一单的最终防线 + (activity_id,user_id) 组合查询直接走该索引；
   分片场景下它包含分片键 user_id，分片内约束依然有效。）
3. **为什么项目所有写路径都带 LIMIT 1 的先查再改？**
   （幂等预检：确认/取消先 SELECT 判状态再 CAS，避免无效写与锁争用；
   并发窗口由唯一约束/乐观锁兜底。）
4. **一条 SQL 慢了，你的排查套路？**
   （EXPLAIN 看 type/key/rows/Extra → 是否走错索引或索引失效（函数/隐式转换/前导 %）
   → 数据量级判断 → 重构索引或改查询模式 → 线上用慢日志定位。）

## 七、自我检查标准

- [ ] EXPLAIN 三连结果表完成，能解释每列含义；
- [ ] 能默写 registration 三索引及其服务的查询；
- [ ] 深分页对比实验完成（数字记在笔记）；
- [ ] 说得清"补偿扫描为什么需要 (status,next_retry_time) 类索引设计"。

## 附一、知识点详解

**1. 补偿扫描类查询的索引设计思维**
`listRetryDue` 的查询模式是 `status IN (0,2) AND next_retry_time<=now ORDER BY next_retry_time LIMIT 50`。
扫描频率高（15s 一次）但命中行通常为 0——这类"低命中高频扫描"的查询，理想索引是
`(status, next_retry_time)` 复合，让 MySQL 直接在索引上做范围+排序。
思考延伸：也可以给表加"待重投数"计数或改用延迟队列，但小项目定时扫描更简单——
**方案复杂度要匹配业务规模**。

**2. countActiveRegistrations 的口径设计**
`SELECT COUNT(*) WHERE activity_id=? AND status IN (CONFIRMED, CHECKED_IN)`——
为什么不含 RESERVED？（RESERVED 尚未确认，可能被补偿释放；对账口径必须"最终事实"）。
这个函数被预热/对账/容量调整三处复用，口径统一是一致性的地基。

**3. 为什么雪花 ID 主键 + AUTO_INCREMENT 不用**
自增 ID 在分库后冲突（两库各自发号）；UUID 无序导致 B+ 树页分裂；雪花 ID
时间有序 + 全局唯一 + 趋势递增——三个候选的取舍是索引设计的必答题。

**4. last("LIMIT 1") 的真实用途**
MyBatis-Plus 的 selectOne 在多行时会抛异常，加 `last("LIMIT 1")` 保证单行语义
（先查后改的幂等预检不需要"发现脏数据就报错"，取任一行即可判断）。

## 附二、EXPLAIN 结果记录表（实验 1 的交付格式）

| SQL | type | key | rows | Extra | 结论 |
|---|---|---|---|---|---|
| 我的报名 | range | idx_user_status | 小 | Using index condition | 走索引 OK |
| 补偿扫描 | range | (status,next_retry_time) | 通常 0-50 | Using where | 低命中高频 |
| registered_at 全查 | ALL | NULL | 全表 | Using where | 索引缺失案例 |

---

## 附三、面试加练（3 题）

**1. 联合索引 (user_id, status) 与 (status, user_id) 怎么选？**
要点：看查询形态——等值 user_id + 范围/IN status：等值列在前（本项目的选法）；
若主要查询是"全站已报名统计"（status 等值 + user_id 无约束）才反转。
**列顺序 = 过滤性排序**，与查询分布有关不是背口诀。

**2. 索引下推（ICP）是什么？**
要点：EXPLAIN Extra 出现 Using index condition——WHERE 中的非前缀列条件
在存储引擎层先过滤再回表，减少回表次数。idx_user_status 的 status IN 条件
受益于 ICP。

**3. message_deliver 的 status 为什么不用枚举字符串？**
要点：TINYINT + 常量类；扫描索引键更短；状态新增走代码常量；
配合 markSent/markFailed 等"动作语义"的 UPDATE 语句，避免状态字段散改。

**今日金句**：索引设计不是"给每列建索引"，而是"为每种查询形态找最小索引"。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 表结构与索引 | 03_MySQL与数据库设计 |
| 查询模式 | 15_源码路线 Day2/Day6（补偿扫描） |

**容易说错的表述**：
- ✗ "索引越多越好" → ✓ "每个索引都是写放大，按查询形态设计最小集"；
- ✗ "status IN 走不了索引" → ✓ "IN 是 range，idx_user_status 可用（EXPLAIN 验证）"。

---

## 明日预告

Day 09 事务隔离级别与锁：从 uk 约束的并发窗口说起。
