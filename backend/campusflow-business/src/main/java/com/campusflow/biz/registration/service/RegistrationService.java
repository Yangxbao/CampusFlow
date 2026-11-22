package com.campusflow.biz.registration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.activity.service.ActivityService;
import com.campusflow.biz.audit.StockAuditService;
import com.campusflow.biz.audit.entity.StockAuditLogDO;
import com.campusflow.biz.checkin.entity.CheckinTokenDO;
import com.campusflow.biz.checkin.mapper.CheckinTokenMapper;
import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.registration.dto.RegistrationVO;
import com.campusflow.biz.registration.dto.SignupResultVO;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.biz.user.mapper.UserMapper;
import com.campusflow.biz.waitlist.mapper.WaitlistMapper;
import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.enums.ActivityStatus;
import com.campusflow.common.enums.NotifyType;
import com.campusflow.common.enums.RegistrationStatus;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.common.result.PageResult;
import com.campusflow.infra.mq.MessageDeliverDO;
import com.campusflow.infra.mq.MessageDeliverService;
import com.campusflow.infra.property.CampusflowProperties;
import com.campusflow.infra.redis.StockRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 报名编排服务（非事务编排层）。
 * <p>报名主链路（高并发报名核心）：
 * <pre>
 * 1. 用户级限流（Lua 内固定窗口）
 * 2. Redis Lua 原子预留（校验 + 扣减 + 一人一单，一次原子执行）
 * 3. 预留记录 + 发件箱消息 同事务落库（RegistrationTxService.persistReservation）
 * 4. 立即同步发送 MQ（失败由本地消息表扫描重投）
 * 5. 消费端异步落库确认（RegistrationConfirmConsumer -> TxService.confirmRegistration）
 * </pre>
 * 状态机：REDIS_RESERVED -> MQ_PUBLISHED -> DB_CONFIRMED；异常 -> COMPENSATING -> RELEASED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final ActivityMapper activityMapper;
    private final ActivityService activityService;
    private final RegistrationMapper registrationMapper;
    private final RegistrationTxService registrationTxService;
    private final StockRedisService stockRedisService;
    private final StockAuditService stockAuditService;
    private final MessageDeliverService messageDeliverService;
    private final CheckinTokenMapper checkinTokenMapper;
    private final UserMapper userMapper;
    private final WaitlistMapper waitlistMapper;
    private final CampusflowProperties properties;

    // ==================== 报名 ====================

    /**
     * 报名入口。所有前置校验集中在 Lua 中原子执行（活动状态/时间窗/重复/库存/限流）。
     */
    public SignupResultVO signup(Long activityId, Long userId) {
        ActivityDO activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getDeleted() == 1) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_EXIST);
        }
        // 组织者不能报名自己的活动（业务规则前置，降低 Redis 无效流量）
        if (activity.getOrganizerId().equals(userId)) {
            throw BizException.of(ErrorCode.CANNOT_REGISTER_OWN_ACTIVITY);
        }

        var props = properties.getReservation();
        var signupProps = properties.getSignup();
        StockRedisService.ReserveResult result = stockRedisService.reserve(
                activityId, userId, props.getReserveTtlMinutes(),
                signupProps.getRateLimitWindowSeconds(), signupProps.getRateLimitMax());
        // 自愈路径：元数据缺失（预热丢失/Redis 重启）-> 锁内按 DB 权威口径重建 -> 重试一次
        if (result == StockRedisService.ReserveResult.META_MISSING) {
            log.info("库存元数据缺失，触发懒预热自愈 activityId={}", activityId);
            activityService.warmupStockIfNeeded(activityId);
            result = stockRedisService.reserve(
                    activityId, userId, props.getReserveTtlMinutes(),
                    signupProps.getRateLimitWindowSeconds(), signupProps.getRateLimitMax());
        }

        return switch (result) {
            case SUCCESS -> doPersistReservation(activity, userId, 0);
            case FULL -> throw BizException.of(ErrorCode.STOCK_NOT_ENOUGH);
            case DUPLICATE -> throw BizException.of(ErrorCode.ALREADY_REGISTERED);
            case ACTIVITY_NOT_AVAILABLE -> throw BizException.of(ErrorCode.ACTIVITY_NOT_PUBLISHED);
            case NOT_IN_WINDOW -> throw BizException.of(ErrorCode.ACTIVITY_NOT_IN_SIGNUP_WINDOW);
            case RATE_LIMITED -> throw BizException.of(ErrorCode.RATE_LIMITED);
            case META_MISSING -> {
                // 自愈后仍缺失：活动可能刚被取消/Redis 不可用，转业务错误而非 500
                log.warn("活动库存元数据缺失（自愈失败）activityId={} userId={}", activityId, userId);
                throw BizException.of(ErrorCode.REDIS_ERROR, "库存数据暂不可用，请稍后重试");
            }
        };
    }

    /**
     * 预留落库 + 消息投递（Lua 成功后的后续链路）。
     * waitlistId > 0 表示候补晋级转正报名。
     */
    public SignupResultVO doPersistReservation(ActivityDO activity, long userId, long waitlistId) {
        RegistrationDO existing = registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activity.getId())
                .eq(RegistrationDO::getUserId, userId)
                .last("LIMIT 1"));
        if (existing != null && RegistrationStatus.ofCode(existing.getStatus()).occupiesStock()) {
            // 双保险：Lua 已拦截，防御性校验（预留落库前 DB 已有有效记录）
            throw BizException.of(ErrorCode.ALREADY_REGISTERED);
        }
        // 事务：预留记录 + 发件箱消息（事务提交后立即发送）
        var reservation = registrationTxService.persistReservation(activity.getId(), userId, waitlistId);
        // 事务已提交，立即尝试投递（失败不影响主流程，扫描任务重投）
        MessageDeliverDO deliver = messageDeliverService.getByMsgKey(reservation.getMessageKey());
        if (deliver != null) {
            messageDeliverService.trySendDeliver(deliver);
        }
        log.info("报名预留链路完成 activityId={} userId={} reservationId={} waitlistId={}",
                activity.getId(), userId, reservation.getId(), waitlistId);
        return new SignupResultVO(null, reservation.getId(), "PENDING_CONFIRM",
                "报名已受理，系统确认中（通常数秒内完成），请稍后在我的报名中查看");
    }

    // ==================== 取消 ====================

    /**
     * 取消报名链路：
     * <pre>
     * 1. DB 事务：报名 CANCELLED（乐观锁）+ 状态日志 + 取消发件箱消息
     * 2. Redis Lua 幂等释放名额（SREM + INCR 原子）
     * 3. 库存审计（bizKey 幂等，防补偿重复记账）
     * 4. 候补晋级（Redisson 锁内出队首）
     * 5. 通知用户（发件箱）
     * </pre>
     * 并发取消：DB 乐观锁保证只有一个请求真正释放；Lua SREM 幂等保证库存只回补一次。
     */
    public void cancel(Long registrationId, Long userId) {
        // 1. DB 事务取消
        boolean changed = registrationTxService.cancelRegistration(registrationId, userId, "用户主动取消");
        RegistrationDO reg = registrationMapper.selectById(registrationId);
        long activityId = reg.getActivityId();
        ActivityDO activity = activityMapper.selectById(activityId);

        if (changed) {
            // 2. Redis 幂等释放（重复调用不会重复 INCR）
            boolean released = stockRedisService.releaseReservation(activityId, userId);
            // 3. 库存审计（幂等 bizKey）
            if (released) {
                stockAuditService.logStockChange(activityId, 1, StockAuditLogDO.SOURCE_CANCEL,
                        "CANCEL-" + registrationId, userId, "取消报名释放名额");
            }
            // 4. 触发候补晋级（锁内尝试，未获取锁说明另一实例已在处理）
            tryPromoteNextQuietly(activityId);
            // 5. 通知
            notifyQuietly(userId, NotifyType.CANCEL_SUCCESS, "取消报名成功",
                    "您已取消活动《" + (activity == null ? "" : activity.getTitle()) + "》的报名", "registration:" + registrationId);
        }
        log.info("取消报名完成 registrationId={} userId={} changed={}", registrationId, userId, changed);
    }

    private void tryPromoteNextQuietly(long activityId) {
        try {
            waitlistPromoteRunner.accept(activityId);
        } catch (Exception e) {
            log.error("候补晋级触发失败 activityId={}（取消释放消息会兜底重试）", activityId, e);
        }
    }

    /** 候补晋级入口以函数注入，避免 RegistrationService -> WaitlistService 循环依赖 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private java.util.function.LongConsumer waitlistPromoteRunner;

    public void setWaitlistPromoteRunner(java.util.function.LongConsumer runner) {
        this.waitlistPromoteRunner = runner;
    }

    private void notifyQuietly(long userId, NotifyType type, String title, String content, String bizRef) {
        try {
            messageDeliverService.createAndSend("NTF", MqConstants.TOPIC_NOTIFY,
                    MqConstants.TAG_NOTIFY, "NOTIFY",
                    new MqPayloads.NotifyMessage(userId, type.getCode(), title, content, bizRef));
        } catch (Exception e) {
            log.error("取消通知发送失败 userId={}", userId, e);
        }
    }

    // ==================== 查询 ====================

    public PageResult<RegistrationVO> pageMyRegistrations(Long userId, Integer status,
                                                          com.campusflow.common.page.PageQuery pageQuery) {
        Page<RegistrationDO> page = registrationMapper.selectPage(
                new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize()),
                new LambdaQueryWrapper<RegistrationDO>()
                        .eq(RegistrationDO::getUserId, userId)
                        .eq(status != null, RegistrationDO::getStatus, status)
                        .orderByDesc(RegistrationDO::getRegisteredAt));
        List<RegistrationVO> vos = page.getRecords().stream()
                .map(this::toVO).collect(Collectors.toList());
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }

    public RegistrationVO getDetail(Long registrationId, Long userId) {
        RegistrationDO reg = registrationMapper.selectById(registrationId);
        if (reg == null || !reg.getUserId().equals(userId)) {
            throw BizException.of(ErrorCode.REGISTRATION_NOT_EXIST);
        }
        return toVO(reg);
    }

    public RegistrationVO toVO(RegistrationDO reg) {
        ActivityDO activity = activityMapper.selectById(reg.getActivityId());
        UserDO user = userMapper.selectById(reg.getUserId());
        CheckinTokenDO token = checkinTokenMapper.selectOne(new LambdaQueryWrapper<CheckinTokenDO>()
                .eq(CheckinTokenDO::getRegistrationId, reg.getId())
                .eq(CheckinTokenDO::getStatus, CheckinTokenDO.STATUS_VALID)
                .last("LIMIT 1"));
        return RegistrationVO.builder()
                .id(reg.getId())
                .activityId(reg.getActivityId())
                .activityTitle(activity == null ? "" : activity.getTitle())
                .activityLocation(activity == null ? "" : activity.getLocation())
                .activityStartTime(activity == null ? null : activity.getStartTime())
                .activityEndTime(activity == null ? null : activity.getEndTime())
                .activityStatus(activity == null ? null : activity.getStatus())
                .userId(reg.getUserId())
                .username(user == null ? "" : user.getUsername())
                .nickname(user == null ? "" : user.getNickname())
                .status(reg.getStatus())
                .statusDesc(RegistrationStatus.ofCode(reg.getStatus()).getDesc())
                .registeredAt(reg.getRegisteredAt())
                .cancelledAt(reg.getCancelledAt())
                .checkinAt(reg.getCheckinAt())
                .hasCheckinToken(token != null)
                .build();
    }

    /** 组织者查询活动报名列表（含用户信息） */
    public PageResult<RegistrationVO> pageByActivity(long activityId, Integer status,
                                                     com.campusflow.common.page.PageQuery pageQuery) {
        Page<RegistrationDO> page = registrationMapper.selectPage(
                new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize()),
                new LambdaQueryWrapper<RegistrationDO>()
                        .eq(RegistrationDO::getActivityId, activityId)
                        .eq(status != null, RegistrationDO::getStatus, status)
                        .orderByAsc(RegistrationDO::getRegisteredAt));
        List<RegistrationVO> vos = page.getRecords().stream()
                .map(this::toVO).collect(Collectors.toList());
        return PageResult.of(vos, page.getTotal(), page.getCurrent(), page.getSize());
    }
}
