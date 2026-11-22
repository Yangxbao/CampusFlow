# Day 29 · Docker 部署与 Linux 运维

> 阶段十一。主教材 `docs/learning/12_Docker部署与Linux实战.md`。
> 今天的产出：**compose 全套跑通 + 一次完整故障演练**。

## 一、今天学习什么

1. **配置环境变量化**：application-docker.yml 的服务名互访（mysql/redis/rocketmq-namesrv）、
   `${ENV:default}` 三层兜底、JWT secret 生产注入；
2. **compose 编排三件套**：depends_on + service_healthy（就绪依赖而非启动顺序）、
   healthcheck（mysqladmin ping / redis-cli ping / actuator health）、数据卷；
3. **Nginx 反代**：try_files 兜 history 路由、/api 反代、文档与 actuator 端点 deny；
4. **Linux 运维清单**：日志三文件（主/错误/gc）、grep traceId 排障、
   jvm-lab 三脚本在 Linux 的使用、e2e 验收脚本；
5. **多实例就绪盘点**：消费组自动均分 / Redisson 锁防任务重入 / 缓存广播 MQ
   （12 讲 Q7 的"自动生效 vs 人工介入"清单）。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-server/src/main/resources/application-docker.yml` | 服务名与环境变量 |
| `backend/campusflow-server/src/main/resources/application.yml` | actuator exposure（health/info/metrics/prometheus） |
| `backend/campusflow-server/src/main/resources/logback-spring.xml` | 三 appender 与滚动策略（100MB/30 天/5GB） |
| `scripts/e2e_api_test.py` | 部署后端到端验收用例清单 |
| `backend/jvm-lab/scripts/*.sh` | Linux 诊断三件套 |
| `docs/learning/12_Docker部署与Linux实战.md` 第四节 | compose 参考实现（今天的输入） |

## 三、运行哪些命令

```bash
# 1. 中间件先行
docker compose -f deploy/docker-compose.yml up -d mysql redis rocketmq-namesrv rocketmq-broker
docker compose -f deploy/docker-compose.yml ps          # healthcheck 状态

# 2. 应用
docker compose -f deploy/docker-compose.yml --profile app up -d campusflow-server
curl -s http://127.0.0.1:8080/actuator/health

# 3. 验收
python3 scripts/e2e_api_test.py

# 4. 日志与诊断
docker logs campusflow-app --tail 100
docker exec campusflow-app sh -c "tail -50 /data/logs/campusflow-error.log"
```

## 四、完成什么实验

1. **compose 全链路**：按讲义 12 写出 compose + Dockerfile，跑通"中间件 healthy →
   应用 UP → e2e PASS"三段；Flyway 首启建表日志截图；
2. **故障演练**：`docker stop campusflow-mysql` → 观察 health DOWN + 应用日志报错 →
   恢复 → Hikari 池重建自愈；记录故障-发现-恢复时间线；
3. **Linux 诊断**：在 Linux（或 WSL）对运行中应用执行 jcmd_diagnose.sh 全套，
   产物归档 deploy/scripts/；
4. **多实例纸面验证**（有条件实跑更好）：两实例 + Nginx upstream，
   观察定时任务只有一个实例在跑（锁生效）。

## 五、面试自测（口头回答，答案要点）

1. **讲讲你的部署架构与配置管理？**
   （compose 五服务编排；环境变量三层兜底（ENV:default）；profile 四形态；
   secret 不入库由部署系统注入；Flyway 首启建表；actuator 健康检查；e2e 验收。）
2. **depends_on 和 healthcheck 的区别？**
   （前者只管启动顺序；MySQL 容器起来≠可连接，service_healthy 等真正就绪；
   应用侧 healthcheck 用 actuator UP 判定——避免"半启动接流量"。）
3. **服务挂了怎么自愈与排障？**
   （healthcheck 状态 + 容器重启策略；排障 SOP：error 日志 → traceId grep →
   jcmd 诊断脚本；演练过 MySQL 停机自愈（Hikari 重建）。）
4. **两个实例会有什么新问题？**
   （自动解决：消费组均分/任务锁/Redis 原子；要处理：本地缓存广播已内置（CacheEvictConsumer）、
   日志聚合（ELK/Loki）、导出文件共享存储——12 讲 Q7 完整清单。）

## 六、自我检查标准

- [ ] compose 全套跑通（三段验收截图）；
- [ ] 故障演练时间线记录完成；
- [ ] Linux 诊断脚本实操过一次；
- [ ] 讲义 12 全部面试题过一遍；错题本更新。

## 附一、知识点详解

**1. compose 服务依赖的"三层就绪"模型**
进程启动（容器 running）→ 服务就绪（healthcheck 通过）→ 业务就绪（Flyway 迁移完成 +
MQ 连通）。depends_on 只覆盖第一层；service_healthy 覆盖第二层；第三层靠应用
重试与快速失败（Hikari 连接超时 10s 重试、MQ 断线重连）。**部署可靠性 =
编排正确 + 应用韧性缺一不可**。

**2. Nginx 配置与后端的"责任交接"**
- X-Real-IP/X-Forwarded-For：真实 IP 传递（审计/限流依赖）；
- exposedHeaders X-Trace-Id：CORS 下前端可读 traceId（WebMvcConfig 配合）；
- deny actuator/doc.html：文档与探活不暴露公网（Knife4j 生产关闭的替代实现）。
**反向代理是安全边界与语义边界的双重交接点**。

**3. 日志体系的"三层结构"回顾（logback-spring.xml）**
CONSOLE（容器 stdout，docker logs 查看）→ campusflow.log（主文件，滚动
100MB/30 天/5GB 上限）→ campusflow-error.log（LevelFilter 只收 ERROR）。
三层的消费者不同：开发/容器、运维 grep、值班告警——**日志配置是给"看日志的人"设计的**。

**4. e2e_api_test.py 的验收价值**
它验证的是"部署后的业务正确性"而非"进程存活"：报名→查询→取消→候补→签到全链路。
部署验收的三级火箭：health UP（活着）→ e2e PASS（对）→ 压测冒烟（快）。

## 附二、故障演练时间线记录

```
T0 docker stop mysql
T1 healthcheck 状态: unhealthy (___s 后)
T2 应用表现: 前端报错___ / 日志关键字___
T3 docker start mysql
T4 自愈: Hikari 重建耗时___s; e2e 重跑 PASS? Y/N
```

---

## 附三、面试加练（3 题）

**1. 镜像安全怎么保障？**
要点：官方基础镜像 + 版本锁定（mysql:8.0.34 非 latest）、镜像扫描
（trivy）、最小运行镜像（JRE 非 JDK）、非 root 运行用户——
**镜像即交付物，要像代码一样审查**。

**2. 滚动发布时怎么保证不断服务？**
要点：多实例 + Nginx 摘流量（健康检查通过才接入）；应用优雅停机
（destroyMethod shutdown 让消费者先退订，Hikari 归还连接）；数据库迁移
向前兼容（只加列不删列）。**发布是编排问题不是重启问题**。

**3. 配置中心（Nacos）和现在的环境变量方案怎么演进？**
要点：环境变量适合"少量部署级参数"；配置中心解决"运行时热更/多环境
集中管理/变更审计"——演进触发点是多环境多实例配置开始混乱时。
CampusflowProperties 集中绑定让切换成本很低（参数定位已收口）。

**今日金句**：部署的最高境界是"发布无感知，回滚有预案"。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| compose 编排 | 12_Docker 第 4 节（参考实现） |
| Nginx/运维 | 12_Docker 第 5-7 节 |

**容易说错的表述**：
- ✗ "depends_on 保证 MySQL 就绪" → ✓ "depends_on 只管启动顺序，就绪靠 service_healthy"；
- ✗ "健康检查是 ping" → ✓ "应用级健康检查是 GET /actuator/health 返回 UP"。

---

## 明日预告

Day 30 面试模拟串讲：36 题收网 + 三轮模拟。
