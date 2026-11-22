package com.campusflow.biz.waitlist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.registration.dto.SignupResultVO;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.registration.service.RegistrationService;
import com.campusflow.biz.waitlist.entity.WaitlistDO;
import com.campusflow.biz.waitlist.mapper.WaitlistMapper;
import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.constant.RedisKeyConstants;
import com.campusflow.common.enums.RegistrationStatus;
import com.campusflow.common.enums.WaitlistStatus;
import com.campusflow.infra.mq.MessageDeliverService;
import com.campusflow.infra.property.CampusflowProperties;
import com.campusflow.infra.redis.StockRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 候补晋级服务。
 * <p>触发时机：
 * 1. 有人取消报名并释放名额后（同步触发 + 取消消息消费兜底）；
 * 2. 定时补偿任务巡检（兜底防漏：库存>0 且队列非空却无人晋级）。
 * <p>并发控制：活动级 Redisson 锁串行化晋级（ZPOPMIN 弹出即消费，
 * 若无锁且两个实例同时晋级，同一用户可能被双重服务 —— Lua 出队保证 Redis 侧安全，
 * DB 侧 markPromoted CAS + 冲突回滚服务保证最终一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistPromoteService {

    private final ActivityMapper activityMapper;
    private final WaitlistMapper waitlistMapper;
    private final RegistrationMapper registrationMapper;
    private final RegistrationService registrationService;
    private final StockRedisService stockRedisService;
    private final MessageDeliverService messageDeliverService;
    private final com.campusflow.infra.lock.RedissonLockService lockService;
    private final CampusflowProperties properties;

    /**
     * 尝试晋级队首用户（循环直到库存耗尽或队列为空）。
     * 未获取到锁时静默返回（另一实例正在处理）。
     */
    public void tryPromoteNext(long activityId) {
        try {
            lockService.tryRun(RedisKeyConstants.lockWaitlistPromote(activityId),
                    () -> promoteLoop(activityId));
        } catch (Exception e) {
            log.error("候补晋级执行异常 activityId={}", activityId, e);
        }
    }

    private void promoteLoop(long activityId) {
        int served = 0;
        while (true) {
            StockRedisService.WaitlistServeResult result =
                    stockRedisService.serveWaitlistHead(activityId,
                            properties.getReservation().getWaitlistConfirmMinutes());
            if (result.isStockEmpty() || result.isQueueEmpty() || result.getPromotedUserId() == null) {
                if (served > 0) {
                    log.info("候补晋级完成 activityId={} served={}", activityId, served);
                }
                return;
            }
            long userId = result.getPromotedUserId();
            Optional<WaitlistDO> waitlistOpt = findActiveWaitlist(activityId, userId);
            if (waitlistOpt.isEmpty()) {
                // 队首用户 DB 记录已关闭（并发取消候补等）：回滚 Redis 服务并继续下一位
                stockRedisService.releaseReservation(activityId, userId);
                log.warn("候补队首记录已失效，回滚服务 activityId={} userId={}", activityId, userId);
                continue;
            }
            WaitlistDO waitlist = waitlistOpt.get();
            LocalDateTime deadline = LocalDateTime.now()
                    .plusMinutes(properties.getReservation().getWaitlistConfirmMinutes());
            int affected = waitlistMapper.markPromoted(waitlist.getId(), deadline);
            if (affected == 0) {
                // DB 晋级失败（并发晋级）：回滚 Redis 并继续
                stockRedisService.releaseReservation(activityId, userId);
                continue;
            }
            // 发送晋级通知（发件箱）
            ActivityDO activity = activityMapper.selectById(activityId);
            messageDeliverService.createDeliverRecord("WL", MqConstants.TOPIC_WAITLIST_PROMOTE,
                    MqConstants.TAG_WAITLIST_PROMOTE, "WAITLIST", new MqPayloads.WaitlistPromoteMessage(
                            activityId, userId, waitlist.getId(),
                            deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            System.currentTimeMillis()));
            if (activity != null) {
                messageDeliverService.createAndSend("NTF", MqConstants.TOPIC_NOTIFY,
                        MqConstants.TAG_NOTIFY, "NOTIFY", new MqPayloads.NotifyMessage(
                                userId,
                                com.campusflow.common.enums.NotifyType.WAITLIST_PROMOTED.getCode(),
                                "候补晋级通知",
                                "活动《" + activity.getTitle() + "》有名额释放，已为您保留名额，"
                                        + "请在 " + properties.getReservation().getWaitlistConfirmMinutes()
                                        + " 分钟内确认，超时名额将自动释放。",
                                "waitlist:" + waitlist.getId()));
            }
            served++;
        }
    }

    private Optional<WaitlistDO> findActiveWaitlist(long activityId, long userId) {
        WaitlistDO row = waitlistMapper.selectOne(new LambdaQueryWrapper<WaitlistDO>()
                .eq(WaitlistDO::getActivityId, activityId)
                .eq(WaitlistDO::getUserId, userId)
                .last("LIMIT 1"));
        if (row == null) {
            return Optional.empty();
        }
        // 只有 WAITING 状态可晋级
        return row.getStatus() == WaitlistStatus.WAITING.getCode()
                ? Optional.of(row) : Optional.empty();
    }
}
