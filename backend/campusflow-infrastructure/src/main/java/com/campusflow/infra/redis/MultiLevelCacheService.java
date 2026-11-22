package com.campusflow.infra.redis;

import com.campusflow.infra.property.CampusflowProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Function;

/**
 * Caffeine + Redis 多级缓存（Cache Aside 模式）。
 * <p>读：L1 本地(Caffeine, 秒级 TTL) -> L2 Redis(分钟级 TTL) -> DB（回填两级）。
 * <p>写：先更新 DB，再"删除"两级缓存（Del 而非 Update：避免并发写覆盖与双写不一致），
 * 并通过 MQ 广播通知其他实例失效本地缓存（见 CacheEvictConsumer）。
 * <p>一致性兜底：本地 TTL 仅 {@code localTtlSeconds} 秒，即使广播丢失，
 * 脏读窗口也被限制在该 TTL 内（延迟双删的适用场景说明见学习讲义）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiLevelCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final CampusflowProperties properties;

    private final Cache<String, String> localCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofSeconds(30))
            .recordStats()
            .build();

    /**
     * 读取（两级回源）。loader 为 DB 加载逻辑；null 表示 DB 也无数据。
     * 缓存穿透防护：DB 为 null 时缓存空值占位（短 TTL）。
     */
    public <T> T get(String key, Function<String, T> loader, Class<T> clazz) {
        String local = localCache.getIfPresent(key);
        if (local != null) {
            return deserialize(local, clazz);
        }
        String redisValue = stringRedisTemplate.opsForValue().get(key);
        if (redisValue != null) {
            localCache.put(key, redisValue);
            return deserialize(redisValue, clazz);
        }
        T data = loader.apply(key);
        if (data == null) {
            // 空值防穿透：TTL 60s
            stringRedisTemplate.opsForValue().set(key, "", Duration.ofSeconds(60));
            return null;
        }
        String json = com.campusflow.common.util.JsonUtils.toJson(data);
        stringRedisTemplate.opsForValue().set(key, json,
                Duration.ofMinutes(properties.getCache().getRedisActivityTtlMinutes()));
        localCache.put(key, json);
        return data;
    }

    /**
     * 失效两级缓存（本实例）。多实例同步由调用方通过广播 MQ 触发各实例的 {@link #evictLocal}。
     */
    public void evict(String key) {
        stringRedisTemplate.delete(key);
        localCache.invalidate(key);
        log.debug("缓存失效 key={}", key);
    }

    /** 广播消费入口：仅失效本地缓存（每个实例各自收到） */
    public void evictLocal(String key) {
        if ("".equals(key) || key.endsWith(":")) {
            localCache.invalidateAll();
        } else {
            localCache.invalidate(key);
        }
        log.debug("本地缓存失效(广播) key={}", key);
    }

    /** 直写 Redis（组织者更新活动后主动刷新场景，随后仍建议 evict 走 Cache Aside） */
    public void put(String key, Object value, Duration ttl) {
        String json = com.campusflow.common.util.JsonUtils.toJson(value);
        stringRedisTemplate.opsForValue().set(key, json, ttl);
        localCache.put(key, json);
    }

    @SuppressWarnings("unchecked")
    private <T> T deserialize(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null; // 空值占位
        }
        return com.campusflow.common.util.JsonUtils.fromJson(json, clazz);
    }
}
