package com.campusflow.common.constant;

/**
 * RocketMQ Topic / Tag / ConsumerGroup 统一规划。
 * <p>Topic 命名：campusflow-{业务}-{动作}-topic；消费组命名：campusflow-{业务}-group。
 * 广播模式的缓存失效 Topic 单独列出。
 */
public final class MqConstants {

    private MqConstants() {
    }

    // ===== Topic =====

    /** 报名确认（Redis 预留成功 -> 异步落库） */
    public static final String TOPIC_REGISTRATION_CONFIRM = "campusflow-registration-confirm-topic";
    public static final String TAG_REGISTRATION_CONFIRM = "CONFIRM";

    /** 取消报名（名额释放后异步处理候补晋级通知、统计） */
    public static final String TOPIC_CANCEL_RELEASE = "campusflow-cancel-release-topic";
    public static final String TAG_CANCEL_RELEASE = "RELEASE";

    /** 候补晋级通知 */
    public static final String TOPIC_WAITLIST_PROMOTE = "campusflow-waitlist-promote-topic";
    public static final String TAG_WAITLIST_PROMOTE = "PROMOTE";

    /** 站内通知异步写入 */
    public static final String TOPIC_NOTIFY = "campusflow-notify-topic";
    public static final String TAG_NOTIFY = "NOTIFY";

    /** 签到完成（异步统计/通知） */
    public static final String TOPIC_CHECKIN = "campusflow-checkin-topic";
    public static final String TAG_CHECKIN = "CHECKIN";

    /** 缓存失效广播（多实例 Caffeine 本地缓存同步），广播消费 */
    public static final String TOPIC_CACHE_EVICT = "campusflow-cache-evict-topic";
    public static final String TAG_CACHE_EVICT = "EVICT";

    // ===== Consumer Group =====

    public static final String GROUP_REGISTRATION = "campusflow-registration-group";
    public static final String GROUP_CANCEL = "campusflow-cancel-group";
    public static final String GROUP_WAITLIST = "campusflow-waitlist-group";
    public static final String GROUP_NOTIFY = "campusflow-notify-group";
    public static final String GROUP_CHECKIN = "campusflow-checkin-group";
    public static final String GROUP_CACHE_EVICT = "campusflow-cache-evict-group";

    // ===== 消息体业务键（message_deliver.msg_key 生成前缀，便于对账排查） =====
    public static final String BIZ_KEY_REGISTRATION = "REG";
    public static final String BIZ_KEY_CANCEL = "CANCEL";
    public static final String BIZ_KEY_WAITLIST = "WL";
    public static final String BIZ_KEY_NOTIFY = "NTF";
    public static final String BIZ_KEY_CHECKIN = "CK";
}
