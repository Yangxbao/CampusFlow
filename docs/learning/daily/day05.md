# Day 05 · Web 层规范：请求生命周期

> 阶段二第 2 天。今天的目标：**不看代码，在白板上讲清一个请求从 Tomcat 到响应的完整路径**。

## 一、今天学习什么

请求生命周期（按执行顺序）：

```
1. TraceIdFilter (Filter, @Order(1))   读/生成 X-Trace-Id → MDC → 响应头回传
2. JwtAuthInterceptor.preHandle        解析 Bearer → UserPrincipal → AuthContext(ThreadLocal)
                                       + 角色 URI 前缀校验(/admin, /organizer)
3. Controller                          @Valid 触发 DTO 校验
4. Service                             业务逻辑；BizException 随手抛
5. GlobalExceptionHandler              BizException→业务码；校验失败→40001；兜底→50000
6. Interceptor.afterCompletion         AuthContext.clear()（线程复用清理）
7. Filter finally                      MDC.remove()
```

配套规范：ApiResult{code,message,data,traceId}；ErrorCode 分段 0/2xxxx/3xxxx/4xxxx。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-server/src/main/java/com/campusflow/server/config/TraceIdFilter.java` | 全文 40 行，逐行读（含 finally 清理注释） |
| `backend/campusflow-server/src/main/java/com/campusflow/server/config/JwtAuthInterceptor.java` | preHandle/afterCompletion 成对操作 |
| `backend/campusflow-server/src/main/java/com/campusflow/server/config/GlobalExceptionHandler.java` | 六类 handler 一张表记住 |
| `backend/campusflow-server/src/main/java/com/campusflow/server/config/WebMvcConfig.java` | PUBLIC_PATHS 白名单与 CORS exposeHeaders |
| `backend/campusflow-common/src/main/java/com/campusflow/common/error/ErrorCode.java` | 分段规则与关键码（23002/23003/42901/40101） |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/user/dto/RegisterRequest.java` | 校验注解示范（@NotBlank/@Size/@Pattern） |

## 三、搞懂哪些八股

- Filter vs Interceptor：规范层（Servlet）vs Spring MVC；生效范围（全部请求 vs handler 方法）；
  项目分工的注释原话："TraceId 属于协议层关注点用 Filter；鉴权属于 MVC 层关注点用 HandlerInterceptor"；
- ThreadLocal 与线程池：Tomcat 线程复用 → 不清理会串号（下一个请求读到上一个用户身份）——
  这是项目两处 finally/afterCompletion 清理的原因；
- 统一异常处理的实现机制：@RestControllerAdvice + @ExceptionHandler 的优先级匹配
  （精确类型优先于 Exception 兜底）；
- 401/403 的语义区分：UNAUTHORIZED(40101) 未登录/token 失效；FORBIDDEN(40301) 已登录无权限。

## 四、运行哪些命令

```bash
# 1. 响应头观察
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"username":"student01","password":"stu123456"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

curl -i http://127.0.0.1:8080/api/v1/my/registrations -H "Authorization: Bearer $TOKEN"
# 观察: X-Trace-Id 响应头 + 响应体 traceId 一致

# 2. 鉴权失败与越权
curl -s http://127.0.0.1:8080/api/v1/my/registrations | python -m json.tool    # 40101
curl -s http://127.0.0.1:8080/api/v1/admin/dashboard -H "Authorization: Bearer $TOKEN"  # 40301（学生访问）

# 3. TraceId 排障链
grep "刚刚的traceId" backend/logs/campusflow.log
```

## 五、完成什么实验

1. **TraceId 全链路**：发起一次报名请求，从响应体拿到 traceId，用 grep 在日志里找出
   "Lua 报名预留 code=" 与 "预留落库完成" 两行——体会"一条 traceId 串全链路"；
2. **异常分级观察**：分别触发（a）参数错（注册用户名太短）→ 40001；（b）业务错（重复报名）
   → 23001 + log.warn；（c）系统错（临时把 Redis 地址改错并报名）→ 50000 + log.error 完整堆栈，
   且响应体无堆栈细节。三种记录归档；
3. **错误日志分离验证**：确认 `logs/campusflow-error.log` 只含（c）类记录（LevelFilter 只收 ERROR）。

## 六、面试自测（口头回答）

1. **说一遍请求生命周期**（按上文 7 步，必须含两处 ThreadLocal 清理）；
2. **为什么 500 只返回"系统繁忙"？**
   （防堆栈/SQL 泄漏；服务端 log.error 带 traceId 留全量堆栈，用户拿 traceId 报障即可——
   GlobalExceptionHandler.handleUnknown。）
3. **JWT 无状态意味着什么？**
   （服务端不存会话，水平扩展免共享存储；代价是签发后无法主动作废，靠 24h 过期兜底——
   生产要环境变量注入 secret。）
4. **校验注解挂哪层？触发后返回什么？**
   （DTO 层 + @Valid；MethodArgumentNotValidException 被 GlobalExceptionHandler 转
   400 + 40001 + "字段: message" 拼接。）

## 七、自我检查标准

- [ ] 白板（或纸上）画出 7 步请求生命周期，两处清理标红；
- [ ] 三类异常实验完成且现象与预期一致；
- [ ] 能背出 ApiResult 四字段与 ErrorCode 四分段；
- [ ] 完成一次"用 traceId 排障"的完整演示（30 秒内定位到日志行）。

## 附一、知识点详解

**1. 一行日志的解剖学（今天学完应该能读懂任何一行）**
```
2026-11-21 02:11:05.123 [http-nio-8080-exec-4] INFO  [a3f8c2d1e4b5...] c.c.b.r.s.RegistrationService - 报名预留链路完成 ...
```
对应 logback pattern：`%d{...} [%thread] %-5level [%X{traceId}] %logger{40} - %msg%n`。
其中 `[...]` 里是 MDC 的 traceId——**线程名 + traceId 两个维度**让并发日志可读。

**2. 40101 vs 40301 vs 40001 的触发路径**
- 40001：DTO 校验失败（@Valid 触发 MethodArgumentNotValidException）或参数缺失；
- 40101：无 Authorization 头或 token 解析失败（JwtAuthInterceptor.preHandle 抛出）；
- 40301：token 有效但角色不足（学生访问 /api/v1/admin/**）。
区分的价值：前端可编程处理（40001 提示表单、40101 跳登录、40301 提示无权限）。

**3. PublicPath 白名单的设计逻辑**
放行三类：鉴权入口本身（login/register）、文档（doc.html/api-docs/webjars）、
探活（actuator/error）。**安全边界原则**：白名单最小化，新增公开接口必须显式加白
并说明理由——而不是"默认全放行再收紧"。

**4. 为什么 traceId 要 32 位无连字符**
UUID 去连字符后仍是 128bit 唯一性，但日志 grep 时无需转义、复制不易出错、
与响应头/前端展示更友好。这种小细节答出来就是"真的写过日志系统"。

## 附二、三类异常实验的预期结果表

| 触发方式 | HTTP | code | 日志级别 | 落点 |
|---|---|---|---|---|
| 注册用户名 2 位 | 400 | 40001 | 无（校验层直接抛） | - |
| 重复报名 | 200 | 23001 | WARN | 主日志 |
| Redis 地址写错后报名 | 500 | 50000 | ERROR + 堆栈 | error 日志 |

---

## 附三、面试加练（3 题）

**1. OncePerRequestFilter 解决什么问题？**
要点：请求转发/ include 场景 Filter 可能被多次调用；OncePerRequestFilter 保证
每请求一次——TraceId 生成必须用它，否则转发后换新 ID 导致链路断裂。

**2. 异步 Servlet（DeferredResult）下 ThreadLocal 会怎样？**
要点：请求线程与业务线程不是同一个——MDC/AuthContext 在异步回调里会丢；
解法：提交任务前捕获快照（TaskDecorator/MDC 拷贝）。本项目消费端 MQ 场景
用"消息属性携带 traceId"绕开了这个问题。

**3. CORS 预检请求是什么？为什么 traceId 要进 exposedHeaders？**
要点：带自定义头的请求先发 OPTIONS 预检；默认 JS 只能读简单响应头，
exposedHeaders=X-Trace-Id 让前端 JS 能读取它做报障展示。

**今日金句**：异常处理的水平不在于 catch 多少，而在于"用户看到什么、日志留下什么"的分层是否清晰。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 请求生命周期 | 11_接口设计与企业工程规范 第 5-6 节 |
| TraceId | 11_接口设计 第 5 节（sequenceDiagram） |
| 统一响应 | 11_接口设计 第 2 节 |

**容易说错的表述**：
- ✗ "异常统一返回 500" → ✓ "业务异常返回业务码 HTTP 200，系统异常才 500"；
- ✗ "traceId 是日志框架自动生成的" → ✓ "TraceIdFilter 生成写 MDC，logback %X{traceId} 输出"。

---

## 明日预告

Day 06 事务边界与 AOP：@Transactional 失效全景 + @DistributedLock 切面实战。
