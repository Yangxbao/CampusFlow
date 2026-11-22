package com.campusflow.infra.config;

import com.campusflow.common.util.TraceUtils;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置（不使用 redisson-spring-boot-starter，手动装配：
 * 1. 保留 spring-data-redis(Lettuce) 作为缓存主通道；
 * 2. Redisson 专用于分布式锁 / 布隆过滤器 / 看门狗机制；
 * 3. 生命周期由 Spring 容器管理（Bean 销毁时 shutdown，避免连接泄漏）。
 * <p>看门狗机制说明：使用 lock()/tryLock(waitTime, unit) 两参形式时不传 leaseTime，
 * Redisson 启动 watchdog（默认 30s，每 10s 续期），防止业务未完成锁先过期被误删。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + host + ":" + port;
        SingleServerConfig single = config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                // 连接池规格：本地开发足够；生产按实例数与并发量扩容
                .setConnectionMinimumIdleSize(8)
                .setConnectionPoolSize(32)
                .setIdleConnectionTimeout(30000)
                .setConnectTimeout(10000)
                .setTimeout(5000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);
        if (password != null && !password.isEmpty()) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }
}
