# CampusFlow 项目状态清单

> 每一项标注：状态 / 验证方式 / 测试证据 / 遗留问题
> 图例：✅ 完成+真实验证 | 🟡 完成（部分验证或存在已知限制）| ❌ 未完成

## A. 工程构建

| 项 | 状态 | 验证方式 | 证据 |
|---|---|---|---|
| 后端 Maven 全模块构建 | ✅ | mvn package | fat jar 131MB 生成 |
| 前端依赖安装 | ✅ | npm install | node_modules 就绪 |
| 前端类型检查 | ✅ | vue-tsc --noEmit | 0 错误 |
| 前端生产构建 | ✅ | npm run build | dist/ 产物 |
| 全部测试执行 | ✅ | mvn test | 89 个用例全过（8+38+30+10+3） |

## B. 真实中间件

| 项 | 状态 | 证据 |
|---|---|---|
| MySQL 连接（本机 3306） | ✅ | 登录/报名全链路真实读写 |
| Flyway 建表 | ✅ | "Successfully applied 2 migrations" |
| Redis 连接（VM 192.168.100.128:6379，密码 123321） | ✅ | AUTH/PING + Lua 执行（报名/取消/候补全链路） |
| Redisson 锁获取与释放 | ✅ | 审核/晋级/补偿任务真实执行日志 |
| RocketMQ NameServer/Broker 连接（VM 9876/10911） | ✅ | 消息 SENT + 消费 CONFIRMED 闭环 |
| 生产消息成功 | ✅ | message_deliver status=1/2 记录 |
| 消费消息成功 | ✅ | 消费者日志 + 业务落库 |
| DB 持久化业务记录 | ✅ | registration/notify/audit 等表真实数据 |

## C. 业务流程（端到端 25/25 + 浏览器三端冒烟）

| 流程 | 状态 | 备注 |
|---|---|---|
| 学生注册/登录 | ✅ | JWT + BCrypt |
| 组织者创建活动 | ✅ | 工作台全生命周期 |
| 活动审核发布 | ✅ | 管理员通过 → 分布式锁内库存预热 |
| 学生报名 | ✅ | Lua 原子预留 + MQ 异步确认 |
| 超额报名拦截 | ✅ | 23002 + 前端引导候补 |
| 取消报名 | ✅ | 幂等 + 触发候补晋级 + 释放审计 |
| 候补晋级 | ✅ | ZSet 出队 + 30min 保留期 + 超时释放 |
| 二维码签到 | ✅ | 凭证签发 + 核销（并发安全） |
| 活动统计 | ✅ | 指标卡 + ECharts 趋势 |
| 管理员管理 | ✅ | 用户/审核/审计/消息/补偿可视化 |
| 活动状态自动流转 | ✅ | 定时任务 PUBLISHED→ONGOING→FINISHED |

## D. 并发与可靠性（集成测试 + 压测实证）

| 项 | 状态 | 验证 |
|---|---|---|
| 库存不超卖 | ✅ | 压测 A：DB 恰好 100；集成测试 LuaOversellIT |
| 一人一单 | ✅ | 压测 B：1000 次并发重复点击仅 1 条；唯一约束直插拦截 |
| 消息重复消费安全 | ✅ | IdempotencyIT（真实 MQ 重复投递） |
| 消息异常重试 | ✅ | 本地消息表指数退避重投 + 消费端 RECONSUME_LATER |
| 死信处理 | 🟡 | 业务级：重试上限转补偿任务（管理端可视化+重入队）；Broker %DLQ% 未实测 |
| 取消不重复恢复库存 | ✅ | CancelConcurrencyIT：审计 1 条、库存精确恢复 |
| Redis/DB 最终一致 | ✅ | 库存对账任务漂移修复实测（CompensationRecoveryIT） |
| 补偿任务可重复执行 | ✅ | 全部操作幂等（状态 CAS/Lua 幂等/审计 bizKey 唯一） |
| 并发签到不重复成功 | ✅ | CheckinConcurrencyIT：10 并发 1 成功 |
| 应用异常退出恢复 | ✅ | 预留记录 PENDING_CONFIRM 恢复重发实测；重启后 Broker 重投消息被安全处理（现场发生并记录） |

## E. 完整测试交付

| 产物 | 状态 | 位置 |
|---|---|---|
| 单元测试报告 | ✅ | docs/testing/TEST_REPORT.md + surefire-reports |
| 集成测试报告 | ✅ | 同上 |
| 端到端报告 | ✅ | scripts/e2e_api_test.py（25 断言） |
| JMeter 测试文件 | ✅ | load-test/jmeter/（3 个 .jmx） |
| 真实压测报告 | ✅ | docs/performance/LOAD_TEST_REPORT.md + .jtl + HTML |
| 异常恢复测试报告 | ✅ | docs/testing/TEST_REPORT.md 第二节 |
| 已知限制清单 | ✅ | 本文档"遗留与限制" |

## F. 学习资料

| 项 | 状态 | 位置 |
|---|---|---|
| 学习地图与索引 | ✅ | docs/learning/00_学习总路线与资料索引.md |
| 30 天学习任务 | ✅ | docs/learning/daily/day01~30.md |
| 技术讲义 00-08 | ✅ | docs/learning/（结合真实源码路径） |
| 技术讲义 09-16 | ✅ | docs/learning/（分片/压测含真实数据） |
| JUC 实验模块 | ✅ | backend/concurrency-lab（10 实验+讲义+38 测试） |
| JVM 实验模块 | ✅ | backend/jvm-lab（11 实验+讲义+30 测试+诊断脚本） |
| 面试题与参考答案 | ✅ | docs/learning/14_项目面试题与参考回答.md |
| 源码阅读路线 | ✅ | docs/learning/15_项目源码阅读路线.md |

## 遗留与限制（如实）

1. **Docker Compose 未实测启动**：本机无 Docker（环境核查确认；总控禁止擅自安装大型软件）。交付文件按官方镜像语法编写；本地以真实二进制完整验证了同等部署拓扑（MySQL/Redis/MQ/后端/前端）。
2. **优化前后对比未执行**：首轮压测即当前架构版本；报告明确标注并给出对比路线，未虚构提升数字。
3. **Broker 级死信未实测**：消费端在重试上限时转业务补偿（等价闭环并可视化管理），消息未自然落入 %DLQ%。
4. **VM 依赖说明**：本地模式复用用户 VMware 虚拟机中的 Redis/RocketMQ（192.168.100.128），虚拟机需开机；Docker 模式无此依赖。
5. **压测并发规模**：200 并发线程（本机资源），1000 真实用户分批完成；未伪造 1000 线程同压数据。
6. **多实例部署**：代码按多实例设计（广播失效/锁防重入/group-suffix），单机未实测双实例。
