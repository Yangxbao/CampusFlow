package com.campusflow.biz.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.checkin.entity.CheckinRecordDO;
import com.campusflow.biz.checkin.mapper.CheckinRecordMapper;
import com.campusflow.biz.compensation.entity.CompensationTaskDO;
import com.campusflow.biz.compensation.mapper.CompensationTaskMapper;
import com.campusflow.biz.mq.MqPayloads;
import com.campusflow.biz.registration.entity.RegistrationDO;
import com.campusflow.biz.registration.mapper.RegistrationMapper;
import com.campusflow.biz.registration.mapper.RegistrationReservationMapper;
import com.campusflow.biz.registration.entity.RegistrationReservationDO;
import com.campusflow.biz.stats.dto.ActivityStatsVO;
import com.campusflow.biz.stats.dto.AdminDashboardVO;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.biz.user.mapper.UserMapper;
import com.campusflow.biz.waitlist.entity.WaitlistDO;
import com.campusflow.biz.waitlist.mapper.WaitlistMapper;
import com.campusflow.common.constant.MqConstants;
import com.campusflow.common.constant.RedisKeyConstants;
import com.campusflow.common.enums.ActivityCategory;
import com.campusflow.common.enums.ActivityStatus;
import com.campusflow.common.enums.RegistrationStatus;
import com.campusflow.common.enums.WaitlistStatus;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.infra.mq.MessageDeliverDO;
import com.campusflow.infra.mq.MessageDeliverMapper;
import com.campusflow.infra.redis.StockRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统计服务：活动维度统计（组织者）与平台维度仪表盘（管理员）。
 * <p>聚合口径：
 * - confirmedCount / checkedInCount：DB 计数字段（事务内维护）；
 * - cancelled / waitlist：实时 COUNT（有索引，量级可控）；
 * - remainingStock / viewCount：Redis 实时值；
 * - 趋势：按天聚合（DATE 分组），供前端 ECharts 渲染。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private final ActivityMapper activityMapper;
    private final RegistrationMapper registrationMapper;
    private final RegistrationReservationMapper reservationMapper;
    private final CheckinRecordMapper checkinRecordMapper;
    private final WaitlistMapper waitlistMapper;
    private final UserMapper userMapper;
    private final CompensationTaskMapper compensationTaskMapper;
    private final MessageDeliverMapper messageDeliverMapper;
    private final StockRedisService stockRedisService;
    private final StringRedisTemplate stringRedisTemplate;

    public ActivityStatsVO activityStats(long activityId, Long operatorId, boolean isAdmin) {
        ActivityDO activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getDeleted() == 1) {
            throw BizException.of(ErrorCode.ACTIVITY_NOT_EXIST);
        }
        if (!isAdmin && !activity.getOrganizerId().equals(operatorId)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        Long cancelled = registrationMapper.selectCount(new LambdaQueryWrapper<RegistrationDO>()
                .eq(RegistrationDO::getActivityId, activityId)
                .eq(RegistrationDO::getStatus, RegistrationStatus.CANCELLED.getCode()));
        Long waiting = waitlistMapper.selectCount(new LambdaQueryWrapper<WaitlistDO>()
                .eq(WaitlistDO::getActivityId, activityId)
                .in(WaitlistDO::getStatus, WaitlistStatus.WAITING.getCode(),
                        WaitlistStatus.PROMOTED.getCode()));
        Long pendingReservation = reservationMapper.selectCount(new LambdaQueryWrapper<RegistrationReservationDO>()
                .eq(RegistrationReservationDO::getActivityId, activityId)
                .eq(RegistrationReservationDO::getStatus, RegistrationReservationDO.STATUS_PENDING_CONFIRM));
        String viewKey = RedisKeyConstants.activityViewCount(activityId);
        String viewRaw = stringRedisTemplate.opsForValue().get(viewKey);

        return ActivityStatsVO.builder()
                .activityId(activityId)
                .activityTitle(activity.getTitle())
                .capacity(activity.getCapacity())
                .confirmedCount(activity.getConfirmedCount())
                .checkedInCount(activity.getCheckedInCount())
                .cancelledCount(cancelled == null ? 0 : cancelled)
                .waitlistWaitingCount(waiting == null ? 0 : waiting)
                .pendingReservationCount(pendingReservation == null ? 0 : pendingReservation)
                .remainingStock(stockRedisService.getStock(activityId))
                .viewCount(viewRaw == null ? 0L : Long.parseLong(viewRaw))
                .dailyRegistrations(dailyRegistrationTrend(activityId))
                .dailyCheckins(dailyCheckinTrend(activityId))
                .build();
    }

    /** 近 14 天每日报名趋势 */
    public List<ActivityStatsVO.DailyCount> dailyRegistrationTrend(Long activityId) {
        List<RegistrationDO> regs = registrationMapper.selectList(new LambdaQueryWrapper<RegistrationDO>()
                .eq(activityId != null, RegistrationDO::getActivityId, activityId)
                .ge(RegistrationDO::getRegisteredAt, LocalDate.now().minusDays(13).atStartOfDay()));
        Map<LocalDate, Long> byDay = regs.stream().collect(Collectors.groupingBy(
                r -> r.getRegisteredAt().toLocalDate(), Collectors.counting()));
        return fillDaily(byDay);
    }

    public List<ActivityStatsVO.DailyCount> dailyCheckinTrend(Long activityId) {
        List<CheckinRecordDO> records = checkinRecordMapper.selectList(new LambdaQueryWrapper<CheckinRecordDO>()
                .eq(activityId != null, CheckinRecordDO::getActivityId, activityId)
                .ge(CheckinRecordDO::getCheckinAt, LocalDate.now().minusDays(13).atStartOfDay()));
        Map<LocalDate, Long> byDay = records.stream().collect(Collectors.groupingBy(
                r -> r.getCheckinAt().toLocalDate(), Collectors.counting()));
        return fillDaily(byDay);
    }

    private List<ActivityStatsVO.DailyCount> fillDaily(Map<LocalDate, Long> byDay) {
        List<ActivityStatsVO.DailyCount> result = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(13);
        for (int i = 0; i < 14; i++) {
            LocalDate day = start.plusDays(i);
            result.add(ActivityStatsVO.DailyCount.builder()
                    .date(day.toString())
                    .count(byDay.getOrDefault(day, 0L))
                    .build());
        }
        return result;
    }

    /** 管理端仪表盘 */
    public AdminDashboardVO adminDashboard() {
        long totalUsers = count(userMapper.selectCount(new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getDeleted, 0)));
        long totalActivities = count(activityMapper.selectCount(new LambdaQueryWrapper<ActivityDO>()
                .eq(ActivityDO::getDeleted, 0)));
        long pendingReview = count(activityMapper.selectCount(new LambdaQueryWrapper<ActivityDO>()
                .eq(ActivityDO::getStatus, ActivityStatus.PENDING_REVIEW.getCode())));
        long totalRegistrations = count(registrationMapper.selectCount(new LambdaQueryWrapper<RegistrationDO>()
                .in(RegistrationDO::getStatus, RegistrationStatus.CONFIRMED.getCode(),
                        RegistrationStatus.CHECKED_IN.getCode())));
        long todayRegistrations = count(registrationMapper.selectCount(new LambdaQueryWrapper<RegistrationDO>()
                .ge(RegistrationDO::getRegisteredAt, LocalDate.now().atStartOfDay())));
        long totalCheckins = count(checkinRecordMapper.selectCount(new LambdaQueryWrapper<CheckinRecordDO>()));
        long pendingCompensation = count(compensationTaskMapper.selectCount(new LambdaQueryWrapper<CompensationTaskDO>()
                .in(CompensationTaskDO::getStatus, CompensationTaskDO.STATUS_PENDING,
                        CompensationTaskDO.STATUS_RETRYABLE_FAILED)));
        long deadCompensation = count(compensationTaskMapper.selectCount(new LambdaQueryWrapper<CompensationTaskDO>()
                .eq(CompensationTaskDO::getStatus, CompensationTaskDO.STATUS_DEAD)));
        long failedMessages = count(messageDeliverMapper.selectCount(new LambdaQueryWrapper<MessageDeliverDO>()
                .eq(MessageDeliverDO::getStatus, MessageDeliverDO.STATUS_FAILED)));

        // 分类分布
        List<ActivityDO> activities = activityMapper.selectList(new LambdaQueryWrapper<ActivityDO>()
                .eq(ActivityDO::getDeleted, 0));
        Map<Integer, Long> byCategory = activities.stream()
                .collect(Collectors.groupingBy(ActivityDO::getCategory, Collectors.counting()));
        List<AdminDashboardVO.CategoryCount> categories = byCategory.entrySet().stream()
                .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
                .map(e -> AdminDashboardVO.CategoryCount.builder()
                        .categoryName(ActivityCategory.ofCode(e.getKey()).getDesc())
                        .count(e.getValue())
                        .build())
                .collect(Collectors.toList());

        return AdminDashboardVO.builder()
                .totalUsers(totalUsers)
                .totalActivities(totalActivities)
                .pendingReviewCount(pendingReview)
                .totalRegistrations(totalRegistrations)
                .todayRegistrations(todayRegistrations)
                .totalCheckins(totalCheckins)
                .pendingCompensationTasks(pendingCompensation)
                .deadCompensationTasks(deadCompensation)
                .failedMessageCount(failedMessages)
                .categoryDistribution(categories)
                .registrationTrend(dailyRegistrationTrend(null))
                .build();
    }

    private long count(Long value) {
        return value == null ? 0 : value;
    }
}
