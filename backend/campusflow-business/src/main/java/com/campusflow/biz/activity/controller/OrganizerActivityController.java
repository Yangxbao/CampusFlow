package com.campusflow.biz.activity.controller;

import com.campusflow.biz.activity.dto.ActivityUpsertRequest;
import com.campusflow.biz.activity.dto.ActivityVO;
import com.campusflow.biz.activity.service.ActivityService;
import com.campusflow.biz.registration.dto.RegistrationVO;
import com.campusflow.biz.registration.service.RegistrationService;
import com.campusflow.biz.stats.dto.ActivityStatsVO;
import com.campusflow.biz.stats.entity.ExportTaskDO;
import com.campusflow.biz.stats.service.ExportService;
import com.campusflow.biz.stats.service.StatsService;
import com.campusflow.biz.waitlist.dto.WaitlistVO;
import com.campusflow.biz.waitlist.service.WaitlistService;
import com.campusflow.common.page.PageQuery;
import com.campusflow.common.result.ApiResult;
import com.campusflow.common.result.PageResult;
import com.campusflow.infra.auth.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

/**
 * 组织者接口：活动管理 / 名额管理 / 报名人员查询 / 候补管理 / 统计 / 导出。
 */
@Tag(name = "08-组织者工作台")
@RestController
@RequestMapping("/api/v1/organizer")
@RequiredArgsConstructor
public class OrganizerActivityController {

    private final ActivityService activityService;
    private final RegistrationService registrationService;
    private final WaitlistService waitlistService;
    private final StatsService statsService;
    private final ExportService exportService;

    // ==================== 活动管理 ====================

    @Operation(summary = "我的活动列表")
    @GetMapping("/activities")
    public ApiResult<PageResult<ActivityVO>> myActivities(
            @RequestParam(required = false) Integer status, PageQuery pageQuery) {
        return ApiResult.ok(activityService.pageByOrganizer(
                AuthContext.getUserId(), status, pageQuery));
    }

    @Operation(summary = "创建活动")
    @PostMapping("/activities")
    public ApiResult<ActivityVO> create(@Valid @RequestBody ActivityUpsertRequest request) {
        return ApiResult.ok(activityService.create(request, AuthContext.getUserId()));
    }

    @Operation(summary = "编辑活动")
    @PutMapping("/activities/{id}")
    public ApiResult<ActivityVO> update(@PathVariable Long id,
                                        @Valid @RequestBody ActivityUpsertRequest request) {
        return ApiResult.ok(activityService.update(id, request, AuthContext.getUserId(), false));
    }

    @Operation(summary = "提交审核")
    @PostMapping("/activities/{id}/submit")
    public ApiResult<Void> submit(@PathVariable Long id) {
        activityService.submitForReview(id, AuthContext.getUserId());
        return ApiResult.ok();
    }

    @Operation(summary = "取消活动（通知全部报名用户）")
    @PostMapping("/activities/{id}/cancel")
    public ApiResult<Void> cancel(@PathVariable Long id) {
        activityService.cancelActivity(id, AuthContext.getUserId(), false);
        return ApiResult.ok();
    }

    @Operation(summary = "调整名额（发布后重算 Redis 库存）")
    @PutMapping("/activities/{id}/capacity")
    public ApiResult<Void> adjustCapacity(@PathVariable Long id, @RequestParam int capacity) {
        activityService.adjustCapacity(id, capacity, AuthContext.getUserId());
        return ApiResult.ok();
    }

    // ==================== 报名与候补 ====================

    @Operation(summary = "报名人员查询")
    @GetMapping("/activities/{id}/registrations")
    public ApiResult<PageResult<RegistrationVO>> registrations(
            @PathVariable Long id, @RequestParam(required = false) Integer status, PageQuery pageQuery) {
        return ApiResult.ok(registrationService.pageByActivity(id, status, pageQuery));
    }

    @Operation(summary = "候补人员管理")
    @GetMapping("/activities/{id}/waitlists")
    public ApiResult<PageResult<WaitlistVO>> waitlists(
            @PathVariable Long id, @RequestParam(required = false) Integer status, PageQuery pageQuery) {
        return ApiResult.ok(waitlistService.pageByActivity(id, status, pageQuery));
    }

    // ==================== 统计与导出 ====================

    @Operation(summary = "活动统计（ECharts 数据）")
    @GetMapping("/activities/{id}/stats")
    public ApiResult<ActivityStatsVO> stats(@PathVariable Long id) {
        return ApiResult.ok(statsService.activityStats(id, AuthContext.getUserId(), false));
    }

    @Operation(summary = "发起报名数据 CSV 导出（异步任务）")
    @PostMapping("/activities/{id}/exports")
    public ApiResult<ExportTaskDO> export(@PathVariable Long id) {
        return ApiResult.ok(exportService.submitRegistrationsExport(id, AuthContext.getUserId()));
    }

    @Operation(summary = "查询导出任务状态")
    @GetMapping("/exports/{taskId}")
    public ApiResult<ExportTaskDO> exportStatus(@PathVariable Long taskId) {
        return ApiResult.ok(exportService.getTask(taskId, AuthContext.getUserId()));
    }

    @Operation(summary = "下载导出文件")
    @GetMapping("/exports/{taskId}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long taskId) {
        Path file = exportService.getTaskFile(taskId, AuthContext.getUserId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getFileName() + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(new FileSystemResource(file));
    }
}
