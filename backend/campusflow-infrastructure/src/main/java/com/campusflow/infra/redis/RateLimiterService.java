package com.campusflow.infra.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 分布式固定窗口限流器（INCR + EXPIRE）。
 * <p>适用：接口级防护（防刷/防重放）。报名链路的用户级限流已内置在 reserve.lua 中（原子），
 * 本服务用于其他端点的独立限流。
 * <p>固定窗口的边界突刺问题：两个窗口交界处可能放行 2*limit 的请求；
 * 若需平滑限流可替换为滑动窗口（ZSet）或令牌桶（Redisson RRateLimiter），此处业务量下固定窗口足够。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * @return true=允许，false=触发限流
     */
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        String fullKey = "campusflow:ratelimit:svc:" + key;
        Long count = stringRedisTemplate.opsForValue().increment(fullKey);
        if (count != null && count == 1) {
            stringRedisTemplate.expire(fullKey, windowSeconds, TimeUnit.SECONDS);
        }
        boolean allowed = count != null && count <= limit;
        if (!allowed) {
            log.warn("触发分布式限流 key={} count={}/{}", fullKey, count, limit);
        }
        return allowed;
    }
}
