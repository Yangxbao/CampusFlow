package com.campusflow.infra.redis;

import com.campusflow.infra.property.CampusflowProperties;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.campusflow.common.constant.RedisKeyConstants.*;

/**
 * 活动库存 Redis 服务（预热 / Lua 原子预留 / Lua 幂等释放 / 候补出队）。
 * <p>预热内容设计：
 * <ul>
 *   <li>stock（String）：剩余名额 —— 高并发扣减对象，适合缓存；</li>
 *   <li>meta（Hash）：status/signupStart/signupEnd —— Lua 校验数据源，避免脚本里查 DB；</li>
 *   <li>members（Set）：已报名用户 —— 一人一单前置去重；</li>
 *   <li>不适合缓存：活动长描述、审核记录等大文本（读少写多、体积大，缓存收益低）。</li>
 * </ul>
 * 一致性方案：Cache Aside + 库存"Redis 权威扣减、DB 异步对账"，
 * 活动变更（容量调整/取消）时失效缓存并重新预热；Redis 异常时报名降级失败（快速失败优于脏数据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockRedisService {

    private final StringRedisTemplate stringRedisTemplate;
    private final CampusflowProperties properties;

    /** reserve.lua：DefaultRedisScript 缓存脚本摘要，使用 EVALSHA 减少网络传输 */
    private final DefaultRedisScript<Long> reserveScript = loadScript("lua/reserve.lua");
    private final DefaultRedisScript<Long> cancelReleaseScript = loadScript("lua/cancel_release.lua");
    private final DefaultRedisScript<String> waitlistServeScript = loadScript("lua/waitlist_serve.lua");

    @SuppressWarnings("unchecked")
    private <T> DefaultRedisScript<T> loadScript(String path) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        try {
            String content = new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            script.setScriptText(content);
        } catch (Exception e) {
            throw new IllegalStateException("加载 Lua 脚本失败: " + path, e);
        }
        script.setResultType((Class<T>) (path.equals("lua/waitlist_serve.lua") ? String.class : Long.class));
        return script;
    }

    /**
     * 库存预热：发布/容量变更时调用。调用方需持有活动级分布式锁（防并发重复预热）。
     *
     * @param activityId     活动ID
     * @param remaining      剩余名额（DB 权威值：capacity - 已确认有效报名数）
     * @param statusCode     活动状态码（3=已发布 4=进行中）
     * @param signupStart    报名开始时间
     * @param signupEnd      报名截止时间
     * @param activityEnd    活动结束时间（决定 TTL：结束时间 + 7 天，保证结算期数据可查）
     */
    public void preloadStock(long activityId, int remaining, int statusCode,
                             LocalDateTime signupStart, LocalDateTime signupEnd,
                             LocalDateTime activityEnd) {
        String stockKey = activityStock(activityId);
        String metaKey = activityMeta(activityId);
        String membersKey = activityMembers(activityId);

        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(remaining));
        stringRedisTemplate.opsForHash().putAll(metaKey, Map.of(
                "status", String.valueOf(statusCode),
                "signupStart", String.valueOf(toMillis(signupStart)),
                "signupEnd", String.valueOf(toMillis(signupEnd)),
                "capacity", String.valueOf(remaining)
        ));

        long ttlSeconds = Math.max(1, TimeUnit.SECONDS.convert(
                java.time.Duration.between(LocalDateTime.now(),
                        activityEnd.plusDays(7)).toMillis(), TimeUnit.MILLISECONDS));
        // 统一 TTL：活动结束 + 7 天后自动清理，防止 Key 治理失控
        stringRedisTemplate.expire(stockKey, ttlSeconds, TimeUnit.SECONDS);
        stringRedisTemplate.expire(metaKey, ttlSeconds, TimeUnit.SECONDS);
        stringRedisTemplate.expire(membersKey, ttlSeconds, TimeUnit.SECONDS);
        log.info("活动库存预热完成 activityId={} remaining={} ttlDays={}", activityId, remaining, ttlSeconds / 86400);
    }

    /**
     * Lua 原子报名预留。
     */
    public ReserveResult reserve(long activityId, long userId, int reserveTtlMinutes,
                                 int rateWindowSeconds, int rateMaxPerWindow) {
        Long ret = stringRedisTemplate.execute(reserveScript,
                List.of(activityStock(activityId), activityMembers(activityId),
                        activityMeta(activityId), reservationState(activityId, userId),
                        signupRateLimit(activityId, userId)),
                String.valueOf(userId),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(reserveTtlMinutes * 60L),
                String.valueOf(rateWindowSeconds),
                String.valueOf(rateMaxPerWindow));
        int code = ret == null ? -5 : ret.intValue();
        log.info("Lua 报名预留 activityId={} userId={} code={}", activityId, userId, code);
        return ReserveResult.fromCode(code);
    }

    /**
     * 取消释放（幂等）：只有此前确实是成员才返回 true 并回补库存。
     */
    public boolean releaseReservation(long activityId, long userId) {
        Long ret = stringRedisTemplate.execute(cancelReleaseScript,
                List.of(activityStock(activityId), activityMembers(activityId),
                        reservationState(activityId, userId)),
                String.valueOf(userId));
        boolean released = ret != null && ret == 1;
        log.info("Lua 取消释放 activityId={} userId={} released={}", activityId, userId, released);
        return released;
    }

    /**
     * 候补队首出队 + 直接获得预留（配合活动级分布式锁调用）。
     *
     * @return PromotedUser(userId) / StockEmpty / QueueEmpty
     */
    public WaitlistServeResult serveWaitlistHead(long activityId, int reserveTtlMinutes) {
        String ret = stringRedisTemplate.execute(waitlistServeScript,
                List.of(activityWaitlist(activityId), activityStock(activityId),
                        activityMembers(activityId),
                        "campusflow:reservation:" + activityId + ":"),
                String.valueOf(reserveTtlMinutes * 60L));
        if (ret == null) {
            return WaitlistServeResult.stockEmpty();
        }
        return switch (ret) {
            case "0" -> WaitlistServeResult.stockEmpty();
            case "1" -> WaitlistServeResult.queueEmpty();
            default -> WaitlistServeResult.promoted(Long.parseLong(ret));
        };
    }

    /** 读取剩余名额（监控/展示用，报名链路以 Lua 内读数为准） */
    public Long getStock(long activityId) {
        String v = stringRedisTemplate.opsForValue().get(activityStock(activityId));
        return v == null ? null : Long.parseLong(v);
    }

    /** 活动变更后失效库存相关 Key（容量调整/取消时调用） */
    public void evictStockKeys(long activityId) {
        stringRedisTemplate.delete(List.of(
                activityStock(activityId), activityMeta(activityId)));
        log.info("失效活动库存与元数据 Key activityId={}", activityId);
    }

    public void removeMember(long activityId, long userId) {
        stringRedisTemplate.opsForSet().remove(activityMembers(activityId), String.valueOf(userId));
    }

    public boolean isMember(long activityId, long userId) {
        Boolean member = stringRedisTemplate.opsForSet()
                .isMember(activityMembers(activityId), String.valueOf(userId));
        return Boolean.TRUE.equals(member);
    }

    private long toMillis(LocalDateTime time) {
        return time == null ? 0 : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** Lua 预留返回码语义 */
    @Getter
    @RequiredArgsConstructor
    public enum ReserveResult {
        SUCCESS(1),
        FULL(0),
        ACTIVITY_NOT_AVAILABLE(-1),
        NOT_IN_WINDOW(-2),
        DUPLICATE(-3),
        RATE_LIMITED(-4),
        META_MISSING(-5);

        private final int code;

        public static ReserveResult fromCode(int code) {
            for (ReserveResult r : values()) {
                if (r.code == code) {
                    return r;
                }
            }
            return META_MISSING;
        }
    }

    @Data
    public static class WaitlistServeResult {
        private Long promotedUserId;
        private boolean stockEmpty;
        private boolean queueEmpty;

        static WaitlistServeResult promoted(long userId) {
            WaitlistServeResult r = new WaitlistServeResult();
            r.promotedUserId = userId;
            return r;
        }

        static WaitlistServeResult stockEmpty() {
            WaitlistServeResult r = new WaitlistServeResult();
            r.stockEmpty = true;
            return r;
        }

        static WaitlistServeResult queueEmpty() {
            WaitlistServeResult r = new WaitlistServeResult();
            r.queueEmpty = true;
            return r;
        }
    }
}
