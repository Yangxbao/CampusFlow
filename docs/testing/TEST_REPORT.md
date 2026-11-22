# CampusFlow 测试报告（真实执行记录）

> 执行日期：2026-11-21
> 环境：JDK17（Temurin 17.0.21.1+2）/ MySQL 8.0.34 本机 / Redis 6.2.6 + RocketMQ 4.9.4（VMware 虚拟机 192.168.100.128）
> 原则：**单元测试可 Mock，集成测试全部使用真实中间件（MySQL + Redis + RocketMQ），禁止 Mock 中间件。**

---

## 一、测试体系总览

| 层级 | 位置 | 数量 | 结果 |
|---|---|---|---|
| 单元测试（common） | `backend/campusflow-common/src/test` | 3 类 8 用例（雪花唯一性/递增/校验、JSON 往返） | ✅ 8/8 |
| JUC 实验测试 | `backend/concurrency-lab/src/test` | 10 类 38 用例（线程池/锁/CAS/ABA/AQS/ThreadLocal/CHM） | ✅ 38/38 |
| JVM 实验测试 | `backend/jvm-lab/src/test` | 10 类 30 用例（OOM/栈溢出/Metaspace/GC/类加载/死锁） | ✅ 30/30 |
| **集成测试（真实中间件）** | `backend/campusflow-server/src/test/java/.../it/` | 5 类 10 用例 | ✅ 10/10 |
| ShardingSphere 分片测试 | 同上（独立无 Spring 上下文） | 3 用例 | ✅ 3/3 |
| 端到端 API 测试 | `scripts/e2e_api_test.py`（25 断言） | 25 | ✅ 25/25 |
| 浏览器 GUI 冒烟 | 三端（学生/组织者/管理员）真实浏览器 | 关键流程 | ✅ 通过 |
| JMeter 压测 | 见 `docs/performance/LOAD_TEST_REPORT.md` | 场景 A/B/F | ✅ 见报告 |

## 二、集成测试用例明细（真实 MySQL + Redis + RocketMQ）

基类 `IntegrationTestBase`：`@SpringBootTest` + `test` 档案（campusflow_test 库、Redis db1、消费组 -it 后缀），
Flyway 编程式迁移，数据工厂创建真实用户/活动（走真实发布链路触发库存预热）。

### LuaOversellIT —— 高并发不超卖（30 用户并发抢 10 名额）
- 30 个真实用户并发调用 `RegistrationService.signup`（完整链路：Lua → 预留落库 → MQ → 消费确认）
- ✅ 断言：DB 有效报名 == 10（不超卖不少卖）；Redis 库存 ≥ 0；成功用户集合大小 == 10；报名表 == 10 行（一人一行）；无非预期错误

### IdempotencyIT —— 一人一单与消息幂等（3 用例）
- ✅ `数据库唯一约束拦截绕过Redis的重复报名`：直接 INSERT 第二行 → `DuplicateKeyException`
- ✅ `重复消费同一确认消息不产生重复报名`：同一 confirm 消息消费两次 → 仍 1 条
- ✅ `真实MQ重复投递消费幂等`：真实 Broker 发送两条相同 msgKey 通知 → 消费端幂等只落 1 条
  （此用例曾真实暴露并发重复消费竞态 bug，修复为 Redis 认领锁 + processed 标记 + DB 唯一约束三层防线）

### CancelConcurrencyIT —— 并发取消（10 线程同时取消同一报名）
- ✅ 库存审计 `CANCEL-{id}` 恰好 1 条（幂等记账防重复加回）；Redis 库存精确恢复；终态 CANCELLED

### CheckinConcurrencyIT —— 并发核销同一凭证（10"管理员"同时扫码）
- ✅ 成功恰好 1 次、拒绝 9 次；checkin_record 1 条；registration → CHECKED_IN；activity.checked_in_count = 1

### CompensationRecoveryIT —— 故障恢复（4 用例）
- ✅ `预留超时后恢复重发消息并完成确认`：模拟确认丢失 → 恢复任务对账修复（不误释放、不重复落库）
- ✅ `预留重试耗尽后补偿释放名额`：模拟"Lua 后进程退出" → 释放幂等、库存加回、审计 1 条、通知用户
- ✅ `本地消息表失败消息自动重投成功`：NEW → 调度重投 → SENT（Broker 真实接收）
- ✅ `库存对账发现并修复漂移`：手工制造 Redis-5 漂移 → 对账修复至 DB 权威值 + RECONCILE 审计

## 三、端到端 API 测试（scripts/e2e_api_test.py，25/25）

覆盖：活动列表 → 报名受理 → 重复报名拦截 → MQ 异步确认落库 → 签到凭证签发 → 取消报名 → 重复取消幂等 → 取消后重新报名（同一行状态回迁）→ 候补语义 → 组织者列表/统计/报名人员/CSV 导出 → 管理员仪表盘/审核通过（触发预热）/消息状态/审计 → 三级越权拦截（学生访问 admin/organizer 40301、未登录 40101）→ 通知链路。

## 四、真实执行中暴露并修复的问题（对开发有价值）

1. **多环境消费组共享 Broker 导致消息被瓜分**：测试 JVM 与运行中应用同组，Broker 负载均衡 7/3 分流确认消息 → 引入 `group-suffix` 环境隔离。
2. **并发重复消息的幂等竞态**：两条相同 msgKey 消息并发到达，检查-插入窗口导致双落库 → Redis 认领锁修复（60s TTL，崩溃后可重试）。
3. **雪花 Long 精度丢失**：前端 JS 53 位安全整数 → Jackson 全局 Long→String。
4. **缓存对象无默认构造器**：ActivityVO 仅 @Builder 导致缓存回读失败 → 补 @NoArgsConstructor。
5. **JDBC URL `characterEncoding=utf8mb4` 非法**：Java 无此 charset → 改 UTF-8。
6. **Flyway 脚本内 `USE` 破坏多库**：迁移 SQL 不应切换 schema，由连接串决定。
7. **fat-jar 扫描双注册**：多模块嵌套 jar 以两种资源形式扫描 → allow-bean-definition-overriding。
8. **SS 5.4.1 的 MOD 算法属 auto-sharding**：经典 actualDataNodes 配置需用 INLINE（SS 5.5.0 起坐标亦更名 shardingsphere-jdbc，并兼容 snakeyaml 2.x，与 Boot 3.2 共存）。
9. **waitlist_serve.lua 混合返回类型**：脚本返回 Lua 数字（0/1）与字符串（userId）混合，Lettuce 的 String 结果通道不支持整数回复 → 候补晋级在"队列空/库存空"分支抛 UnsupportedOperationException。修复：所有分支统一返回字符串。修复后已用"容量1→报满→候补→取消→自动晋级→确认转正"全链路 E2E 实测通过。
10. **PageResult.total 被 Long→String 波及**：分页 total 变字符串影响前端分页组件 → 前端拦截器统一 Number() 标准化（业务 ID 保持字符串防精度丢失）。

## 五、复现命令

```bash
# 单元 + 实验模块
mvn -pl backend/campusflow-common test
mvn -pl backend/concurrency-lab test
mvn -pl backend/jvm-lab test

# 集成测试（需本机 MySQL + VM Redis/RocketMQ 在线）
mvn -pl backend/campusflow-server -am test "-Dtest=LuaOversellIT,IdempotencyIT,CancelConcurrencyIT,CheckinConcurrencyIT,CompensationRecoveryIT"

# 分片测试
mvn -pl backend/campusflow-server -am test -Dtest=ShardingSphereShardingIT

# 端到端 API（应用已启动）
python scripts/e2e_api_test.py
```
