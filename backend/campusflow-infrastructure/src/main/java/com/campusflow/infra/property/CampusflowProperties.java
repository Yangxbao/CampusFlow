package com.campusflow.infra.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CampusFlow 全局业务参数集中管理。
 * <p>禁止在业务代码中散落魔法数字；调整行为只改配置文件。
 */
@Data
@Component
@ConfigurationProperties(prefix = "campusflow")
public class CampusflowProperties {

    private final Reservation reservation = new Reservation();
    private final Signup signup = new Signup();
    private final Stock stock = new Stock();
    private final Mq mq = new Mq();
    private final Jwt jwt = new Jwt();
    private final Cache cache = new Cache();
    private final Upload upload = new Upload();

    /** 报名预留与候补参数 */
    @Data
    public static class Reservation {
        /** Redis 预留状态 TTL（分钟）：超时未确认由补偿任务释放 */
        private int reserveTtlMinutes = 15;
        /** 候补晋级后确认保留期（分钟） */
        private int waitlistConfirmMinutes = 30;
        /** 预留记录最大补偿重试次数 */
        private int reservationMaxRetry = 5;
    }

    /** 报名限流参数 */
    @Data
    public static class Signup {
        /** 用户级限流窗口（秒） */
        private int rateLimitWindowSeconds = 10;
        /** 窗口内最大报名请求数 */
        private int rateLimitMax = 8;
        /** 报名接口防抖：同一用户同一活动的并发请求去重 TTL（秒） */
        private int dedupeTtlSeconds = 3;
    }

    /** 库存与对账参数 */
    @Data
    public static class Stock {
        /** 对账漂移阈值：Redis 与 DB 库存差超过该值才告警并修复 */
        private int reconcileThreshold = 3;
        /** 预热锁等待时间（秒） */
        private int preloadLockWaitSeconds = 5;
    }

    /** RocketMQ 参数 */
    @Data
    public static class Mq {
        /** NameServer 地址（yml 中支持环境变量占位） */
        private String nameServer = "127.0.0.1:9876";
        private String producerGroup = "campusflow-producer-group";
        /**
         * 消费组环境后缀（多环境共享同一 Broker 时必须隔离消费组，
         * 否则同组实例会负载均衡瓜分消息，把测试环境的确认消息"偷"到生产库）
         */
        private String groupSuffix = "";
        /** 消费线程池规格 */
        private int consumeThreadMin = 5;
        private int consumeThreadMax = 20;
        /** 同步发送超时（毫秒） */
        private int sendTimeoutMs = 3000;
        /** 同步发送失败重试次数 */
        private int sendRetryTimes = 2;
        /** 消费端最大重试次数（超过则转补偿任务，防止无限重投） */
        private int consumerMaxReconsume = 5;
        /** 本地消息表发送失败后的基础退避间隔（秒），按重试次数指数递增 */
        private int messageRetryBaseSeconds = 30;
        /** 本地消息表最大投递重试次数 */
        private int messageMaxRetry = 8;
    }

    /** JWT 参数 */
    @Data
    public static class Jwt {
        /** 签名密钥（生产必须通过环境变量 JWT_SECRET 注入，长度 >= 32 字符；yml 中做占位符解析） */
        private String secret = "campusflow-dev-secret-change-me-32bytes!!";
        /** token 有效期（小时） */
        private int expireHours = 24;
        /** 签发者 */
        private String issuer = "campusflow";
    }

    /** 缓存参数 */
    @Data
    public static class Cache {
        /** 本地 Caffeine 缓存条目上限 */
        private long localMaxSize = 500;
        /** 本地缓存过期（秒）：作为 Redis 失效广播短窗口内的兜底 */
        private int localTtlSeconds = 30;
        /** Redis 活动详情缓存 TTL（分钟） */
        private int redisActivityTtlMinutes = 30;
        /** 布隆过滤器预期容量与误判率 */
        private long bloomExpectedInsertions = 100000;
        private double bloomFpp = 0.01;
    }

    /** 文件上传参数（当前仅头像） */
    @Data
    public static class Upload {
        /** 本地存储目录（yml/环境变量可覆盖） */
        private String dir = "./data/uploads";
        /** 单文件大小上限（MB） */
        private int maxSizeMb = 2;
    }
}
