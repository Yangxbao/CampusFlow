# Day 07 · 阶段复盘一：Spring 与工程规范收网

> 阶段二（04-07）第 4 天 + 第一个复盘日。今天不学新知识，
> 做三件事：**自测、补漏、建立错题本**。

## 一、今天学习什么

复盘范围：day04（装配）+ day05（请求生命周期）+ day06（事务与 AOP）+ 配套讲义
`docs/learning/11_接口设计与企业工程规范.md` 全文。

## 二、阅读哪些项目文件

- 重读四类文件（今天用"讲出来"代替"看"）：
  1. `TraceIdFilter / JwtAuthInterceptor / GlobalExceptionHandler / JacksonConfig`（server/config 包）；
  2. `RegistrationTxService` 类注释（事务边界设计）；
  3. `DistributedLockAspect` 类注释（锁误删三防护）；
  4. `ThreadPoolConfig` 类注释（线程池治理三池）。

## 三、搞懂哪些八股（自测清单，逐题打分）

Spring 核心：
- [ ] Bean 生命周期 7 步 + initMethod/destroyMethod 实例；
- [ ] 三种装配形态 + CampusflowProperties 绑定；
- [ ] @Transactional 失效 6 场景 + 项目对策；
- [ ] AOP 两种代理（JDK 接口代理 / CGLIB 子类代理）与自调用失效根因。

工程规范（讲义 11）：
- [ ] ApiResult 四字段、ErrorCode 四分段、关键业务码（23001/23002/23003/42901）；
- [ ] DTO/VO/DO 三层职责与转换位置（Service 层 toVO）；
- [ ] TraceId 链路（Filter→MDC→logback %X→响应头→ApiResult→grep）；
- [ ] Long→String 的原理（2^53-1）与三种解法对比。

## 四、运行哪些命令（复习性质）

```bash
# 五条"每日检索"命令——以后每天开工先跑
grep -c "Lua 报名预留" backend/logs/campusflow.log          # 报名量
grep -c "报名确认消费成功" backend/logs/campusflow.log       # 消费量
mysql -uroot -p campusflow -e "SELECT status,COUNT(*) FROM message_deliver GROUP BY status;"
mysql -uroot -p campusflow -e "SELECT COUNT(*) FROM compensation_task WHERE status=0;"  # 待处理补偿
tail -50 backend/logs/campusflow-error.log
```

## 五、完成什么实验

**实验：错题本建立（产出文件）**

在笔记（或 `docs/learning/my-notes.md`，个人笔记不入库）里建三栏表：
「日期 / 讲不清的知识点 / 回炉材料（讲义或源码位置）」。
今天至少填 3 条——凡是自测打分低于 8/10 的都算。

**口头测验（请同学或对着录音讲 10 分钟）**：
主题定为"CampusFlow 的请求生命周期与异常处理"，要求覆盖 Filter/Interceptor/异常器/TraceId/
Long 精度五个点，讲完回放自查逻辑断点。

## 六、面试自测（本阶段高频真题）

1. **你们项目怎么保证接口幂等的？（工程规范视角）**
   要点：三层幂等总述（Lua 前置/DB 约束/消费幂等表）+ 各自拦截的故障场景不同
   （快路径省 DB / 最终防线不依赖中间件 / Broker 重复投递）；
2. **为什么错误码要分段？**
   要点：排障路径分流 + 告警分级 + 模块扩段（26xxx 补偿），规范即成本；
3. **@DistributedLock 和 @Transactional 一起用注意什么？**
   要点：锁外事务内；leaseTime 必须 > P99 或用看门狗；解锁校验持有者；
4. **Knife4j 文档怎么维护的？生产要不要关？**
   要点：@Tag/@Operation 注解式维护（OpenApiConfig BearerAuth）；生产 deny 或关端点防信息泄漏；
5. **线程池为什么禁止 Executors？项目怎么建池？**
   要点：无界队列/无界线程两类风险；ThreadPoolConfig 三池七参数显式 + 拒绝策略按业务选
   （notify=CallerRuns 保数据 / export=Abort 保内存）。

## 七、自我检查标准

- [ ] 20 项自测清单 ≥ 18 项通过（不达标项进错题本并当天回炉）；
- [ ] 错题本文件建立且 ≥ 3 条；
- [ ] 10 分钟口头讲解完成（录音自查一次）；
- [ ] 五条"每日检索"命令已跑熟。

## 附一、阶段一+二知识地图（收网自检用）

```
请求生命周期: TraceIdFilter → JwtAuthInterceptor → Controller(@Valid)
            → Service(编排) → TxService(事务) → 响应(ApiResult+traceId)
            → GlobalExceptionHandler(兜底) → afterCompletion/finally 清理 ThreadLocal
装配要点:    消费者 Bean(initMethod=start) / Redisson 手动装配 / CampusflowProperties 绑定
AOP:        @DistributedLock(SpEL key+看门狗) / AuditLogAspect / @Transactional 集中边界
规范:       ApiResult/ErrorCode 分段 / DTO-VO-DO / Long→String / Knife4j
```

## 附二、本阶段高频追问的"标准答案骨架"

1. **"你的接口规范怎么设计的？"** → ApiResult 四字段 + 错误码四分段 + traceId 进响应体
   + 分页统一 PageResult——四个点各配一个代码位置；
2. **"为什么 23002 满员是正常响应？"** → HTTP 传输语义 vs 业务语义分离；前端引导候补；
   899 个 23002 支撑了"错误率 0%"的压测结论；
3. **"ThreadLocal 用在哪，怎么保证安全？"** → MDC（traceId）+ AuthContext（身份）；
   线程复用清理两处；引出 ThreadLocalMap 弱引用 key 的内存泄漏话题（day24 深挖）；
4. **"如果让你 review 同学的 PR，规范层面先看什么？"** → 错误码是否占用新段位、
   DTO 是否带校验注解、事务是否进了 TxService、日志是否带业务键——把规范变成 checklist
   是工程师的体现。

## 附三、错题回炉路线图

| 自测不通过的点 | 回炉材料 |
|---|---|
| Bean 生命周期顺序 | 讲义 11 + day04 附表 |
| 事务失效场景 | day06 附二实验 |
| 错误码分段 | `ErrorCode.java` 全文重读 |
| Long 精度 | `JacksonConfig.java` + 实验 2 |
| Filter/Interceptor 边界 | `TraceIdFilter`/`JwtAuthInterceptor` 注释对比 |

---

## 附三、面试加练（3 题）

**1. "你们项目的 code review 都看什么？"**
要点：规范类（错误码段位/DTO 校验/分层越界）、并发类（共享状态/ThreadLocal 清理/
事务与锁边界）、可靠性类（异常吞噬/消息发件箱是否同事务/幂等键是否唯一）——
把 review checklist 与讲义 11 的规范一一对应。

**2. 项目里的"技术债"你欠了哪些？（展示自省）**
要点：无分片键的统计查询（广播代价，留了异构改造方案）、本地消息表归档策略未实现、
导出文件本地存储（多实例需共享存储）、日志聚合缺失——**能主动列出债务的人
比声称"完美"的人可信**。

**3. 怎么向面试官介绍"工程规范"而不显得背书？**
要点：每个规范讲"它防的具体事故"：错误码防误告警、traceId 防排障大海捞针、
Long→String 防前端幽灵 404——事故驱动叙述。

**今日金句**：规范的价值在事故发生时才兑现——讲规范要讲它防的事故。

---

## 附四、复盘日的三个输出物（收网清单）

1. **错题本**：低于 8/10 的自测项进入表格（日期/知识点/回炉材料）；
2. **10 分钟录音**："请求生命周期与异常处理"完整讲一遍并回放自查；
3. **知识地图**：一页纸画出 day04-06 的装配/生命周期/AOP 三块及互相引用关系。

| 本阶段关联讲义 | 重点章节 |
|---|---|
| 02_SpringBoot与Spring核心原理 | 全篇（装配/AOP/事务） |
| 11_接口设计与企业工程规范 | 全篇（规范六件套） |

**容易说错的表述**：
- ✗ "规范是为了好看" → ✓ "每个规范都对应一类事故与排障成本"。

---

## 明日预告

Day 08 进入阶段三（MySQL 与缓存）：索引原理与 EXPLAIN 实战。
