# CampusFlow 运维手册（本地环境）

## 一、拓扑

```
Windows 主机
 ├── MySQL 8.0.34（127.0.0.1:3306, root/1234）        [本机服务]
 ├── campusflow-server.jar（8080, local 档案）         [便携 JDK17]
 └── 前端 dev server（5173）或 Nginx 托管 dist/
VMware 虚拟机 CentOS7（192.168.100.128, NAT）
 ├── Redis 6.2.6（6379, 密码 123321）
 └── RocketMQ 4.9.4（NameServer 9876 / Broker 10911）
```

## 二、日常启停

```bash
# 启动（一键）
deploy/scripts/start-local.sh
# 或手动：
java -jar backend/campusflow-server/target/campusflow-server.jar --spring.profiles.active=local
cd frontend && npm run dev

# 停止：结束 java/node 进程；数据无需清理（幂等启动）
# 完全重置：
mysql -uroot -p1234 -e "DROP DATABASE campusflow;"   # 重启应用自动重建+播种
```

## 三、日志与排障

| 内容 | 位置 |
|---|---|
| 应用日志（含 traceId） | backend/campusflow-server/logs/campusflow.log |
| 错误独立日志 | logs/campusflow-error.log |
| 启动日志 | backend/campusflow-server/app-startup.log |
| 前端日志 | frontend/dev-server.log |
| 全链路排障 | 取响应头 X-Trace-Id → grep 日志 |

## 四、健康检查与中间件核对

```bash
curl http://localhost:8080/actuator/health
# Redis：
redis-cli -h 192.168.100.128 -a 123321 PING
# RocketMQ（连接即证明 NameServer 可达）：
curl http://localhost:8080/doc.html
# 消息闭环核对：
mysql -uroot -p1234 -e "SELECT status, COUNT(*) FROM campusflow.message_deliver GROUP BY status"
# status: 0待发 1已发 2消费确认 3失败（长期>0 的 3 需要排查）
```

## 五、补偿任务巡检（管理端 /admin/compensations）

正常情况 6 个任务均为空跑；出现待处理/死信任务时：
1. 查看任务 payload 与 last_error；
2. 修复底层原因（Redis/MQ/DB）；
3. 管理端"重新入队"触发重试。

## 六、常见问题

| 现象 | 处置 |
|---|---|
| 启动失败：MQ producer 连接超时 | 虚拟机未开机/NameServer 未运行（9876） |
| 启动失败：Flyway 校验失败 | DB 被手工改过表结构 → 回滚或重建库 |
| 报名返回 30002 | Redis 不可达（6379）或密码变更 |
| 消息 status 长期 0/3 | Broker 挂了；重投任务每 15s 自动恢复投递 |
| 前端 401 | token 过期（24h），重新登录 |
