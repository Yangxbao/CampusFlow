package com.campusflow.biz.waitlist.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.registration.dto.SignupResultVO;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.registration.service.RegistrationService;
import com.campusflow.biz.waitlist.dto.WaitlistVO;
import com.campusflow.biz.waitlist.entity.WaitlistDO;
import com.campusflow.biz.waitlist.mapper.WaitlistMapper;
import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.constant.RedisKeyConstants;
import com.campusflow.common.enums.ActivityStatus;
import com.campusflow.common.enums.NotifyType;
import com.campusflow.common.enums.RegistrationStatus;
import com.campusflow.common.enums.WaitlistStatus;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.common.result.PageResult;
import com.campusflow.infra.mq.MessageDeliverService;
import com.campusflow.infra.property.CampusflowProperties;
import com.campusflow.infra.redis.StockRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 候补服务：加入 / 晋级确认 / 退出 / 查询。
 * <p>队列权威数据在 Redis ZSet（score=入队时间戳，保证真实顺序）；
 * waitlist 表为持久化依据（服务重启后可重建队列 + 审计）。
 * 并发安全：ZADD NX 原子防重复入队 + DB uk_activity_user 唯一约束兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistService {

    private final ActivityMapper activityMapper;
    private final WaitlistMapper waitlistMapper;
    private final RegistrationMapper registrationMapper;
    private final RegistrationService registrationService;
    private final StockRedisService stockRedisService;
    private final StringRedisTemplate stringRedisTemplate;
    private final MessageDeliverService messageDeliverService;
    private final CampusflowProperties properties;

    /**
     * 加入候补队列。仅当名额已满且在报名窗口内允许排队。
     */
    public WaitlistVO join(long activityId, long userId) {
        ActivityDO activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getDeleted() == 1) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_EXIST);
        }
        if (!ActivityStatus.ofCode(activity.getStatus()).canSignup()) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_PUBLISHED);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getSignupStartTime()) || now.isAfter(activity.getSignupEndTime())) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_IN_SIGNUP_WINDOW);
        }
        // 已有有效报名则无需候补
        RegistrationDO reg = registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activityId)
                .eq(RegistrationDO::getUserId, userId)
                .last("LIMIT 1"));
        if (reg != null && RegistrationStatus.ofCode(reg.getStatus()).occupiesStock()) {
            throw BizException.of(ErrorCode.ALREADY_REGISTERED);
        }
        // 前置：已有候补记录（幂等校验）
        WaitlistDO existing = waitlistMapper.selectOne(new LambdaQueryWrapper<WaitlistDO>()
                .eq(WaitlistDO::getActivityId, activityId)
                .eq(WaitlistDO::getUserId, userId)
                .last("LIMIT 1"));
        if (existing != null && (existing.getStatus() == WaitlistStatus.WAITING.getCode()
                || existing.getStatus() == WaitlistStatus.PROMOTED.getCode())) {
            throw BizException.of(ErrorCode.WAITLIST_DUPLICATE);
        }

        // 名额校验：有名额时引导直接报名（从 Redis 读，未预热时按 DB 口径预热）
        Long stock = stockRedisService.getStock(activityId);
        if (stock == null) {
            throw BizException.of(ErrorCode.STOCK_NOT_ENOUGH, "库存暂不可用，请稍后重试或联系组织者");
        }
        if (stock > 0) {
            throw BizException.of(ErrorCode.WAITLIST_NOT_ALLOWED);
        }

        // Redis ZADD NX + 排队号 INCR（原子）
        String waitlistKey = RedisKeyConstants.activityWaitlist(activityId);
        String seqKey = RedisKeyConstants.activityWaitlistSeq(activityId);
        double score = System.currentTimeMillis();
        Boolean added = stringRedisTemplate.opsForZSet().addIfAbsent(waitlistKey,
                String.valueOf(userId), score);
        if (!Boolean.TRUE.equals(added)) {
            // 已在 Redis 队列但 DB 记录缺失/关闭：修复 DB 记录（重建场景）
            if (existing != null) {
                existing.setStatus(WaitlistStatus.WAITING.getCode());
                existing.setJoinTime(now);
                existing.setClosedAt(null);
                waitlistMapper.updateById(existing);
                return toVO(existing, activity);
            }
        }
        Long seq = stringRedisTemplate.opsForValue().increment(seqKey);
        int queueNo = seq == null ? 0 : seq.intValue();

        WaitlistDO waitlist;
        if (existing != null) {
            existing.setQueueNo(queueNo);
            existing.setStatus(WaitlistStatus.WAITING.getCode());
            existing.setJoinTime(now);
            existing.setClosedAt(null);
            waitlistMapper.updateById(existing);
            waitlist = existing;
        } else {
            waitlist = new WaitlistDO();
            waitlist.setActivityId(activityId);
            waitlist.setUserId(userId);
            waitlist.setQueueNo(queueNo);
            waitlist.setStatus(WaitlistStatus.WAITING.getCode());
            waitlist.setJoinTime(now);
            try {
                waitlistMapper.insert(waitlist);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // DB 唯一约束兜底（并发加入）
                throw BizException.of(ErrorCode.WAITLIST_DUPLICATE);
            }
        }
        // 入队通知（告知当前排位）
        Long ahead = stringRedisTemplate.opsForZSet().rank(waitlistKey, String.valueOf(userId));
        messageDeliverService.createAndSend("NTF", MqConstants.TOPIC_NOTIFY,
                MqConstants.TAG_NOTIFY, "NOTIFY", new MqPayloads.NotifyMessage(
                        userId, NotifyType.WAITLIST_JOINED.getCode(), "候补排队成功",
                        "您已加入活动《" + activity.getTitle() + "》候补队列，当前排位第 "
                                + (ahead == null ? queueNo : ahead + 1) + " 位，有名额释放将自动通知您。",
                        "waitlist:" + waitlist.getId()));
        log.info("候补入队 activityId={} userId={} queueNo={}", activityId, userId, queueNo);
        return toVO(waitlist, activity);
    }

    /**
     * 候补晋级确认：转为正式报名（走报名确认链路，waitlistId 联动转正）。
     */
    public SignupResultVO confirm(long waitlistId, long userId) {
        WaitlistDO waitlist = waitlistMapper.selectById(waitlistId);
        if (waitlist == null || !waitlist.getUserId().equals(userId)) {
            throw BizException.of(ErrorCode.WAITLIST_NOT_EXIST);
        }
        if (waitlist.getStatus() == WaitlistStatus.CONFIRMED.getCode()) {
            // 幂等：重复确认
            return new SignupResultVO(null, null, "PENDING_CONFIRM", "候补已转正，报名确认中");
        }
        if (waitlist.getStatus() != WaitlistStatus.PROMOTED.getCode()) {
            throw BizException.of(waitlist.getStatus() == WaitlistStatus.EXPIRED.getCode()
                    ? ErrorCode.WAITLIST_EXPIRED : ErrorCode.WAITLIST_NOT_PROMOTED);
        }
        if (LocalDateTime.now().isAfter(waitlist.getConfirmDeadline())) {
            throw BizException.of(ErrorCode.WAITLIST_EXPIRED);
        }
        ActivityDO activity = activityMapper.selectById(waitlist.getActivityId());
        // 复用报名确认链路（预留 -> MQ -> 落库），消费端联动 waitlist 转正
        return registrationService.doPersistReservation(activity, userId, waitlistId);
    }

    /**
     * 退出候补：Redis ZREM + DB 关闭（幂等）。
     */
    public void cancel(long waitlistId, long userId) {
        WaitlistDO waitlist = waitlistMapper.selectById(waitlistId);
        if (waitlist == null || !waitlist.getUserId().equals(userId)) {
            throw BizException.of(ErrorCode.WAITLIST_NOT_EXIST);
        }
        if (waitlist.getStatus() == WaitlistStatus.PROMOTED.getCode()) {
            // 已晋级名额占用中，退出需要释放（走取消链路的释放逻辑）
            stockRedisService.releaseReservation(waitlist.getActivityId(), userId);
        }
        stringRedisTemplate.opsForZSet().remove(
                RedisKeyConstants.activityWaitlist(waitlist.getActivityId()), String.valueOf(userId));
        waitlistMapper.markCancelled(waitlistId);
        log.info("候补退出 activityId={} userId={} waitlistId={}", waitlist.getActivityId(), userId, waitlistId);
    }

    public PageResult<WaitlistVO> pageMyWaitlists(long userId, com.campusflow.common.page.PageQuery pageQuery) {
        Page<WaitlistDO> page = waitlistMapper.selectPage(new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize()),
                new LambdaQueryWrapper<WaitlistDO>()
                        .eq(WaitlistDO::getUserId, userId)
                        .orderByDesc(WaitlistDO::getJoinTime));
        List<WaitlistVO> vos = page.getRecords().stream()
                .map(w -> toVO(w, activityMapper.selectById(w.getActivityId())))
                .collect(Collectors.toList());
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    public PageResult<WaitlistVO> pageByActivity(long activityId, Integer status,
                                                 com.campusflow.common.page.PageQuery pageQuery) {
        Page<WaitlistDO> page = waitlistMapper.selectPage(new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize()),
                new LambdaQueryWrapper<WaitlistDO>()
                        .eq(WaitlistDO::getActivityId, activityId)
                        .eq(status != null, WaitlistDO::getStatus, status)
                        .orderByAsc(WaitlistDO::getQueueNo));
        List<WaitlistVO> vos = page.getRecords().stream()
                .map(w -> toVO(w, activityMapper.selectById(w.getActivityId())))
                .collect(Collectors.toList());
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    private WaitlistVO toVO(WaitlistDO w, ActivityDO activity) {
        Long ahead = w.getStatus() == WaitlistStatus.WAITING.getCode()
                ? stringRedisTemplate.opsForZSet().rank(
                        RedisKeyConstants.activityWaitlist(w.getActivityId()), String.valueOf(w.getUserId()))
                : null;
        return WaitlistVO.builder()
                .id(w.getId())
                .activityId(w.getActivityId())
                .activityTitle(activity == null ? "" : activity.getTitle())
                .userId(w.getUserId())
                .queueNo(w.getQueueNo())
                .status(w.getStatus())
                .statusDesc(WaitlistStatus.ofCode(w.getStatus()).getDesc())
                .aheadCount(ahead)
                .joinTime(w.getJoinTime())
                .promotedAt(w.getPromotedAt())
                .confirmDeadline(w.getConfirmDeadline())
                .confirmedAt(w.getConfirmedAt())
                .closedAt(w.getClosedAt())
                .build();
    }
}
