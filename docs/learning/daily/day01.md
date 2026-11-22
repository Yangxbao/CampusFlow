# Day 01 · 项目全景与环境搭建

> 阶段一（01-03 环境与架构总览）第 1 天。
> 今天不写代码，目标只有一个：**让 CampusFlow 在你机器上完整跑起来**。

## 一、今天学习什么

1. 项目定位：高校校园活动报名平台——学生抢名额（热点写）、候补排队、电子签到；
   这是"高并发读 + 突发写"的典型业务，全项目技术选型都围绕它展开；
2. 技术栈总览：Spring Boot 3.2.5 + Java 17 + MyBatis-Plus 3.5.5 + MySQL 8 + Redis(Redisson 3.27)
   + RocketMQ 5.1.4 client + Knife4j + Vue3；
3. Maven 6 模块：campusflow-common / infrastructure / business / server（业务四件）+
   concurrency-lab / jvm-lab（实验模块）；
4. 本地拓扑：MySQL 本机 3306；Redis 6.2.6 与 RocketMQ 4.9.4 在虚拟机 192.168.100.128
   （见 `application-local.yml` 注释，全部环境变量可覆盖）。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `pom.xml`（根） | 6 模块聚合 + 版本管理（dependencies 中 shardingsphere 5.4.1、jjwt、knife4j） |
| `backend/campusflow-server/src/main/resources/application.yml` | 全局参数：限流 8 次/10s、预留 TTL 15min、MQ 重试、缓存 TTL、布隆参数 |
| `backend/campusflow-server/src/main/resources/application-local.yml` | 本地连接信息 + Hikari 池参数 |
| `backend/campusflow-server/src/main/resources/db/migration/V1__init_schema.sql` | 17 张表清单（先只看表名和注释） |
| `docs/performance/LOAD_TEST_REPORT.md` | 全项目"成果页"：场景 A 999 请求 0 错误 P99=5ms 等 |

## 三、搞懂哪些八股

- Spring Boot 约定优于配置：`application.yml` + `profiles` 的加载与覆盖顺序；
- 环境变量占位符 `${MYSQL_HOST:127.0.0.1}` 的默认值机制；
- Flyway 是什么：V1__init_schema.sql / V2__seed_base_data.sql 的版本化迁移；
- HikariCP 为什么是默认连接池（关键参数 maximum-pool-size=20）。

## 四、运行哪些命令

```bash
# 1. 编译整个项目
mvn clean package -DskipTests

# 2. 启动（先确保 MySQL 可连、Flyway 自动建表）
java -jar backend/campusflow-server/target/campusflow-server.jar --spring.profiles.active=local

# 3. 验证
curl http://127.0.0.1:8080/actuator/health           # 期望 {"status":"UP","groups":[...]}
curl -X POST http://127.0.0.1:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"student01","password":"stu123456"}'
# 期望: {"code":0,"message":"success","data":{"token":"..."},"traceId":"..."}
```

看到 `traceId` 字段——记住它，day05 会讲它怎么来的。

## 五、完成什么实验

1. Flyway 首启观察：日志中搜 `Flyway`，确认 V1/V2 依次执行，`SHOW TABLES` 数出 17 张表；
2. 打开 Knife4j 文档 `http://127.0.0.1:8080/doc.html`，点 Authorize 填 token 后调一个查询接口；
3. 前端跑通：`cd frontend && npm run dev`，用 student01 登录浏览活动列表。

## 六、面试自测（口头回答，答案要点在括号里）

1. **自我介绍里这个项目怎么一句话定位？**
   （高校活动报名平台；我负责的是高并发报名链路：Redis Lua 原子预留 + MQ 异步落库 + 补偿对账，
   压测 1000 用户抢 100 名额 0 超卖、P99 5ms。）
2. **项目分几个 Maven 模块，为什么这么拆？**
   （common 常量/工具、infrastructure 基础设施、business 业务域、server 装配；单向依赖，
   是"逻辑微服务化"，为未来拆分留边界——14 讲 Q22。）
3. **本地环境里中间件和应用是怎么解耦的？**
   （连接信息全部 `${ENV:default}` 环境变量化，profile 区分 local/test/docker 三形态，
   换环境不改代码。）
4. **Flyway 在项目里做了什么？**
   （V1 全量 17 张表 + V2 种子数据，`baseline-on-migrate` 兼容已有库；容器化时是"首启即就绪"的关键。）

## 七、自我检查标准

- [ ] `mvn package` 一次通过，能说出 6 个模块的职责；
- [ ] 应用启动成功，/actuator/health 返回 UP；
- [ ] 用 curl 登录成功且响应里有 traceId；
- [ ] 能不看书列出至少 10 张核心表名（user/activity/registration/waitlist/checkin_token/
      checkin_record/registration_reservation/registration_state_log/message_deliver/
      message_consume_log/compensation_task/stock_audit_log/...）；
- [ ] 压测报告的三个场景数字能复述（A: 999/0%/P99=5ms；B: 1/7/992；F: 1677/s）。

## 附一、知识点详解（今天的三块基石，展开读）

**1. 配置加载与覆盖顺序（面试常考）**
Spring Boot 配置优先级从高到低：命令行参数（--spring.profiles.active）> 操作系统环境变量
（MYSQL_HOST）> application-{profile}.yml > application.yml。所以 application.yml 里的
`${MYSQL_HOST:127.0.0.1}` 是"三层兜底"：环境变量有值用环境变量，没有用冒号后的默认值。
验证方法：`MYSQL_DB=campusflow_x java -jar app.jar` 启动后看日志里连接的库名。

**2. 六个 Maven 模块的职责边界（背诵版）**
- campusflow-common：零依赖——常量（RedisKeyConstants/MqConstants）、错误码、枚举、
  工具（SnowflakeIdWorker/TraceUtils/JsonUtils）、ApiResult/PageResult；
- campusflow-infrastructure：中间件适配——Redis（Stock/MultiLevel/Bloom/RateLimiter）、
  锁（Redisson 三件套）、MQ（本地消息表/消费幂等/工厂）、线程池、Redisson/Redis 配置；
- campusflow-business：业务域——registration/waitlist/checkin/activity/user/notify/
  compensation/audit/stats 九个域，每个域 controller/dto/entity/mapper/service 五件套；
- campusflow-server：装配——启动类、全局配置（异常/拦截器/序列化/文档）；
- concurrency-lab / jvm-lab：纯 JDK 实验模块，仅 lombok+JUnit，与业务零耦合。

**3. 压测报告怎么"读三遍"**
第一遍读结论表（三个场景的关键数字）；第二遍读业务核对（四断言——这才是可信度来源）；
第三遍读诚实声明（第六节未跑场景的替代验证 + 第八节不虚构优化百分比）。
把"四断言"抄在笔记首页：COUNT=名额 / COUNT=COUNT(DISTINCT user_id) /
Redis 库存=0 不为负 / confirmed_count 与 DB 一致。

## 附二、复盘记录模板（每天填，30 天后就是你的项目手册）

```
日期: Day01
今天最大的收获: （一句话）
没搞懂的点: （列出来，明天优先解决）
跑通的命令/实验: （清单）
错题本新增: （0-N 条）
```

---

## 附三、面试加练（3 题，口头作答）

**1. 为什么选 Java 17 而不是 8/11？**
要点：LTS 版本；record/switch 模式（RegistrationService 的 switch 表达式真实用到）；
ZGC/G1 成熟；JDK17 是 Spring Boot 3 的最低要求——选型服务于技术栈代际。

**2. 多模块项目的编译与发布怎么组织？**
要点：根 pom 聚合 + dependencyManagement 统一版本；`mvn -pl backend/xxx -am`
只构建目标模块及依赖（日常调试常用）；server 模块产出 fat-jar 作为唯一部署物。

**3. 如果你接手一个没有压测报告的项目，第一步做什么？**
要点：先建立基线（冒烟压测）→ 定义业务正确性断言 → 再谈优化；对照本项目
LOAD_TEST_REPORT 的结构（场景/负载模型/业务核对/复现方式）输出同款报告。

**今日金句**：环境可复现（Compose/环境变量/Flyway）是"工程可信"的第一印象。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 环境与配置 | 12_Docker部署 第 2-3 节；02_SpringBoot 配置章节 |
| 模块划分 | 01_项目业务与整体架构 |
| 压测报告 | 13_JMeter 第 1 节 |

**容易说错的表述**：
- ✗ "报名单接口是异步的" → ✓ "报名接口是异步受理、异步确认"（受理是同步返回的）；
- ✗ "Redis 存库存" → ✓ "Redis Lua 原子预留库存，DB 异步确认"（细节决定可信度）；
- ✗ "大概一千多用户" → ✓ "999 请求 0 错误，P99=5ms"（数字要精确到报告口径）。

---

## 明日预告

Day 02 架构总览：把"一张报名请求的完整旅程"画出来。
