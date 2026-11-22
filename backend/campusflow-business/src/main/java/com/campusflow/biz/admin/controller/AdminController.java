package com.campusflow.biz.admin.controller;

import com.campusflow.biz.activity.dto.ActivityVO;
import com.campusflow.biz.admin.service.AdminService;
import com.campusflow.biz.audit.AuditLog;
import com.campusflow.biz.audit.entity.SysAuditLogDO;
import com.campusflow.biz.compensation.entity.CompensationTaskDO;
import com.campusflow.biz.stats.dto.ActivityStatsVO;
import com.campusflow.biz.stats.dto.AdminDashboardVO;
import com.campusflow.biz.user.dto.UserVO;
import com.campusflow.common.page.PageQuery;
import com.campusflow.common.result.ApiResult;
import com.campusflow.common.result.PageResult;
import com.campusflow.infra.mq.MessageDeliverDO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理员接口（服务端二次鉴权：JwtAuthInterceptor 校验 ADMIN 角色，
 * 普通学生直接调用该路径会被 40301 拒绝 —— 前端隐藏菜单不是安全边界）。
 */
@Tag(name = "09-后台管理")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    // ==================== 仪表盘 ====================

    @Operation(summary = "系统仪表盘（总览统计）")
    @GetMapping("/dashboard")
    public ApiResult<AdminDashboardVO> dashboard() {
        return ApiResult.ok(adminService.dashboard());
    }

    @Operation(summary = "活动统计（管理员视角）")
    @GetMapping("/activities/{id}/stats")
    public ApiResult<ActivityStatsVO> activityStats(@PathVariable Long id) {
        return ApiResult.ok(adminService.activityStats(id));
    }

    // ==================== 用户管理 ====================

    @Operation(summary = "用户列表")
    @GetMapping("/users")
    public ApiResult<PageResult<UserVO>> users(@RequestParam(required = false) String keyword,
                                               PageQuery pageQuery) {
        return ApiResult.ok(adminService.pageUsers((int) pageQuery.getPageNum(),
                (int) pageQuery.getPageSize(), keyword));
    }

    @Operation(summary = "禁用/启用用户")
    @PutMapping("/users/{id}/status")
    @AuditLog(action = "USER_STATUS_CHANGE", resource = "user")
    public ApiResult<Void> setUserStatus(@PathVariable Long id, @RequestParam boolean disabled) {
        adminService.setUserStatus(id, disabled);
        return ApiResult.ok();
    }

    // ==================== 活动审核 ====================

    @Operation(summary = "活动列表（全量）")
    @GetMapping("/activities")
    public ApiResult<PageResult<ActivityVO>> activities(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword, PageQuery pageQuery) {
        return ApiResult.ok(adminService.pageActivities(status, keyword, pageQuery));
    }

    @Operation(summary = "审核通过（触发库存预热）")
    @PostMapping("/activities/{id}/approve")
    @AuditLog(action = "ACTIVITY_APPROVE", resource = "activity")
    public ApiResult<ActivityVO> approve(@PathVariable Long id,
                                         @RequestParam(required = false) String remark) {
        adminService.reviewApprove(id, remark);
        return ApiResult.ok();
    }

    @Operation(summary = "审核拒绝")
    @PostMapping("/activities/{id}/reject")
    @AuditLog(action = "ACTIVITY_REJECT", resource = "activity")
    public ApiResult<Void> reject(@PathVariable Long id, @RequestParam String remark) {
        adminService.reviewReject(id, remark);
        return ApiResult.ok();
    }

    // ==================== 审计 / 补偿 / 消息 ====================

    @Operation(summary = "系统操作审计列表")
    @GetMapping("/audits")
    public ApiResult<PageResult<SysAuditLogDO>> audits(@RequestParam(required = false) Long userId,
                                                       @RequestParam(required = false) String action,
                                                       PageQuery pageQuery) {
        return ApiResult.ok(adminService.pageAuditLogs((int) pageQuery.getPageNum(),
                (int) pageQuery.getPageSize(), userId, action));
    }

    @Operation(summary = "补偿任务列表")
    @GetMapping("/compensations")
    public ApiResult<PageResult<CompensationTaskDO>> compensations(
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) Integer status, PageQuery pageQuery) {
        return ApiResult.ok(adminService.pageCompensationTasks((int) pageQuery.getPageNum(),
                (int) pageQuery.getPageSize(), bizType, status));
    }

    @Operation(summary = "补偿任务重新入队（死信恢复）")
    @PostMapping("/compensations/{id}/requeue")
    @AuditLog(action = "COMPENSATION_REQUEUE", resource = "compensation_task")
    public ApiResult<Void> requeue(@PathVariable Long id) {
        adminService.requeueCompensation(id);
        return ApiResult.ok();
    }

    @Operation(summary = "消息投递状态列表（本地消息表）")
    @GetMapping("/messages")
    public ApiResult<PageResult<MessageDeliverDO>> messages(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String bizType, PageQuery pageQuery) {
        return ApiResult.ok(adminService.pageMessages((int) pageQuery.getPageNum(),
                (int) pageQuery.getPageSize(), status, bizType));
    }
}
