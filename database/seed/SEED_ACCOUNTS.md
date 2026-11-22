# CampusFlow 种子数据说明

> 种子由应用启动时 `SeedDataRunner`（campusflow-business/com/campusflow/biz/seed/）幂等创建：
> 仅当用户表为空时执行；关闭方式 `campusflow.seed.enabled=false`（测试环境默认关闭）。

## 测试账号

| 角色 | 账号 | 密码 | 数量 |
|---|---|---|---|
| 管理员 | admin | admin123456 | 1 |
| 组织者 | organizer1 | org123456 | 1 |
| 学生 | student01 ~ student20 | stu123456 | 20 |
| 压测用户池 | jmeter0001 ~ jmeter1000 | Jm123456 | 1000 |

> 压测账号共用同一 BCrypt 密文（同明文可校验），避免 1000 次慢哈希拖慢启动。

## 示例活动（5 个）

| 活动 | 状态 | 容量 | 用途 |
|---|---|---|---|
| 2026 年校园人工智能前沿讲座 | 已发布 | 100 | 报名/压测目标 |
| Python 爬虫实战工作坊 | 进行中 | 50 | 签到演示 |
| 春季志愿服务动员大会 | 待审核 | 200 | 审核流程演示 |
| 创新创业大赛宣讲会 | 草稿 | 300 | 编辑/提交流程演示 |
| 就业指导：简历门诊 | 已结束 | 80 | 统计演示（预置 20 条报名，18 条已签到） |

## 运行时初始化动作

1. Flyway：V1 建表（17 张）+ V2 角色字典（STUDENT/ORGANIZER/ADMIN）。
2. SeedDataRunner：账号 + 活动 + 历史报名 + 布隆过滤器装载 + 已发布/进行中活动库存预热。
3. 重置方式：`DROP DATABASE campusflow;` 后重启应用（全部幂等重建）。

## 与 Docker 的关系

docker-compose 中 MySQL 容器以 `MYSQL_DATABASE=campusflow` 初始化库；
表结构由应用启动时 Flyway 创建（挂载 SQL 到 /docker-entrypoint-initdb.d 非必需）。
