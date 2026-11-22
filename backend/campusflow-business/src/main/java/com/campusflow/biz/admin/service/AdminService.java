package com.campusflow.biz.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusflow.biz.activity.dto.ActivityVO;
import com.campusflow.biz.activity.entity.ActivityDO;
import com.campusflow.biz.activity.mapper.ActivityMapper;
import com.campusflow.biz.activity.service.ActivityService;
import com.campusflow.biz.audit.entity.SysAuditLogDO;
import com.campusflow.biz.audit.SysAuditService;
import com.campusflow.biz.compensation.entity.CompensationTaskDO;
import com.campusflow.biz.compensation.mapper.CompensationTaskMapper;
import com.campusflow.biz.compensation.service.CompensationTaskService;
import com.campusflow.biz.stats.dto.ActivityStatsVO;
import com.campusflow.biz.stats.dto.AdminDashboardVO;
import com.campusflow.biz.stats.service.StatsService;
import com.campusflow.biz.user.entity.UserDO;
import com.campusflow.biz.user.mapper.UserMapper;
import com.campusflow.biz.user.service.UserService;
import com.campusflow.biz.user.dto.UserVO;
import com.campusflow.common.error.ErrorCode;
import com.campusflow.common.exception.BizException;
import com.campusflow.common.result.PageResult;
import com.campusflow.infra.mq.MessageDeliverDO;
import com.campusflow.infra.mq.MessageDeliverMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * 后台管理服务：用户管理 / 活动审核 / 审计查询 / 补偿任务 / 消息状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserMapper userMapper;
    private final UserService userService;
    private final ActivityMapper activityMapper;
    private final ActivityService activityService;
    private final StatsService statsService;
    private final SysAuditService sysAuditService;
    private final CompensationTaskMapper compensationTaskMapper;
    private final CompensationTaskService compensationTaskService;
    private final MessageDeliverMapper messageDeliverMapper;

    // ==================== 用户管理 ====================

    public PageResult<UserVO> pageUsers(int pageNum, int pageSize, String keyword) {
        Page<UserDO> page = userMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<UserDO>()
                        .and(keyword != null && !keyword.isEmpty(),
                                w -> w.like(UserDO::getUsername, keyword)
                                        .or()
                                        .like(UserDO::getNickname, keyword))
                        .orderByDesc(UserDO::getCreatedAt));
        return PageResult.of(page.getRecords().stream()
                        .map(u -> userService.toVO(u, userService.getRoleCodes(u.getId())))
                        .collect(Collectors.toList()),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    public void setUserStatus(long userId, boolean disabled) {
        UserDO user = userService.requireUser(userId);
        user.setStatus(disabled ? UserDO.STATUS_DISABLED : UserDO.STATUS_ACTIVE);
        userMapper.updateById(user);
        log.info("管理员{}用户 userId={} ", disabled ? "禁用" : "启用", userId);
    }

    // ==================== 活动审核 ====================

    public PageResult<ActivityVO> pageActivities(Integer status, String keyword,
                                                 com.campusflow.common.page.PageQuery pageQuery) {
        return activityService.pageForAdmin(status, keyword, pageQuery);
    }

    public void reviewApprove(long activityId, String remark) {
        activityService.approvePublish(activityId, remark);
    }

    public void reviewReject(long activityId, String remark) {
        activityService.rejectPublish(activityId, remark, null);
    }

    // ==================== 统计 ====================

    public AdminDashboardVO dashboard() {
        return statsService.adminDashboard();
    }

    public ActivityStatsVO activityStats(long activityId) {
        return statsService.activityStats(activityId, null, true);
    }

    // ==================== 审计 / 补偿 / 消息 ====================

    public PageResult<SysAuditLogDO> pageAuditLogs(int pageNum, int pageSize, Long userId, String action) {
        return sysAuditService.pageLogs(pageNum, pageSize, userId, action);
    }

    public PageResult<CompensationTaskDO> pageCompensationTasks(int pageNum, int pageSize,
                                                                String bizType, Integer status) {
        return compensationTaskService.pageTasks(pageNum, pageSize, bizType, status);
    }

    public void requeueCompensation(long taskId) {
        compensationTaskService.requeue(taskId);
    }

    public PageResult<MessageDeliverDO> pageMessages(int pageNum, int pageSize, Integer status, String bizType) {
        Page<MessageDeliverDO> page = messageDeliverMapper.selectPage(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<MessageDeliverDO>()
                        .eq(status != null, MessageDeliverDO::getStatus, status)
                        .eq(bizType != null && !bizType.isEmpty(), MessageDeliverDO::getBizType, bizType)
                        .orderByDesc(MessageDeliverDO::getCreatedAt));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }
}
