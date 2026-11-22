# Day 27 · 分库分表（ShardingSphere）

> 阶段九。单日主题（内容密度高），主教材 `docs/learning/10_分库分表与ShardingSphere.md`。

## 一、今天学习什么

1. **为什么拆**：registration 增长最快 + 按 user_id 查询最高频；
2. **分片键选择论证**：user_id（用户维度直达/无热点）vs id（无业务查询）vs
   activity_id（热点活动单分片打爆）——"分片键=最高频等值查询维度"；
3. **真实配置**（sharding.yaml）：Standalone/JDBC 模式、两数据源 Hikari、
   standard 策略 + INLINE `ds_${user_id % 2}`、keyGenerateStrategy SNOWFLAKE；
4. **真实教训**：type: MOD 是 AUTO_SHARDING 分类算法，经典配置启动失败 → INLINE 修正；
5. **启动隔离**：application-sharding.yml（ShardingSphereDriver + flyway 关闭），
   默认 local 完全不加载；IT 用编程式 YAML 数据源独立构建；
6. **五个测试**（ShardingSphereShardingIT）：带键写入路由正确 / 带键查询单库 /
   无键广播聚合 / 跨分片排序归并 / 全局 ID 与分片键无关。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-server/src/main/resources/sharding.yaml` | 全文 55 行逐段读 |
| `backend/campusflow-server/src/main/resources/application-sharding.yml` | 驱动 URL + flyway=false 的原因 |
| `backend/campusflow-server/src/test/java/com/campusflow/server/it/ShardingSphereShardingIT.java` | 218 行全文（五个测试 + 内嵌 YAML + 直连物理库核对） |
| `docs/learning/10_分库分表与ShardingSphere.md` | 主讲义（含 mermaid 路由图） |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/compensation/scheduler/CompensationScheduler.java` | 找出分片模式下会广播的 SQL（stockReconcile 的 selectCount） |

## 三、运行哪些命令

```bash
# 准备分片库
mysql -uroot -p -e "CREATE DATABASE campusflow_ds0; CREATE DATABASE campusflow_ds1;"

# 跑分片 IT（真实 MySQL 双库）
mvn -pl backend/campusflow-server test -Dtest=ShardingSphereShardingIT

# 验证数据分布（绕过 ShardingSphere 直连）
mysql -uroot -p campusflow_ds0 -e "SELECT COUNT(*) FROM registration WHERE user_id%2=0;"
mysql -uroot -p campusflow_ds1 -e "SELECT COUNT(*) FROM registration WHERE user_id%2=1;"

# 开 sql-show 观察路由
# sharding.yaml props: sql-show: true 后重跑，抄 Logic SQL 与 Actual SQL
```

## 四、完成什么实验

1. 五个 IT 全绿后直连两库核对（ds0 偶数 5 条 / ds1 奇数 5 条）；
2. **路由日志分析**：sql-show=true 重跑，把"带键查询命中 1 个 Actual SQL"与
   "无键查询出现 2 条 Actual SQL"截图对比；
3. **MOD 踩坑复现**：算法改 `type: MOD` 跑 IT 记录启动异常，再改回 INLINE 修复；
4. **深分页推演**：`ORDER BY user_id LIMIT 900, 10`（sql-show 开）观察各分片
   实际执行的 LIMIT 参数。

## 五、面试自测（口头回答，答案要点）

1. **分片键为什么选 user_id？**
   （最高频等值查询（我的报名）直达单库；activity_id 会让热点活动单分片打爆、
   用户查询广播；id 无业务查询语义——三个候选的论证过程比结论值钱。）
2. **不带分片键的查询代价？**
   （全路由 N 倍 + 归并内存；分页更深：每分片取前 offset+size 行；对策：
   查询设计带键、统计异构（练习 9 索引表）、游标分页（练习 11）——IT 测试 3/4 实证。）
3. **踩过 ShardingSphere 什么坑？**
   （MOD 属 AUTO_SHARDING 分类与经典 standard 策略不兼容，启动失败；
   INLINE + algorithm-expression 修正；另一个坑：分片模式接管 DataSource 后 Flyway
   必须关闭，schema 由脚本独立初始化。）
4. **分片后全局 ID 怎么办？唯一约束呢？**
   （雪花 ID 与分片键无关仍全局唯一（keyGenerateStrategy 兜底）；
   唯一索引包含分片键（uk_activity_user 含 user_id），同用户行必同分片，约束本库有效。）
5. **怎么扩容 2→4？**
   （双写迁移六步：新集群→双写→存量搬迁→校验→读灰度→停老写；INLINE 改
   `ds_${user_id % 4}` 只是配置，难在数据与流量（16 讲练习 12）。）

## 六、自我检查标准

- [ ] sharding.yaml 逐段能讲（mode/dataSources/rules/props 四大块）；
- [ ] 五个测试 + 实验全部完成，路由日志截图归档；
- [ ] MOD 教训能完整讲述（错误配置 → 异常 → 修正 → 归类原因）；
- [ ] 讲义 10 的 Q1-Q9 全过一遍；错题本更新。

## 附一、知识点详解

**1. IT 内嵌 YAML 的教学价值（为什么不用主配置）**
ShardingSphereShardingIT 用字符串 YAML + `YamlShardingSphereDataSourceFactory`
编程式建数据源——与 Spring 主上下文完全隔离。收益：① IT 可单独跑（不起整个应用）；
② 主配置改动不影响分片验证；③ 参数可编程替换（__DS0__ 占位符）。
**集成测试的"环境自包含"设计**值得每个项目学习。

**2. 路由日志的"两种典型形态"（sql-show 实验的判读标准）**
定向：`Actual SQL: ds_0 ::: SELECT ... user_id = ?`（一条）；广播：`ds_0 ::: ...` 与
`ds_1 ::: ...`（两条同文）。带键写入是定向；无键 SELECT/COUNT/UPDATE 是广播。
**看到 UPDATE 广播要警惕**（两库都执行，代价与锁窗口翻倍）。

**3. 归并的三类（跨分片结果集处理）**
流式归并（ORDER BY 多路归并，内存友好）、内存归并（复杂排序/聚合）、
装饰归并（分页 offset 处理）。测试 4 的 `ORDER BY user_id DESC LIMIT 3` 属于
流式归并——每库返回有序前 N 即可，代价可控；`LIMIT 900,10` 则要求每库取 910 行。

**4. 与 MP ASSIGN_ID 的关系**
application.yml 注释："全局唯一 ID：应用层雪花算法（MP ASSIGN_ID），分片键不含 ID
也能保证全局唯一"——应用层生成优先（insert 前就拿到 ID，便于构建关联键）；
ShardingSphere 的 keyGenerateStrategy 是兜底（手工 SQL/其他客户端写入时）。
**双保险但责任分明**。

## 附二、五个测试 × 结论对照

```
测试1 带键写入: 偶→ds0 奇→ds1 (直连物理库核对)
测试2 带键查询: Actual SQL 仅 1 条
测试3 无键查询: Actual SQL 2 条, COUNT=10=两库之和
测试4 跨分片分页: ORDER BY DESC LIMIT 3 归并正确
测试5 全局ID: 分片键无关的雪花 ID 全局唯一
```

---

## 附三、面试加练（3 题）

**1. 哪些表不需要跟风分片？**
要点：小而稳定的（t_role/sys_audit_log 增长可控）、强中心化聚合查询的
（activity 主表——详情全走缓存）、一次写入多次读的（notify_message 可按月归档
代替分片）——**分片是负债不是装饰**。

**2. ShardingSphere 分片和 MySQL 原生分区（PARTITION）区别？**
要点：分区单库内按行规则切物理段（对查询透明，不能跨机器扩容）；
分片跨库跨实例（能水平扩容但引入路由/归并/跨库事务）——先分区后分片
的演进顺序适用于大多数场景。

**3. 广播 SQL 的结果可以缓存吗？**
要点：可以——无分片键的统计（confirmed_count 类）结果按 activityId 缓存
（TTL 短），把 N 次广播摊薄成 1 次广播 + N 次缓存读；或走异构表（练习 9）
根治。**缓存是广播的临时止痛药，异构才是根治**。

**今日金句**：分片键的选择是一场"查询分布"的分析题，不是技术偏好题。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 分片设计 | 10_分库分表与ShardingSphere 全篇 |
| 路由验证 | 10_讲义第五节 + ShardingSphereShardingIT |

**容易说错的表述**：
- ✗ "用了 MOD 算法分片" → ✓ "INLINE 表达式 ds_${user_id % 2}（MOD 是 AUTO_SHARDING 分类，经典配置不可用）"；
- ✗ "所有查询都会广播" → ✓ "带分片键直达单库，无分片键才广播"。

---

## 明日预告

Day 28 压测与性能：场景 A/B/F 数字记忆卡。
