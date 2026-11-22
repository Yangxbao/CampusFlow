# Day 04 · Spring Boot 启动与装配

> 阶段二（04-07 Spring 核心与工程规范）第 1 天。
> 今天只回答一个问题：**应用启动时，那些 Bean 是怎么被创建和接线的？**

## 一、今天学习什么

1. 启动类 `CampusflowApplication` 的 @SpringBootApplication 三合一（@Configuration +
   @EnableAutoConfiguration + @ComponentScan）；
2. 项目的三种装配形态：
   - @Component/@Service/@Aspect 扫描装配（业务层绝大多数 Bean）；
   - @Configuration + @Bean 手动装配（RedissonConfig/ThreadPoolConfig/RedisConfig/
     MqProducerConfig/消费者 Bean）；
   - @ConfigurationProperties 属性装配（CampusflowProperties 映射 application.yml 的 campusflow.*）；
3. 生命周期钩子：`@Bean(initMethod = "start", destroyMethod = "shutdown")` 在
   RegistrationConfirmConsumer 等消费者 Bean 上的用法——**RocketMQ 消费者随容器启停**；
4. 条件化 profile：local/test/docker/sharding 四套配置的装配差异。

## 二、阅读哪些项目文件

| 文件 | 看什么 |
|---|---|
| `backend/campusflow-server/src/main/java/com/campusflow/server/CampusflowApplication.java` | 启动类注释（fat-jar 嵌套扫描与 allow-bean-definition-overriding 的坑） |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/config/RedissonConfig.java` | 手动装配 Redisson；@Value 注入 spring.data.redis.* |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/config/ThreadPoolConfig.java` | 三个线程池 Bean + destroyMethod 优雅关闭 |
| `backend/campusflow-infrastructure/src/main/java/com/campusflow/infra/property/CampusflowProperties.java` | 属性映射：mq.groupSuffix、reservation、cache 各段 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/registration/listener/RegistrationConfirmConsumer.java` | @Bean(initMethod/destroyMethod) 的实际用法 |
| `backend/campusflow-business/src/main/java/com/campusflow/biz/config/WaitlistWiringConfig.java` | 函数式注入解循环依赖（@Autowired LongConsumer） |

## 三、搞懂哪些八股

- Bean 生命周期：实例化 → 属性填充 → Aware → BeanPostProcessor 前置 → init（@PostConstruct/
  initMethod）→ 就绪 → destroy（destroyMethod/@PreDestroy）；项目消费者用 initMethod=start
  挂钩的原因（订阅必须在启动时完成）；
- @ConfigurationProperties vs @Value：前者集中（CampusflowProperties 一次映射一段配置），
  后者零散（RedissonConfig 拿 host/port）；
- 循环依赖的三种解法：构造器注入会失败 → setter/@Lazy/函数式注入（项目用 LongConsumer 注入）；
- @Order 与 Filter 链顺序（TraceIdFilter @Order(1) 必须在鉴权 Filter 之前）。

## 四、运行哪些命令

```bash
# 观察容器里都有什么 Bean
java -jar backend/campusflow-server/target/campusflow-server.jar \
  --spring.profiles.active=local --debug 2>&1 | grep -i "bean '" | head -30

# 启动日志里的关键 Bean 创建（搜名字）
# 期望看到: redissonClient / notifyExecutor / exportExecutor / compensationExecutor /
# registrationConfirmMessageConsumer / cacheEvictMessageConsumer / apiDoc...
```

## 五、完成什么实验

1. **消费者启停实验**：启动应用后 Ctrl+C 优雅停机，观察日志中
   `registrationConfirmMessageConsumer` 的 shutdown 被调用（destroyMethod 生效）；
2. **属性绑定验证**：把 `campusflow.signup.rate-limit-max` 临时改成 2，重启后连报 3 次报名，
   第 3 次被限流（code=23003），验证 CampusflowProperties 绑定生效；改回 8；
3. **手动装配理解**：在 RedissonConfig 里临时改错 redis 密码，观察启动失败位置与异常链
   （Bean 创建失败 → 应用退出），改回。

## 六、面试自测（口头回答）

1. **你的 Redisson 是怎么装配的？为什么不用 starter？**
   （RedissonConfig 手动 new RedissonClient，@Value 取 spring.data.redis 配置；
   类注释三理由：保留 Letture 作缓存主通道、Redisson 专用于锁/布隆/看门狗、
   destroyMethod 管生命周期防连接泄漏。）
2. **RocketMQ 消费者为什么用 @Bean(initMethod="start")？**
   （start 触发订阅与重平衡；destroyMethod="shutdown" 保证容器关闭时优雅退出，
   否则 stop 时消费组可能残留。）
3. **项目里循环依赖怎么解的？**
   （RegistrationService 需要 WaitlistService 的晋级入口但 WaitlistService 又依赖它——
   用 @Autowired(required=false) LongConsumer 函数注入 + WaitlistWiringConfig 装配。）
4. **@ConfigurationProperties 绑定失败会怎样？**
   （绑定错误在启动期抛 ConfigurationPropertiesBindException 快速失败——配置错误不进运行期。）

## 七、自我检查标准

- [ ] 能画出 Bean 生命周期 7 步并对应到项目实例（initMethod=start）；
- [ ] 实验 2 的限流绑定验证通过；
- [ ] 说出项目三种装配形态各举一例；
- [ ] 理解 allow-bean-definition-overriding 注释写的 fat-jar 双资源扫描坑。

## 附一、知识点详解

**1. 消费者 Bean 的生命周期设计（initMethod 的深意）**
`@Bean(initMethod = "start", destroyMethod = "shutdown")` 把 RocketMQ 消费者的
启动/关闭绑定到 Spring 容器：start 在依赖注入完成后触发（此时 nameServer 配置、
TxService 等依赖已就绪）；shutdown 在容器关闭时执行，防止停机时消费组残留导致
Broker 端消息堆积到失联组。**这是"框架资源随容器生命周期"的标准范式**，
RedissonClient（destroyMethod="shutdown"）同理。

**2. CampusflowProperties 的参数分组与业务含义（映射表）**

| 配置段 | 关键参数 | 业务含义 |
|---|---|---|
| reservation | reserve-ttl-minutes=15 | Redis 预留的 TTL（安全阀） |
| reservation | waitlist-confirm-minutes=30 | 候补晋级确认窗口 |
| signup | rate-limit-window-seconds / rate-limit-max = 10/8 | 用户级固定窗口限流 |
| stock | reconcile-threshold=3 | 对账漂移容忍度 |
| mq | message-retry-base-seconds=30 / message-max-retry=8 | 指数退避 30×2^retry |
| cache | local-max-size=500 / local-ttl-seconds=30 | Caffeine 规格 |
| cache | bloom-expected-insertions / fpp = 100000/0.01 | 布隆容量与误判率 |

**3. @Value vs @ConfigurationProperties 的项目分工**
@Value 用在"跨模块的单个值"（RedissonConfig 拿 spring.data.redis.host）；
@ConfigurationProperties 用在"业务参数包"（campusflow.* 全部 40+ 参数一次绑定）。
选型口诀：**单点取值用 @Value，一组相关值建 Properties 类**——后者有 IDE 提示与
类型校验。

**4. 启动失败的"快速失败"哲学**
配置错（绑定失败）、依赖连不上（Redisson 初始化失败）、脚本加载失败（loadScript 抛
IllegalStateException）都在启动期抛异常退出——**错误暴露越早，成本越低**。
这解释了为什么 loadScript 不做懒加载。

## 附二、Bean 装配速查（项目实例）

| 形态 | 实例 | 作用 |
|---|---|---|
| 扫描 | @Service RegistrationService | 业务组件 |
| @Bean 方法 | redissonClient() | 手动构建带参对象 |
| initMethod | registrationConfirmMessageConsumer | 资源启动 |
| @ConfigurationProperties | CampusflowProperties | 参数绑定 |
| 函数注入 | WaitlistWiringConfig | 解循环依赖 |

---

## 附三、面试加练（3 题）

**1. application.yml 里 allow-bean-definition-overriding 的注释讲了什么坑？**
要点：多模块 fat-jar 下嵌套 jar 被以两种资源形式（nested:/jar:nested:）扫描到同一
@Configuration 类，同名 Bean 定义覆盖是安全的；展开式 classpath 运行不会触发——
这是一个真实的"环境差异型"启动问题，注释即文档。

**2. @Lazy 怎么解循环依赖？与函数式注入比呢？**
要点：@Lazy 注入代理，首次调用才解析——掩盖问题但简单；函数式注入
（本项目 LongConsumer）显式表达"我只需要一个回调"，耦合面最小；
构造器注入失败本质上是"互相需要"的设计味道，重构优先。

**3. 生产配置里的敏感信息（密码/JWT secret）怎么管理？**
要点：application.yml 只放默认开发值并注释"生产必须环境变量注入"；
进阶：配置中心（Nacos）+ 加密（jasypt）+ KMS；原则：**仓库里永远没有生产密钥**。

**今日金句**：Bean 的 initMethod/destroyMethod 是"资源随容器生死"的契约——滥用 new 的人写不出它。

---

## 附四、讲义对照与口误自查

| 今日主题 | 讲义章节 |
|---|---|
| 装配与生命周期 | 02_SpringBoot与Spring核心原理 |
| 参数绑定 | 11_接口设计 第 2 节（CampusflowProperties） |
| 消费者 Bean | 06_RocketMQ 第 4 节 |

**容易说错的表述**：
- ✗ "启动时创建所有 Bean" → ✓ "单例 Bean 在容器刷新时按依赖序创建，懒加载除外"；
- ✗ "配置写在代码里" → ✓ "参数集中在 CampusflowProperties，环境变量三层兜底"。

---

## 明日预告

Day 05 Web 层规范：一个 HTTP 请求的完整生命周期（Filter/Interceptor/异常/TraceId）。
