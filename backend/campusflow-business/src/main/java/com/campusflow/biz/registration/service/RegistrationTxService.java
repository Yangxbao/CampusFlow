package com.campusflow.biz.registration.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.audit.StockAuditService;
import com.campusflow.biz.audit.entity.StockAuditLogDO;
import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.entity.RegistrationReservationDO;
import com.campusflow.biz.registration.entity.RegistrationStateLogDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.registration.mapper.RegistrationReservationMapper;
import com.campusflow.biz.registration.mapper.RegistrationStateLogMapper;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.biz.user.mapper.UserMapper;
import com.campusflow.biz.waitlist.entity.WaitlistDO;
import com.campusflow.biz.waitlist.mapper.WaitlistMapper;
import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.enums.ActivityStatus;
import com.campusflow.common.enums.RegistrationStatus;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.infra.mq.MessageDeliverService;
import com.campusflow.infra.mq.MessageDeliverDO;
import com.campusflow.infra.property.CampusflowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 报名域事务服务 —— 所有报名相关 DB 变更的唯一入口（事务边界集中管理）。
 * <p>设计要点：
 * <ul>
 *   <li>本类方法均带 @Transactional 且被外部类调用（避免同类自调用代理失效）；</li>
 *   <li>"预留落库 + 本地消息表写入"同事务：保证 Lua 预留成功后 DB 一定有据可查、
 *       消息一定会被投递（发件箱模式）；</li>
 *   <li>confirmRegistration 是幂等的：唯一约束 + 状态 CAS，重复消费不会重复生效；</li>
 *   <li>cancelRegistration 是乐观锁的：并发取消只有一个成功。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationTxService {

    private final RegistrationMapper registrationMapper;
    private final RegistrationReservationMapper reservationMapper;
    private final RegistrationStateLogMapper stateLogMapper;
    private final ActivityMapper activityMapper;
    private final WaitlistMapper waitlistMapper;
    private final UserMapper userMapper;
    private final MessageDeliverService messageDeliverService;
    private final StockAuditService stockAuditService;
    private final CampusflowProperties properties;
    private final com.campusflow.common.util.SnowflakeIdWorker snowflakeIdWorker;

    // ==================== 预留落库（事务） ====================

    /**
     * Lua 预留成功后：预留记录 + 报名确认消息（发件箱）同一事务落库。
     * 事务提交后由调用方立即尝试发送；发送失败由补偿扫描重投。
     */
    @Transactional(rollbackFor = Exception.class)
    public RegistrationReservationDO persistReservation(long activityId, long userId, long waitlistId) {
        // 预留记录（先手工生成雪花 ID，messageKey 依赖它且列非空，须在 insert 前就绪）
        RegistrationReservationDO reservation = new RegistrationReservationDO();
        reservation.setId(snowflakeIdWorker.nextId());
        reservation.setActivityId(activityId);
        reservation.setUserId(userId);
        reservation.setStatus(RegistrationReservationDO.STATUS_PENDING_CONFIRM);
        reservation.setMessageKey("REG-" + reservation.getId());
        reservation.setReservedAt(LocalDateTime.now());
        reservation.setExpireAt(LocalDateTime.now().plusMinutes(properties.getReservation().getReserveTtlMinutes()));
        reservation.setRetryCount(0);
        reservationMapper.insert(reservation);

        // 发件箱消息（同事务，msgKey 与预留记录一致：提交后立即发送需要按该键检索）
        MqPayloads.RegistrationConfirmMessage payload = new MqPayloads.RegistrationConfirmMessage(
                activityId, userId, reservation.getId(), reservation.getMessageKey(), waitlistId,
                System.currentTimeMillis());
        messageDeliverService.createDeliverRecordWithKey(reservation.getMessageKey(),
                MqConstants.TOPIC_REGISTRATION_CONFIRM,
                MqConstants.TAG_REGISTRATION_CONFIRM, "REGISTRATION", payload);
        log.info("预留落库完成 reservationId={} activityId={} userId={} msgKey={}",
                reservation.getId(), activityId, userId, reservation.getMessageKey());
        return reservation;
    }

    // ==================== 报名确认（消费端事务，幂等） ====================

    /**
     * 报名落库确认。幂等性由三层保证：
     * 1. uk_activity_user 唯一约束（并发插入拦截）；
     * 2. 状态 CAS 推进（重复确认直接返回）；
     * 3. 消费幂等表（上层拦截重复消息）。
     *
     * @return 报名ID（已存在时返回已有记录ID）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long confirmRegistration(MqPayloads.RegistrationConfirmMessage msg) {
        long activityId = msg.activityId();
        long userId = msg.userId();

        ActivityDO activity = activityMapper.selectById(activityId);
        if (activity == null) {
            // 活动已物理不存在（理论不应发生）：视为终态，消费成功防止无限重投
            log.error("报名确认异常：活动不存在 activityId={}", activityId);
            throw BizException.of(ErrorCode.ACTIVITY_NOT_EXIST);
        }
        if (activity.getStatus() == ActivityStatus.CANCELLED.getCode()) {
            // 活动已取消：释放预留而不是确认
            log.warn("报名确认时发现活动已取消 activityId={} userId={} -> 走释放补偿", activityId, userId);
            releaseReservationInternal(msg.reservationId(), activityId, userId, "活动已取消", null);
            throw BizException.of(ErrorCode.ACTIVITY_NOT_PUBLISHED);
        }

        // 用户信息快照（记录审计所需）
        UserDO user = userMapper.selectById(userId);

        RegistrationDO existing = registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activityId)
                .eq(RegistrationDO::getUserId, userId)
                .last("LIMIT 1"));
        RegistrationDO registration;
        if (existing == null) {
            registration = new RegistrationDO();
            registration.setActivityId(activityId);
            registration.setUserId(userId);
            registration.setStatus(RegistrationStatus.CONFIRMED.getCode());
            registration.setReservationId(msg.reservationId());
            registration.setRegisteredAt(LocalDateTime.now());
            registration.setVersion(0);
            try {
                registrationMapper.insert(registration);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发重复插入：回查已有记录走幂等分支
                existing = registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                        .eq(RegistrationDO::getActivityId, activityId)
                        .eq(RegistrationDO::getUserId, userId)
                        .last("LIMIT 1"));
                if (existing == null) {
                    throw e;
                }
                return idempotentConfirm(existing, msg);
            }
        } else {
            return idempotentConfirm(existing, msg);
        }

        // 新报名成功：推进配套状态（一个事务内完成）
        afterConfirm(registration, msg, activity, user);
        return registration.getId();
    }

    /** 已有记录的幂等确认：取消/失败状态回迁为已报名，其余状态直接返回 */
    private Long idempotentConfirm(RegistrationDO existing, MqPayloads.RegistrationConfirmMessage msg) {
        RegistrationStatus status = RegistrationStatus.ofCode(existing.getStatus());
        if (status == RegistrationStatus.CONFIRMED || status == RegistrationStatus.CHECKED_IN) {
            log.info("报名确认幂等命中 registrationId={} status={}", existing.getId(), status);
            markReservationConfirmedQuietly(msg.reservationId());
            return existing.getId();
        }
        if (status == RegistrationStatus.CANCELLED || status == RegistrationStatus.CONFIRM_FAILED
                || status == RegistrationStatus.RESERVED) {
            // 取消后重新报名（或预留回执晚于取消）：复用同一行状态回迁，唯一索引不冲突
            RegistrationStatus from = status;
            existing.setStatus(RegistrationStatus.CONFIRMED.getCode());
            existing.setReservationId(msg.reservationId());
            existing.setRegisteredAt(LocalDateTime.now());
            existing.setCancelledAt(null);
            existing.setCheckinAt(null);
            int affected = registrationMapper.updateById(existing);
            if (affected == 0) {
                // 并发状态竞争：由下一次投递重试
                throw BizException.of(ErrorCode.REGISTRATION_STATE_CONFLICT);
            }
            writeStateLog(existing.getId(), msg.activityId(), msg.userId(), from, RegistrationStatus.CONFIRMED,
                    "重新报名/状态回迁", null);
            afterConfirm(existing, msg,
                    activityMapper.selectById(msg.activityId()), userMapper.selectById(msg.userId()));
            return existing.getId();
        }
        throw new IllegalStateException("未知的报名状态: " + existing.getStatus());
    }

    /** 确认后的配套状态推进（同一事务） */
    private void afterConfirm(RegistrationDO registration, MqPayloads.RegistrationConfirmMessage msg,
                              ActivityDO activity, UserDO user) {
        // 1. 预留 -> CONFIRMED
        markReservationConfirmedQuietly(msg.reservationId());
        // 2. DB 侧确认人数自增（对账口径）
        activityMapper.incrementConfirmedCount(registration.getActivityId());
        // 3. 候补转正联动
        if (msg.waitlistId() > 0) {
            waitlistMapper.markConfirmed(msg.waitlistId());
        }
        // 4. 报名成功通知（发件箱：随本事务提交）
        String nickname = user == null ? String.valueOf(registration.getUserId()) : user.getNickname();
        messageDeliverService.createDeliverRecord("NTF", MqConstants.TOPIC_NOTIFY,
                MqConstants.TAG_NOTIFY, "NOTIFY", new MqPayloads.NotifyMessage(
                        registration.getUserId(),
                        com.campusflow.common.enums.NotifyType.REGISTER_SUCCESS.getCode(),
                        "报名成功",
                        "您已成功报名活动《" + activity.getTitle() + "》，请在报名记录中查看电子签到凭证。",
                        "registration:" + registration.getId()));
        log.info("报名确认完成 registrationId={} activityId={} userId={} nickname={}",
                registration.getId(), registration.getActivityId(), registration.getUserId(), nickname);
    }

    private void markReservationConfirmedQuietly(Long reservationId) {
        if (reservationId == null || reservationId <= 0) {
            return;
        }
        reservationMapper.markConfirmed(reservationId);
    }

    // ==================== 取消报名（事务） ====================

    /**
     * 取消报名（乐观锁）。成功返回 true；并发重复取消返回 false（幂等）。
     * 名额的 Redis 释放由调用方在事务提交后执行（Lua 幂等），DB 审计随后落库。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelRegistration(Long registrationId, Long userId, String reason) {
        RegistrationDO reg = registrationMapper.selectById(registrationId);
        if (reg == null || !reg.getUserId().equals(userId)) {
            throw BizException.of(ErrorCode.REGISTRATION_NOT_EXIST);
        }
        RegistrationStatus status = RegistrationStatus.ofCode(reg.getStatus());
        if (status == RegistrationStatus.CANCELLED) {
            // 重复点击取消：幂等成功
            return false;
        }
        if (!status.canCancel() && status != RegistrationStatus.RESERVED) {
            throw BizException.of(ErrorCode.CANNOT_CANCEL_CURRENT_STATUS);
        }
        // 乐观锁推进：UPDATE ... WHERE version=?
        reg.setStatus(RegistrationStatus.CANCELLED.getCode());
        reg.setCancelledAt(LocalDateTime.now());
        int affected = registrationMapper.updateById(reg);
        if (affected == 0) {
            // 并发取消竞争：另一请求已成功，视为幂等
            log.info("取消报名并发竞争 registrationId={} -> 幂等处理", registrationId);
            return false;
        }
        writeStateLog(reg.getId(), reg.getActivityId(), userId,
                status, RegistrationStatus.CANCELLED, reason, userId);
        // 发件箱：取消释放消息（候补晋级兜底 + 通知）
        messageDeliverService.createDeliverRecord("CANCEL", MqConstants.TOPIC_CANCEL_RELEASE,
                MqConstants.TAG_CANCEL_RELEASE, "CANCEL", new MqPayloads.CancelReleaseMessage(
                        reg.getActivityId(), userId, reg.getId(),
                        reason == null ? "用户取消" : reason, System.currentTimeMillis()));
        log.info("取消报名事务提交 registrationId={} activityId={} userId={}",
                reg.getId(), reg.getActivityId(), userId);
        return true;
    }

    // ==================== 预留释放与补偿（事务） ====================

    /**
     * 预留释放（补偿路径）：预留 RELEASED + 处于 RESERVED 的报名置 CONFIRM_FAILED + 状态日志。
     * Redis 名额释放（Lua）由调用方执行；DB 审计由本方法内的审计服务以幂等 bizKey 记账。
     */
    @Transactional(rollbackFor = Exception.class)
    public void releaseReservation(Long reservationId, String reason, Long operatorId) {
        RegistrationReservationDO reservation = reservationMapper.selectById(reservationId);
        if (reservation == null) {
            log.warn("预留记录不存在 reservationId={}", reservationId);
            return;
        }
        releaseReservationInternal(reservationId, reservation.getActivityId(), reservation.getUserId(),
                reason, operatorId);
    }

    private void releaseReservationInternal(Long reservationId, long activityId, long userId,
                                            String reason, Long operatorId) {
        if (reservationId != null && reservationId > 0) {
            reservationMapper.markReleased(reservationId);
        }
        // 处于"确认中"的报名记录置为确认失败（终态）
        RegistrationDO reg = registrationMapper.selectOne(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activityId)
                .eq(RegistrationDO::getUserId, userId)
                .last("LIMIT 1"));
        if (reg != null && reg.getStatus() == RegistrationStatus.RESERVED.getCode()) {
            RegistrationStatus from = RegistrationStatus.RESERVED;
            reg.setStatus(RegistrationStatus.CONFIRM_FAILED.getCode());
            int affected = registrationMapper.updateById(reg);
            if (affected > 0) {
                writeStateLog(reg.getId(), activityId, userId, from,
                        RegistrationStatus.CONFIRM_FAILED, reason, operatorId);
            }
        }
        // 库存释放审计（幂等 bizKey：重复补偿不会重复记账）
        stockAuditService.logStockChange(activityId, 1, StockAuditLogDO.SOURCE_COMPENSATE,
                "COMP-REL-" + reservationId, operatorId, reason);
        log.info("预留释放完成 reservationId={} activityId={} userId={} reason={}",
                reservationId, activityId, userId, reason);
    }

    // ==================== 查询（补偿扫描用） ====================

    /**
     * 预留状态对账修复：报名实际已确认，但预留记录仍停留在 PENDING_CONFIRM（消息丢失场景）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void releaseReservationQuietlyConfirm(Long reservationId) {
        if (reservationId != null && reservationId > 0) {
            reservationMapper.markConfirmed(reservationId);
        }
    }

    /**
     * 补偿重发计数 +1（CAS 仅 PENDING_CONFIRM 推进）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void incrementReservationRetry(Long reservationId) {
        reservationMapper.incrementRetry(reservationId);
    }

    public java.util.List<RegistrationReservationDO> listExpiredPendingReservations(LocalDateTime before, int limit) {
        return reservationMapper.selectList(new LambdaQueryWrapper<RegistrationReservationDO>()
                .eq(RegistrationReservationDO::getStatus, RegistrationReservationDO.STATUS_PENDING_CONFIRM)
                .lt(RegistrationReservationDO::getExpireAt, before)
                .orderByAsc(RegistrationReservationDO::getExpireAt)
                .last("LIMIT " + limit));
    }

    public MessageDeliverDO findDeliverByMsgKey(String msgKey) {
        return messageDeliverService.getByMsgKey(msgKey);
    }

    public void writeStateLog(Long registrationId, Long activityId, Long userId,
                              RegistrationStatus from, RegistrationStatus to,
                              String reason, Long operatorId) {
        RegistrationStateLogDO logRow = new RegistrationStateLogDO();
        logRow.setRegistrationId(registrationId);
        logRow.setActivityId(activityId);
        logRow.setUserId(userId);
        logRow.setFromStatus(from == null ? null : from.getCode());
        logRow.setToStatus(to.getCode());
        logRow.setReason(reason);
        logRow.setOperatorId(operatorId);
        stateLogMapper.insert(logRow);
    }
}
