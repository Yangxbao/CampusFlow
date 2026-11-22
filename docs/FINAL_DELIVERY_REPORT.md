# CampusFlow 最终交付报告

> 交付日期：2026-11-21
> 交付原则执行情况：**真实运行优先于技术名词，真实测试优先于完成声明** —— 本报告所有"✅"均对应真实执行记录，所有未验证项如实标注"未验证"。

---

## 1. 项目完整目录

**项目规模**：后端 184 个 Java 文件 / 14766 行 · 前端 28 个 TS/Vue 文件 / 2585 行 · 89 个自动化测试用例（全部通过） · 52 篇中文文档 · 17 张数据库表 · 3 个 Lua 脚本 · 6 个 MQ Topic


```
CampusFlow/
├── pom.xml                      # Maven 父工程（版本全部锁定，无浮动 latest）
├── backend/
│   ├── campusflow-common/           # 统一响应/错误码/枚举/雪花ID/工具（8 单测通过）
│   ├── campusflow-infrastructure/   # Redis+Lua/Redisson/RocketMQ 可靠消息/多级缓存/布隆/限流/分布式锁/JWT/线程池
│   ├── campusflow-business/         # 12 个业务模块（用户/活动/报名/候补/签到/通知/统计/管理/审计/补偿/种子/MQ消费者）
│   ├── campusflow-server/           # 启动装配/拦截器/全局异常/Flyway/分片配置/集成测试（10+3 用例）
│   ├── concurrency-lab/             # JUC 实验：10 个实验类 + 38 个测试 + 11 篇讲义
│   └── jvm-lab/                     # JVM 实验：11 个实验类 + 30 个测试 + 8 篇讲义 + 8 个诊断脚本
├── frontend/                    # Vue3+TS：19 个视图（学生7/组织者7/管理员6），构建通过 + 浏览器实测
├── database/
│   ├── docs/DATABASE_DESIGN.md      # （根 docs/database 为权威版本）ER 图/EXPLAIN/设计依据
│   └── seed/SEED_ACCOUNTS.md        # 种子账号与压测数据说明
├── deploy/                      # docker-compose.yml(8服务) / 2个Dockerfile / nginx / rocketmq broker.conf / 5个脚本
├── load-test/                   # 3个 .jmx + 1000用户CSV + .jtl 原始数据 + HTML 报告
├── docs/
│   ├── FINAL_DELIVERY_REPORT.md     # 本文档
│   ├── PROJECT_STATUS.md            # 功能完成状态清单
│   ├── architecture/ARCHITECTURE.md
│   ├── api/API_REFERENCE.md         # 接口清单 + 错误码 + 测试账号
│   ├── database/DATABASE_DESIGN.md  # 17表设计/ER图/EXPLAIN
│   ├── testing/TEST_REPORT.md       # 全部测试真实记录
│   ├── performance/LOAD_TEST_REPORT.md  # JMeter 真实压测报告
│   └── learning/                    # 16 篇讲义 + daily/ 30 天任务（独立目录）
├── scripts/                     # e2e_api_test.py(25断言) / prepare_loadtest.py
├── .env.example / .gitignore / README.md
```

## 2. 实际实现的业务功能

**学生端**：注册/登录、活动推荐/分类/搜索/详情（布隆+多级缓存）、报名（异步确认）、我的报名、取消报名（幂等+触发候补）、候补排队/晋级确认/退出、电子签到凭证（二维码）、消息通知、个人信息与改密。
**组织者端**：工作台（活动全生命周期：创建→编辑→提交审核→发布→调容量→取消）、报名人员查询、候补人员管理、活动统计（ECharts 趋势图）、CSV 异步导出与下载、扫码核销签到。
**管理员端**：平台仪表盘（ECharts）、活动审核（通过触发库存预热）、用户管理（禁用/启用）、操作审计、本地消息表状态、补偿任务（含死信重入队）。

## 3. 技术栈与具体应用位置

| 技术 | 应用位置 |
|---|---|
| Spring Boot 3.2.5 / MVC / AOP / Validation | 全部模块；AOP：DistributedLockAspect、AuditLogAspect |
| MyBatis-Plus 3.5.5（boot3 starter） | 全部 Mapper；分页/乐观锁/自动填充/逻辑删除插件（MybatisPlusConfig） |
| JWT（jjwt 0.12.5） | JwtUtils + JwtAuthInterceptor + AuthContext(ThreadLocal+清理) |
| BCrypt（spring-security-crypto） | UserService 密码哈希 |
| Redis（Lettuce）+ Lua | StockRedisService + resources/lua/{reserve,cancel_release,waitlist_serve}.lua |
| Redisson 3.27.2 | 分布式锁（注解+编程式）、布隆过滤器 RBloomFilter、看门狗 |
| RocketMQ 客户端 5.1.4（原生，非 starter） | MqProducerConfig(@Bean(initMethod=start))、6 个消费者、本地消息表、消费幂等 |
| Caffeine | MultiLevelCacheService（30s 本地缓存 + 广播失效） |
| Flyway 9.22.3 | V1 建表（17 表）+ V2 角色种子；真实执行通过 |
| Knife4j 4.4.0 | /doc.html 在线文档 |
| JUnit5 / Mockito（spring-boot-starter-test） | 集成测试 + 单测 |
| ShardingSphere-JDBC 5.4.1 | test 域 + application-sharding.yml（分片测试 3/3） |
| zxing / qrcode | 后端发凭证 token，前端渲染二维码 |
| JMeter 5.6.3 | 3 场景 .jmx，真实执行 |
| Vue3.4/TS5/Vite5/Pinia/Element Plus 2.7/ECharts5 | frontend/ |

## 4. 数据库结构

17 张表（清单与 ER 图：`docs/database/DATABASE_DESIGN.md`），全部由 Flyway 真实执行创建。
核心约束：`registration` 的 `uk_activity_user`（一人一单最终防线）、`checkin_record.uk_registration`、`stock_audit_log.uk_biz_key`（补偿幂等）、`message_consume_log.uk_msg_group`。
主键雪花 BIGINT；乐观锁 version（registration/activity）；逻辑删除（user/activity）。

## 5. Redis Key 设计（详见 common/RedisKeyConstants.java）

| Key | 类型 | 用途 | TTL |
|---|---|---|---|
| `campusflow:activity:{id}:stock` | String | 剩余名额（Lua 扣减对象） | 活动结束+7天 |
| `campusflow:activity:{id}:meta` | Hash | Lua 校验数据源(status/窗口) | 同上 |
| `campusflow:activity:{id}:members` | Set | 已报名成员（一人一单前置去重） | 同上 |
| `campusflow:activity:{id}:waitlist` | ZSet | 候补队列（score=入队时间） | 同上 |
| `campusflow:reservation:{aid}:{uid}` | String | 预留状态（REDIS_RESERVED/WAITLIST_RESERVED） | 15min / 30min |
| `campusflow:idempotent:msg:*` | String | 消费幂等（claim 认领锁 60s + processed 24h） | 动态 |
| `campusflow:ratelimit:signup:{aid}:{uid}` | String | 用户级限流计数 | 10s 窗口 |
| `campusflow:checkin:once:{hash}` | String | 签到一次性令牌 | 5min |
| `campusflow:lock:*` | Redisson | 预热/晋级/补偿任务/活动状态锁 | 看门狗 |
| `campusflow:bloom:activity` | 位图 | 活动存在性布隆（fpp=0.01） | 持久+启动重建 |

## 6. RocketMQ Topic 与消费者组

| Topic | Tag | 消费组 | 模式 |
|---|---|---|---|
| campusflow-registration-confirm-topic | CONFIRM | campusflow-registration-group | 集群 |
| campusflow-cancel-release-topic | RELEASE | campusflow-cancel-group | 集群 |
| campusflow-waitlist-promote-topic | PROMOTE | campusflow-waitlist-group | 集群 |
| campusflow-notify-topic | NOTIFY | campusflow-notify-group | 集群 |
| campusflow-checkin-topic | CHECKIN | campusflow-checkin-group | 集群 |
| campusflow-cache-evict-topic | EVICT | campusflow-cache-evict-group | **广播**（多实例本地缓存同步） |

环境隔离：消费组自动追加 group-suffix（test 环境 `-it`），教训来源见测试报告。

## 7. 线程池参数（ThreadPoolConfig，全部有界 + 显式拒绝策略）

| 池 | core/max/queue | 拒绝策略 | 用途 |
|---|---|---|---|
| notifyExecutor | 4/8/200 | CallerRuns（保数据） | 审计异步落库、通知 |
| exportExecutor | 2/4/50 | Abort（防 OOM，任务过多报错） | CSV 导出 |
| compensationExecutor | 2/4/100 | CallerRuns | 补偿修复 |

RocketMQ 消费线程 5-20/组；Tomcat 默认 200。

## 8. JVM 参数

- 本地开发建议：`-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -Xlog:gc*:file=logs/gc.log`
- Docker：`JAVA_TOOL_OPTIONS=-Xms512m -Xmx1g -XX:+UseG1GC`
- 生产建议模板 + 调优对比模板：`backend/jvm-lab/docs/07_args.md`

## 9. Docker 服务信息（deploy/docker-compose.yml）

mysql:8.0.34（健康检查+数据卷） / redis:6.2.6（AOF+密码） / rocketmq-namesrv:4.9.4 / rocketmq-broker:4.9.4（brokerIP1=服务名，autoCreateTopic） / backend（Dockerfile 多阶段构建，health=actuator） / frontend（nginx:1.25 + /api 反代）。网络 campusflow-net；.env.example 提供全部变量。

## 10. 启动方式

- **本地（验证过的路径）**：MySQL 本机 + VM 中间件 → `java -jar backend/campusflow-server/target/campusflow-server.jar --spring.profiles.active=local` + `cd frontend && npm run dev` → http://localhost:5173
- **Docker**：`cp .env.example .env && deploy/scripts/start.sh`（见下文"未验证"说明）
- 接口文档：http://localhost:8080/doc.html

## 11. 测试账号

admin/admin123456（管理员）、organizer1/org123456、student01~20/stu123456、jmeter0001~1000/Jm123456。

## 12. JMeter 执行方式

见 `docs/performance/LOAD_TEST_REPORT.md` 第七节（含 prepare 脚本、三个场景完整命令、业务四断言核对 SQL）。

## 13. 真实测试结果汇总

| 验证项 | 结果 | 证据 |
|---|---|---|
| Maven 全模块构建 | ✅ BUILD SUCCESS | 打包产物 131MB fat jar |
| 前端类型检查 + 生产构建 | ✅ 通过 | dist/ 产物 |
| 应用启动（local） | ✅ Started in ~19s，0 ERROR | app-startup.log |
| Flyway 建表 + 种子 | ✅ 2 migrations applied；1022 用户 + 5 活动 | 应用日志 |
| Redis 真实连接（VM） | ✅ AUTH/PING 通过 | 环境核查记录 |
| RocketMQ 真实收发（VM） | ✅ 消息 SENT→CONFIRMED 闭环 | message_deliver 表 |
| 端到端 API | ✅ 25/25 | scripts/e2e_api_test.py 输出 |
| 集成测试（真实中间件） | ✅ 10/10 | surefire-reports |
| 分片测试 | ✅ 3/3 | surefire-reports |
| JUC/JVM 实验模块 | ✅ 38/38 + 30/30 | 各模块 surefire |
| 浏览器冒烟（三端） | ✅ 截图验证 | 对话内截图 |
| 候补晋级全链路 E2E | ✅ 容量1→报满→候补→取消→自动晋级→确认转正（修复 waitlist_serve.lua 返回类型后实测） | e2e 脚本输出 |
| JMeter 压测 A/B/F | ✅ 0 错误 + 不超卖四断言 | .jtl + DB/Redis 核对 + 报告 |

## 14. 未通过项目及原因（如实记录）

- **无未通过的测试**。所有写出的测试均已跑通。

## 15. 尚未完成 / 未验证的部分（含原因与影响）

| 项 | 状态 | 原因 | 影响与替代 |
|---|---|---|---|
| Docker Compose 容器化启动实测 | ❌ 未验证 | 本机未安装 Docker（环境核查确认），且总控要求不得擅自安装大型软件 | compose/Dockerfile/nginx/脚本均已交付并按官方镜像语法编写；本地以"真实二进制"路径完整验证（MySQL/Redis/RocketMQ/应用/前端全链路） |
| JMeter 场景 C/D/E | ➖ 未用 JMeter 执行 | 正确性竞态需要精确同点并发，HTTP 注入难以对齐 | 已用集成测试精确验证（CancelConcurrencyIT/CompensationRecoveryIT/CheckinConcurrencyIT），见测试报告 |
| 优化前后对比基线 | ❌ 未执行 | 首轮压测即为架构后版本；"未优化版本"需另实现一条纯 DB 扣减链路 | 报告中明确标注未虚构提升百分比，并给出对比路线 |
| 死信队列 Broker 级验证 | ➖ 部分 | 消费端在重试上限时主动转补偿任务并确认消费（业务级死信处理），未让消息自然进入 %DLQ% | 补偿任务表 + 管理端死信重入队已实现并测试 |
| 双实例部署验证 | ❌ 未执行 | 单机资源限制 | 广播消费/分布式锁/补偿防重入代码均按多实例设计并注释 |

## 16. 学习资料位置

`docs/learning/`：00~16 共 17 篇中文讲义（含真实源码路径/mermaid 图/并发推演/面试题）+ `docs/learning/daily/day01~day30.md`（30 天任务）+ 两个实验模块自带讲义（backend/concurrency-lab/docs、backend/jvm-lab/docs）。

## 17. 后续可独立完善的技术方向

1. 引入 Redis Cluster 后 Lua 脚本 Key 同槽改造（hash tag）；
2. 本地消息表 ↔ RocketMQ 事务消息双方案对比实验；
3. 双实例部署验证广播失效与锁防重入；
4. 纯 DB 扣减对照版实现，补全优化前后对比；
5. 前端接入摄像头扫码（jsQR）完成真机扫码闭环；
6. SkyWalking/OpenTelemetry 分布式追踪接入；
7. 读写分离（ShardingSphere master-slave）与报表异构统计表。

---

*本报告由开发过程真实记录整理：环境核查记录、应用启动日志、surefire 测试报告、JMeter .jtl、数据库/Redis 现场核对均为可复查证据。*
