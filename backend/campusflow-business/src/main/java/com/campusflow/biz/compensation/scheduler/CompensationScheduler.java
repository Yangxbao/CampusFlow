package com.campusflow.biz.compensation.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.audit.StockAuditService;
import com.campusflow.biz.audit.entity.StockAuditLogDO;
import com.campusflow.biz.compensation.entity.CompensationTaskDO;
import com.campusflow.biz.compensation.service.CompensationTaskService;
import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.registration.service.RegistrationTxService;
import com.campusflow.biz.waitlist.entity.WaitlistDO;
import com.campusflow.biz.waitlist.mapper.WaitlistMapper;
import com.campusflow.biz.waitlist.service.WaitlistPromoteService;
import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.constant.RedisKeyConstants;
import com.campusflow.common.enums.ActivityStatus;
import com.campusflow.common.enums.NotifyType;
import com.campusflow.common.enums.RegistrationStatus;
import com.campusflow.common.enums.WaitlistStatus;
import com.campusflow.infra.lock.RedissonLockService;
import com.campusflow.infra.mq.MessageDeliverDO;
import com.campusflow.infra.mq.MessageDeliverService;
import com.campusflow.infra.property.CampusflowProperties;
import com.campusflow.infra.redis.StockRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时补偿调度器（5 个独立任务，全部 Redisson 锁防多实例重复执行）。
 * <pre>
 * 1. reservationRecovery   每 20s：扫描过期未确认的预留记录 -> 重发消息或幂等释放名额
 * 2. messageRetry          每 15s：本地消息表重投（指数退避）
 * 3. waitlistTimeout       每 30s：候补晋级超时 -> 释放名额并晋级下一位
 * 4. waitlistBackfill      每 60s：兜底晋级巡检（库存>0 且队列非空）
 * 5. stockReconcile        每 5min：Redis 与 DB 库存对账，漂移超阈值修复并审计
 * 6. activityStatusSync    每 60s：活动状态自动流转（PUBLISHED->ONGOING->FINISHED）
 * </pre>
 * 幂等保证：每个任务的操作依赖底层幂等机制（Lua SREM、DB 状态 CAS、审计 bizKey 唯一），
 * 重复执行不会产生副作用叠加。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationScheduler {

    private final RegistrationTxService registrationTxService;
    private final RegistrationMapper registrationMapper;
    private final MessageDeliverService messageDeliverService;
    private final WaitlistMapper waitlistMapper;
    private final WaitlistPromoteService waitlistPromoteService;
    private final ActivityMapper activityMapper;
    private final StockRedisService stockRedisService;
    private final StockAuditService stockAuditService;
    private final CompensationTaskService compensationTaskService;
    private final RedissonLockService lockService;
    private final CampusflowProperties properties;

    // ==================== 1. 预留恢复 ====================

    @Scheduled(fixedDelay = 20_000, initialDelay = 30_000)
    public void reservationRecovery() {
        lockService.tryRun(RedisKeyConstants.lockCompensationJob("reservationRecovery"),
                this::doReservationRecovery);
    }

    private void doReservationRecovery() {
        LocalDateTime now = LocalDateTime.now();
        List<com.campusflow.biz.registration.entity.RegistrationReservationDO> expired =
                registrationTxService.listExpiredPendingReservations(now.minusMinutes(1), 50);
        for (var reservation : expired) {
            try {
                // 查报名记录：已确认 -> 修复预留状态（对账修复）
                RegistrationDO reg = registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                        .eq(RegistrationDO::getActivityId, reservation.getActivityId())
                        .eq(RegistrationDO::getUserId, reservation.getUserId())
                        .last("LIMIT 1"));
                if (reg != null && RegistrationStatus.ofCode(reg.getStatus()).occupiesStock()
                        && reg.getStatus() != RegistrationStatus.RESERVED.getCode()) {
                    // 报名实际有效：预留记录推进 CONFIRMED（消息可能丢失但业务已完成）
                    registrationTxService.releaseReservationQuietlyConfirm(reservation.getId());
                    log.info("预留恢复：报名已有效，预留状态推进 reservationId={}", reservation.getId());
                    continue;
                }
                if (reservation.getRetryCount() < properties.getReservation().getReservationMaxRetry()) {
                    // 重发确认消息（从本地消息表原样重投）
                    MessageDeliverDO deliver = messageDeliverService.getByMsgKey(reservation.getMessageKey());
                    if (deliver != null) {
                        boolean sent = messageDeliverService.trySendDeliver(deliver);
                        registrationTxService.incrementReservationRetry(reservation.getId());
                        log.info("预留恢复：重发确认消息 reservationId={} sent={}", reservation.getId(), sent);
                    } else {
                        // 本地消息表记录丢失：重新构建
                        messageDeliverService.createAndSend("REG", MqConstants.TOPIC_REGISTRATION_CONFIRM,
                                MqConstants.TAG_REGISTRATION_CONFIRM, "REGISTRATION",
                                new MqPayloads.RegistrationConfirmMessage(
                                        reservation.getActivityId(), reservation.getUserId(),
                                        reservation.getId(), reservation.getMessageKey(), 0,
                                        System.currentTimeMillis()));
                        registrationTxService.incrementReservationRetry(reservation.getId());
                    }
                } else {
                    // 超过最大重试：幂等释放名额（COMPENSATING -> RELEASED）
                    registrationTxService.releaseReservation(reservation.getId(),
                            "预留超时，补偿释放", null);
                    stockRedisService.releaseReservation(reservation.getActivityId(), reservation.getUserId());
                    stockAuditService.logStockChange(reservation.getActivityId(), 1,
                            StockAuditLogDO.SOURCE_COMPENSATE, "COMP-REL-" + reservation.getId(),
                            null, "预留超时补偿释放");
                    // 通知用户报名确认失败
                    messageDeliverService.createAndSend("NTF", MqConstants.TOPIC_NOTIFY,
                            MqConstants.TAG_NOTIFY, "NOTIFY", new MqPayloads.NotifyMessage(
                                    reservation.getUserId(),
                                    NotifyType.REGISTER_CONFIRM_FAILED.getCode(),
                                    "报名确认失败",
                                    "您的报名预留超时未确认，名额已释放，欢迎重新报名。",
                                    "reservation:" + reservation.getId()));
                    log.warn("预留补偿释放完成 reservationId={} activityId={} userId={}",
                            reservation.getId(), reservation.getActivityId(), reservation.getUserId());
                }
            } catch (Exception e) {
                log.error("预留恢复任务异常 reservationId={}", reservation.getId(), e);
            }
        }
    }

    // ==================== 2. 本地消息表重投 ====================

    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    public void messageRetry() {
        lockService.tryRun(RedisKeyConstants.lockCompensationJob("messageRetry"), () -> {
            List<MessageDeliverDO> due = messageDeliverService.listRetryDue(LocalDateTime.now(), 50);
            for (MessageDeliverDO record : due) {
                messageDeliverService.trySendDeliver(record);
            }
            if (!due.isEmpty()) {
                log.info("本地消息表重投完成 count={}", due.size());
            }
        });
    }

    // ==================== 3. 候补晋级超时 ====================

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void waitlistTimeout() {
        lockService.tryRun(RedisKeyConstants.lockCompensationJob("waitlistTimeout"),
                this::doWaitlistTimeout);
    }

    private void doWaitlistTimeout() {
        List<WaitlistDO> timeouts = waitlistMapper.selectList(new LambdaQueryWrapper<WaitlistDO>()
                .eq(WaitlistDO::getStatus, WaitlistStatus.PROMOTED.getCode())
                .lt(WaitlistDO::getConfirmDeadline, LocalDateTime.now())
                .orderByAsc(WaitlistDO::getConfirmDeadline)
                .last("LIMIT 50"));
        for (WaitlistDO waitlist : timeouts) {
            try {
                // 晋级超时：释放预留名额 -> EXPIRED -> 晋级下一位
                boolean released = stockRedisService.releaseReservation(
                        waitlist.getActivityId(), waitlist.getUserId());
                int affected = waitlistMapper.markExpired(waitlist.getId());
                if (affected > 0) {
                    stockAuditService.logStockChange(waitlist.getActivityId(), 1,
                            StockAuditLogDO.SOURCE_WAITLIST_TIMEOUT,
                            "WLTO-" + waitlist.getId(), null,
                            released ? "候补晋级超时释放" : "候补晋级超时（Redis 无预留，幂等跳过）");
                    messageDeliverService.createAndSend("NTF", MqConstants.TOPIC_NOTIFY,
                            MqConstants.TAG_NOTIFY, "NOTIFY", new MqPayloads.NotifyMessage(
                                    waitlist.getUserId(),
                                    NotifyType.WAITLIST_PROMOTE_TIMEOUT.getCode(),
                                    "候补晋级超时",
                                    "您在候补晋级后未在规定时间内确认，名额已自动释放。",
                                    "waitlist:" + waitlist.getId()));
                    // 晋级下一位
                    waitlistPromoteService.tryPromoteNext(waitlist.getActivityId());
                    log.info("候补晋级超时处理完成 waitlistId={} released={}", waitlist.getId(), released);
                }
            } catch (Exception e) {
                log.error("候补超时处理异常 waitlistId={}", waitlist.getId(), e);
            }
        }
    }

    // ==================== 4. 候补兜底晋级巡检 ====================

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void waitlistBackfill() {
        lockService.tryRun(RedisKeyConstants.lockCompensationJob("waitlistBackfill"), () -> {
            // 查找发布中且报名窗口未结束的活动
            List<ActivityDO> activities = activityMapper.selectList(new LambdaQueryWrapper<ActivityDO>()
                    .eq(ActivityDO::getStatus, ActivityStatus.PUBLISHED.getCode())
                    .ge(ActivityDO::getSignupEndTime, LocalDateTime.now())
                    .last("LIMIT 100"));
            for (ActivityDO activity : activities) {
                Long stock = stockRedisService.getStock(activity.getId());
                if (stock == null || stock <= 0) {
                    continue;
                }
                Long waiting = waitlistMapper.selectCount(new LambdaQueryWrapper<WaitlistDO>()
                        .eq(WaitlistDO::getActivityId, activity.getId())
                        .eq(WaitlistDO::getStatus, WaitlistStatus.WAITING.getCode()));
                if (waiting != null && waiting > 0) {
                    // 有库存且有人排队：触发晋级
                    log.info("候补兜底晋级触发 activityId={} stock={} waiting={}",
                            activity.getId(), stock, waiting);
                    waitlistPromoteService.tryPromoteNext(activity.getId());
                }
            }
        });
    }

    // ==================== 5. 库存对账 ====================

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void stockReconcile() {
        lockService.tryRun(RedisKeyConstants.lockCompensationJob("stockReconcile"), this::doStockReconcile);
    }

    private void doStockReconcile() {
        List<ActivityDO> activities = activityMapper.selectList(new LambdaQueryWrapper<ActivityDO>()
                .in(ActivityDO::getStatus, ActivityStatus.PUBLISHED.getCode(),
                        ActivityStatus.ONGOING.getCode())
                .last("LIMIT 200"));
        int fixed = 0;
        for (ActivityDO activity : activities) {
            try {
                Long redisStock = stockRedisService.getStock(activity.getId());
                if (redisStock == null) {
                    // 预热丢失：重建（DB 权威口径）
                    int remaining = Math.max(0, activity.getCapacity()
                            - activityServiceCountActive(activity.getId()));
                    stockRedisService.preloadStock(activity.getId(), remaining,
                            activity.getStatus(), activity.getSignupStartTime(),
                            activity.getSignupEndTime(), activity.getEndTime());
                    log.warn("库存对账：预热丢失已重建 activityId={} remaining={}", activity.getId(), remaining);
                    fixed++;
                    continue;
                }
                int dbActive = activityServiceCountActive(activity.getId());
                int expectedRemaining = Math.max(0, activity.getCapacity() - dbActive);
                int drift = expectedRemaining - redisStock.intValue();
                int threshold = properties.getStock().getReconcileThreshold();
                if (Math.abs(drift) >= threshold) {
                    // 漂移超阈值：修复 + 审计 + 告警任务
                    log.warn("库存漂移 activityId={} redis={} expected={} drift={}",
                            activity.getId(), redisStock, expectedRemaining, drift);
                    stockRedisService.preloadStock(activity.getId(), expectedRemaining,
                            activity.getStatus(), activity.getSignupStartTime(),
                            activity.getSignupEndTime(), activity.getEndTime());
                    stockAuditService.logStockChange(activity.getId(), drift,
                            StockAuditLogDO.SOURCE_RECONCILE,
                            "RECONCILE-" + activity.getId() + "-" + System.currentTimeMillis() / 300_000,
                            null, "库存对账修复 drift=" + drift);
                    compensationTaskService.createTask(CompensationTaskDO.TYPE_STOCK_RECONCILE_ALERT,
                            "RECONCILE-ALERT-" + activity.getId() + "-" + System.currentTimeMillis() / 300_000,
                            "activityId=" + activity.getId() + " drift=" + drift);
                    fixed++;
                }
            } catch (Exception e) {
                log.error("库存对账异常 activityId={}", activity.getId(), e);
            }
        }
        if (fixed > 0) {
            log.info("库存对账完成 修复数={}", fixed);
        }
    }

    private int activityServiceCountActive(long activityId) {
        Long count = registrationMapper.selectCount(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activityId)
                .in(RegistrationDO::getStatus, RegistrationStatus.CONFIRMED.getCode(),
                        RegistrationStatus.CHECKED_IN.getCode()));
        return count == null ? 0 : count.intValue();
    }

    // ==================== 6. 活动状态自动流转 ====================

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void activityStatusSync() {
        lockService.tryRun(RedisKeyConstants.lockCompensationJob("activityStatusSync"), () -> {
            LocalDateTime now = LocalDateTime.now();
            // PUBLISHED -> ONGOING（开始时间已到）
            List<ActivityDO> toStart = activityMapper.selectList(new LambdaQueryWrapper<ActivityDO>()
                    .eq(ActivityDO::getStatus, ActivityStatus.PUBLISHED.getCode())
                    .le(ActivityDO::getStartTime, now)
                    .last("LIMIT 100"));
            for (ActivityDO activity : toStart) {
                activity.setStatus(ActivityStatus.ONGOING.getCode());
                activityMapper.updateById(activity);
                // 刷新 Redis 元数据状态
                stockRedisService.preloadStock(activity.getId(),
                        stockRedisService.getStock(activity.getId()) == null
                                ? Math.max(0, activity.getCapacity() - countActive(activity.getId()))
                                : stockRedisService.getStock(activity.getId()).intValue(),
                        ActivityStatus.ONGOING.getCode(),
                        activity.getSignupStartTime(), activity.getSignupEndTime(), activity.getEndTime());
                log.info("活动自动开始 activityId={}", activity.getId());
            }
            // ONGOING -> FINISHED（结束时间已到）
            List<ActivityDO> toFinish = activityMapper.selectList(new LambdaQueryWrapper<ActivityDO>()
                    .eq(ActivityDO::getStatus, ActivityStatus.ONGOING.getCode())
                    .le(ActivityDO::getEndTime, now)
                    .last("LIMIT 100"));
            for (ActivityDO activity : toFinish) {
                activity.setStatus(ActivityStatus.FINISHED.getCode());
                activityMapper.updateById(activity);
                stockRedisService.evictStockKeys(activity.getId());
                log.info("活动自动结束 activityId={}", activity.getId());
            }
        });
    }

    private int countActive(long activityId) {
        return activityServiceCountActive(activityId);
    }
}
