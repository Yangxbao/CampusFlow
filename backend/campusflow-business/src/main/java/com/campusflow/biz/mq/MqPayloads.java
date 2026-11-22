package com.campusflow.biz.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MQ 消息体集中定义（Java 17 record：不可变、结构清晰）。
 * 所有消息均通过本地消息表投递，msgKey 用于消费幂等与全链路追踪。
 */
public final class MqPayloads {

    private MqPayloads() {
    }

    /** 报名确认消息：Redis 预留成功 -> 消费端落库确认 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegistrationConfirmMessage(
            long activityId,
            long userId,
            long reservationId,
            String messageKey,
            /** 候补晋级转正时携带 waitlistId；普通报名为 0 */
            long waitlistId,
            long reservedAtMillis) {
    }

    /** 取消报名释放消息：触发候补晋级兜底 + 统计刷新 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CancelReleaseMessage(
            long activityId,
            long userId,
            long registrationId,
            String reason,
            long occurredAtMillis) {
    }

    /** 候补晋级消息：通知被晋级用户在截止时间前确认 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WaitlistPromoteMessage(
            long activityId,
            long userId,
            long waitlistId,
            long confirmDeadlineMillis,
            long promotedAtMillis) {
    }

    /** 站内通知消息 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NotifyMessage(
            long userId,
            int type,
            String title,
            String content,
            String bizRef) {
    }

    /** 签到完成消息：异步通知 + 统计 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckinMessage(
            long activityId,
            long registrationId,
            long userId,
            long operatorId,
            long checkinAtMillis) {
    }
}
