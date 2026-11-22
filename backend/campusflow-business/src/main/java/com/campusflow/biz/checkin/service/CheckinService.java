package com.campusflow.biz.checkin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.checkin.dto.CheckinTokenVO;
import com.campusflow.biz.checkin.dto.ScanCheckinRequest;
import com.campusflow.biz.checkin.entity.CheckinRecordDO;
import com.campusflow.biz.checkin.entity.CheckinTokenDO;
import com.campusflow.biz.checkin.mapper.CheckinRecordMapper;
import com.campusflow.biz.checkin.mapper.CheckinTokenMapper;
import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.entity.RegistrationStateLogDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.registration.mapper.RegistrationStateLogMapper;
import com.campusflow.biz.registration.service.RegistrationTxService;
import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.constant.RedisKeyConstants;
import com.campusflow.common.enums.ActivityStatus;
import com.campusflow.common.enums.CheckinTokenStatus;
import com.campusflow.common.enums.NotifyType;
import com.campusflow.common.enums.RegistrationStatus;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.common.util.JsonUtils;
import com.campusflow.common.util.SnowflakeIdWorker;
import com.campusflow.infra.auth.UserPrincipal;
import com.campusflow.infra.mq.MessageDeliverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 签到服务：电子凭证签发 + 组织者核销。
 * <p>并发安全（两个管理员同时扫同一二维码不重复签到）三层防线：
 * <ol>
 *   <li>Redis SETNX 一次性令牌（快路径，拦截绝大多数并发重复）；</li>
 *   <li>DB 事务内 CAS：checkin_token WHERE status=0 与 registration WHERE status=1
 *       在同一事务内推进，任一失败整体回滚 —— 数据库层最终防线；</li>
 *   <li>checkin_record uk_registration 唯一约束兜底。</li>
 * </ol>
 * <p>防伪造：明文 token 仅签发时返回一次，DB 只存 SHA-256 摘要；
 * 核销时按摘要查询，截获 QR 内容也无法反推他人凭证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinService {

    /** 签到窗口：活动开始前 30 分钟可签到 */
    private static final int CHECKIN_ADVANCE_MINUTES = 30;

    private final CheckinTokenMapper checkinTokenMapper;
    private final CheckinRecordMapper checkinRecordMapper;
    private final RegistrationMapper registrationMapper;
    private final RegistrationStateLogMapper stateLogMapper;
    private final RegistrationTxService registrationTxService;
    private final ActivityMapper activityMapper;
    private final MessageDeliverService messageDeliverService;
    private final StringRedisTemplate stringRedisTemplate;
    private final SnowflakeIdWorker snowflakeIdWorker;

    // ==================== 学生端：签发凭证 ====================

    /**
     * 获取（或复用）我的签到凭证。同一报名凭证唯一，重复获取返回同一 token。
     */
    @Transactional(rollbackFor = Exception.class)
    public CheckinTokenVO issueToken(long registrationId, long userId) {
        RegistrationDO reg = registrationMapper.selectById(registrationId);
        if (reg == null || !reg.getUserId().equals(userId)) {
            throw BizException.of(ErrorCode.REGISTRATION_NOT_EXIST);
        }
        RegistrationStatus regStatus = RegistrationStatus.ofCode(reg.getStatus());
        if (regStatus != RegistrationStatus.CONFIRMED && regStatus != RegistrationStatus.CHECKED_IN) {
            throw BizException.of(ErrorCode.CHECKIN_REGISTRATION_NOT_CONFIRMED);
        }
        ActivityDO activity = requireOngoingActivity(reg.getActivityId());

        CheckinTokenDO existing = checkinTokenMapper.selectOne(new LambdaQueryWrapper<CheckinTokenDO>()
                .eq(CheckinTokenDO::getRegistrationId, registrationId)
                .last("LIMIT 1"));
        if (existing != null && existing.getStatus() == CheckinTokenStatus.VALID.getCode()
                && existing.getExpireAt().isAfter(LocalDateTime.now())) {
            return buildTokenVO(existing, activity, reg.getUserId());
        }
        if (existing != null && existing.getStatus() == CheckinTokenStatus.USED.getCode()) {
            throw BizException.of(ErrorCode.ALREADY_CHECKED_IN);
        }
        if (regStatus == RegistrationStatus.CHECKED_IN) {
            throw BizException.of(ErrorCode.ALREADY_CHECKED_IN);
        }

        // 签发新凭证（旧凭证过期则覆盖）
        String plainToken = UUID.randomUUID().toString().replace("-", "") + snowflakeIdWorker.nextId();
        CheckinTokenDO token = new CheckinTokenDO();
        token.setRegistrationId(registrationId);
        token.setActivityId(reg.getActivityId());
        token.setUserId(userId);
        token.setTokenHash(sha256(plainToken));
        token.setStatus(CheckinTokenStatus.VALID.getCode());
        token.setIssuedAt(LocalDateTime.now());
        token.setExpireAt(activity.getEndTime());
        if (existing != null) {
            token.setId(existing.getId());
            checkinTokenMapper.updateById(token);
        } else {
            checkinTokenMapper.insert(token);
        }
        return buildTokenVO(token, activity, userId, plainToken);
    }

    // ==================== 组织者端：核销 ====================

    /**
     * 扫码核销（组织者/管理员）。单人单次有效；并发核销由三层防线拦截。
     */
    @Transactional(rollbackFor = Exception.class)
    public CheckinRecordVO scan(ScanCheckinRequest request, UserPrincipal operator) {
        ActivityDO activity = activityMapper.selectById(request.getActivityId());
        if (activity == null || activity.getDeleted() == 1) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_EXIST);
        }
        // 权限：活动组织者或管理员
        if (!operator.isAdmin() && !activity.getOrganizerId().equals(operator.getUserId())) {
            throw BizException.of(ErrorCode.CHECKIN_NO_PERMISSION);
        }
        // 签到时间窗口
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowStart = activity.getStartTime().minusMinutes(CHECKIN_ADVANCE_MINUTES);
        if (now.isBefore(windowStart) || now.isAfter(activity.getEndTime())
                || activity.getStatus() != ActivityStatus.ONGOING.getCode()
                        && activity.getStatus() != ActivityStatus.PUBLISHED.getCode()) {
            throw BizException.of(ErrorCode.CHECKIN_NOT_IN_TIME_WINDOW);
        }

        String tokenHash = sha256(request.getToken().trim());
        // 快路径：SETNX 一次性令牌（5 分钟 TTL）
        Boolean once = stringRedisTemplate.opsForValue().setIfAbsent(
                RedisKeyConstants.checkinOnce(tokenHash), "1", Duration.ofMinutes(5));
        if (!Boolean.TRUE.equals(once)) {
            throw BizException.of(ErrorCode.ALREADY_CHECKED_IN);
        }

        CheckinTokenDO token = checkinTokenMapper.selectOne(new LambdaQueryWrapper<CheckinTokenDO>()
                .eq(CheckinTokenDO::getTokenHash, tokenHash)
                .last("LIMIT 1"));
        if (token == null || !token.getActivityId().equals(activity.getId())) {
            throw BizException.of(ErrorCode.CHECKIN_TOKEN_INVALID);
        }
        if (token.getStatus() == CheckinTokenStatus.USED.getCode()) {
            throw BizException.of(ErrorCode.ALREADY_CHECKED_IN);
        }
        if (token.getExpireAt().isBefore(now)) {
            throw BizException.of(ErrorCode.CHECKIN_TOKEN_EXPIRED);
        }

        RegistrationDO reg = registrationMapper.selectById(token.getRegistrationId());
        if (reg == null) {
            throw BizException.of(ErrorCode.REGISTRATION_NOT_EXIST);
        }
        if (reg.getStatus() == RegistrationStatus.CHECKED_IN.getCode()) {
            throw BizException.of(ErrorCode.ALREADY_CHECKED_IN);
        }
        if (reg.getStatus() != RegistrationStatus.CONFIRMED.getCode()) {
            throw BizException.of(ErrorCode.CHECKIN_REGISTRATION_NOT_CONFIRMED);
        }

        // DB 事务内 CAS 推进：token + registration 原子变更
        token.setStatus(CheckinTokenStatus.USED.getCode());
        token.setUsedAt(now);
        int tokenAffected = checkinTokenMapper.updateById(token);
        if (tokenAffected == 0) {
            throw BizException.of(ErrorCode.ALREADY_CHECKED_IN);
        }
        RegistrationStatus from = RegistrationStatus.ofCode(reg.getStatus());
        reg.setStatus(RegistrationStatus.CHECKED_IN.getCode());
        reg.setCheckinAt(now);
        int regAffected = registrationMapper.updateById(reg);
        if (regAffected == 0) {
            // 乐观锁失败：另一核销已成功（Redis 快路径失效时的兜底）
            throw BizException.of(ErrorCode.ALREADY_CHECKED_IN);
        }
        registrationTxService.writeStateLog(reg.getId(), reg.getActivityId(), reg.getUserId(),
                from, RegistrationStatus.CHECKED_IN, "现场扫码核销", operator.getUserId());

        CheckinRecordDO record = new CheckinRecordDO();
        record.setActivityId(activity.getId());
        record.setRegistrationId(reg.getId());
        record.setUserId(reg.getUserId());
        record.setOperatorId(operator.getUserId());
        record.setTokenHash(tokenHash);
        record.setCheckinAt(now);
        try {
            checkinRecordMapper.insert(record);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一约束兜底（极端并发）：事务回滚
            throw BizException.of(ErrorCode.ALREADY_CHECKED_IN);
        }
        activityMapper.incrementCheckedInCount(activity.getId());

        // 签到通知 + 签到完成消息（发件箱，随本事务提交）
        messageDeliverService.createDeliverRecord("CK", MqConstants.TOPIC_CHECKIN,
                MqConstants.TAG_CHECKIN, "CHECKIN", new MqPayloads.CheckinMessage(
                        activity.getId(), reg.getId(), reg.getUserId(),
                        operator.getUserId(), System.currentTimeMillis()));
        messageDeliverService.createDeliverRecord("NTF", MqConstants.TOPIC_NOTIFY,
                MqConstants.TAG_NOTIFY, "NOTIFY", new MqPayloads.NotifyMessage(
                        reg.getUserId(), NotifyType.CHECKIN_SUCCESS.getCode(), "签到成功",
                        "您已在活动《" + activity.getTitle() + "》完成签到。",
                        "checkin:" + record.getId()));

        log.info("签到核销成功 activityId={} registrationId={} operator={} checkinId={}",
                activity.getId(), reg.getId(), operator.getUserId(), record.getId());
        return new CheckinRecordVO(record.getId(), reg.getUserId(), record.getCheckinAt(), "签到成功");
    }

    public record CheckinRecordVO(Long checkinId, Long userId, LocalDateTime checkinAt, String message) {
    }

    // ==================== 内部工具 ====================

    private ActivityDO requireOngoingActivity(long activityId) {
        ActivityDO activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getDeleted() == 1) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_EXIST);
        }
        return activity;
    }

    private CheckinTokenVO buildTokenVO(CheckinTokenDO token, ActivityDO activity, long userId) {
        return buildTokenVO(token, activity, userId, null);
    }

    /** 明文 token 仅在新签发时返回（重取已存在凭证时返回提示状态） */
    private CheckinTokenVO buildTokenVO(CheckinTokenDO token, ActivityDO activity, long userId, String plainToken) {
        String qrPayload;
        if (plainToken != null) {
            qrPayload = JsonUtils.toJson(java.util.Map.of(
                    "activityId", activity.getId(),
                    "userId", userId,
                    "token", plainToken));
        } else {
            // 已有有效凭证：签发替代 token（刷新页面不能拿到旧明文——重签发但记录主键不变）
            String fresh = UUID.randomUUID().toString().replace("-", "") + snowflakeIdWorker.nextId();
            token.setTokenHash(sha256(fresh));
            token.setIssuedAt(LocalDateTime.now());
            checkinTokenMapper.updateById(token);
            qrPayload = JsonUtils.toJson(java.util.Map.of(
                    "activityId", activity.getId(),
                    "userId", userId,
                    "token", fresh));
        }
        return CheckinTokenVO.builder()
                .registrationId(token.getRegistrationId())
                .activityId(token.getActivityId())
                .activityTitle(activity.getTitle())
                .userId(token.getUserId())
                .qrPayload(qrPayload)
                .status(token.getStatus())
                .statusDesc(CheckinTokenStatus.ofCode(token.getStatus()).getDesc())
                .expireAt(token.getExpireAt())
                .issuedAt(token.getIssuedAt())
                .build();
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
