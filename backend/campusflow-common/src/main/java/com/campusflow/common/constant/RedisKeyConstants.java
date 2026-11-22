package com.campusflow.common.constant;

/**
 * Redis Key 命名规范：campusflow:{业务域}:{id}:{数据含义}
 * <p>所有 Key 必须集中在此定义，避免散落在业务代码里造成治理困难；
 * TTL 策略统一在注释中标注，禁止使用永不过期的大对象 Key。
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() {
    }

    public static final String PREFIX = "campusflow:";

    // ===== 活动库存（String，剩余名额，活动发布时预热，TTL=活动结束时间+7天） =====
    /** campusflow:activity:{id}:stock -> String(剩余名额) */
    public static final String ACTIVITY_STOCK = "campusflow:activity:%d:stock";

    // ===== 活动信息缓存（Hash，Cache Aside，TTL 30min + MQ 广播失效） =====
    /** campusflow:activity:{id}:info -> Hash(活动详情字段) */
    public static final String ACTIVITY_INFO = "campusflow:activity:%d:info";

    // ===== 活动已报名成员集合（Set，Lua 报名的前置去重，TTL=活动结束+7天） =====
    /** campusflow:activity:{id}:members -> Set(userId) */
    public static final String ACTIVITY_MEMBERS = "campusflow:activity:%d:members";

    // ===== 活动元数据（Hash：status/signupStart/signupEnd/capacity，Lua 校验数据源） =====
    /** campusflow:activity:{id}:meta -> Hash */
    public static final String ACTIVITY_META = "campusflow:activity:%d:meta";

    // ===== 候补队列（ZSet，score=入队时间戳，TTL=活动结束+7天） =====
    /** campusflow:activity:{id}:waitlist -> ZSet(userId -> joinTime) */
    public static final String ACTIVITY_WAITLIST = "campusflow:activity:%d:waitlist";

    // ===== 候补序号发生器（INCR，用于生成排队号） =====
    /** campusflow:activity:{id}:waitlist:seq */
    public static final String ACTIVITY_WAITLIST_SEQ = "campusflow:activity:%d:waitlist:seq";

    // ===== 报名预留状态（String，REDIS_RESERVED / WAITLIST_RESERVED，TTL=预留有效期） =====
    /** campusflow:reservation:{activityId}:{userId} -> String(state) */
    public static final String RESERVATION_STATE = "campusflow:reservation:%d:%d";

    // ===== 消息幂等（SETNX + TTL，消费端快速幂等去重，TTL 24h） =====
    /** campusflow:idempotent:msg:{msgKey} */
    public static final String MSG_IDEMPOTENT = "campusflow:idempotent:msg:%s";

    // ===== 用户级报名限流（固定窗口计数，TTL=窗口长度） =====
    /** campusflow:ratelimit:signup:{activityId}:{userId} */
    public static final String SIGNUP_RATE_LIMIT = "campusflow:ratelimit:signup:%d:%d";

    // ===== 热点活动查询限流保护（可扩展） =====
    /** campusflow:ratelimit:view:{activityId} */
    public static final String VIEW_RATE_LIMIT = "campusflow:ratelimit:view:%d";

    // ===== 签到一次性令牌（SETNX，防止并发扫码双签到，TTL 5min） =====
    /** campusflow:checkin:once:{tokenHash} */
    public static final String CHECKIN_ONCE = "campusflow:checkin:once:%s";

    // ===== 活动浏览量计数（INCR，用于推荐排序，TTL=活动结束+7天） =====
    /** campusflow:activity:{id}:viewcount */
    public static final String ACTIVITY_VIEW_COUNT = "campusflow:activity:%d:viewcount";

    // ===== 库存预热分布式锁（Redisson，防并发重复预热） =====
    /** campusflow:lock:stock:preload:{activityId} */
    public static final String LOCK_STOCK_PRELOAD = "campusflow:lock:stock:preload:%d";

    // ===== 候补晋级分布式锁（Redisson，同一活动串行晋级） =====
    /** campusflow:lock:waitlist:promote:{activityId} */
    public static final String LOCK_WAITLIST_PROMOTE = "campusflow:lock:waitlist:promote:%d";

    // ===== 定时补偿任务分布式锁（Redisson，多实例部署时防重复执行） =====
    /** campusflow:lock:job:{jobName} */
    public static final String LOCK_COMPENSATION_JOB = "campusflow:lock:job:%s";

    // ===== 活动状态切换分布式锁 =====
    /** campusflow:lock:activity:status:{activityId} */
    public static final String LOCK_ACTIVITY_STATUS = "campusflow:lock:activity:status:%d";

    // ===== 布隆过滤器（Redisson RBloomFilter，活动存在性快速判断） =====
    public static final String BLOOM_ACTIVITY = "campusflow:bloom:activity";

    public static String activityStock(long activityId) {
        return String.format(ACTIVITY_STOCK, activityId);
    }

    public static String activityInfo(long activityId) {
        return String.format(ACTIVITY_INFO, activityId);
    }

    public static String activityMembers(long activityId) {
        return String.format(ACTIVITY_MEMBERS, activityId);
    }

    public static String activityMeta(long activityId) {
        return String.format(ACTIVITY_META, activityId);
    }

    public static String activityWaitlist(long activityId) {
        return String.format(ACTIVITY_WAITLIST, activityId);
    }

    public static String activityWaitlistSeq(long activityId) {
        return String.format(ACTIVITY_WAITLIST_SEQ, activityId);
    }

    public static String reservationState(long activityId, long userId) {
        return String.format(RESERVATION_STATE, activityId, userId);
    }

    public static String msgIdempotent(String msgKey) {
        return String.format(MSG_IDEMPOTENT, msgKey);
    }

    public static String signupRateLimit(long activityId, long userId) {
        return String.format(SIGNUP_RATE_LIMIT, activityId, userId);
    }

    public static String checkinOnce(String tokenHash) {
        return String.format(CHECKIN_ONCE, tokenHash);
    }

    public static String activityViewCount(long activityId) {
        return String.format(ACTIVITY_VIEW_COUNT, activityId);
    }

    public static String lockStockPreload(long activityId) {
        return String.format(LOCK_STOCK_PRELOAD, activityId);
    }

    public static String lockWaitlistPromote(long activityId) {
        return String.format(LOCK_WAITLIST_PROMOTE, activityId);
    }

    public static String lockCompensationJob(String jobName) {
        return String.format(LOCK_COMPENSATION_JOB, jobName);
    }

    public static String lockActivityStatus(long activityId) {
        return String.format(LOCK_ACTIVITY_STATUS, activityId);
    }
}
