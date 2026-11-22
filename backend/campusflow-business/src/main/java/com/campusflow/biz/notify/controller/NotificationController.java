package com.campusflow.biz.notify.controller;

import com.campusflow.biz.notify.entity.NotifyMessageDO;
import com.campusflow.biz.notify.service.NotifyService;
import com.campusflow.common.page.PageQuery;
import com.campusflow.common.result.ApiResult;
import com.campusflow.common.result.PageResult;
import com.campusflow.infra.auth.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息通知接口（学生/组织者/管理员通用）。
 */
@Tag(name = "07-消息通知")
@RestController
@RequestMapping("/api/v1/my/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotifyService notifyService;

    @Operation(summary = "通知列表")
    @GetMapping
    public ApiResult<PageResult<NotifyMessageDO>> list(
            @RequestParam(required = false) Boolean unreadOnly, PageQuery pageQuery) {
        return ApiResult.ok(notifyService.pageMyNotifications(
                AuthContext.getUserId(), pageQuery, unreadOnly));
    }

    @Operation(summary = "未读数量")
    @GetMapping("/unread-count")
    public ApiResult<Long> unreadCount() {
        return ApiResult.ok(notifyService.unreadCount(AuthContext.getUserId()));
    }

    @Operation(summary = "标记已读")
    @PostMapping("/{id}/read")
    public ApiResult<Void> markRead(@PathVariable Long id) {
        notifyService.markRead(AuthContext.getUserId(), id);
        return ApiResult.ok();
    }

    @Operation(summary = "全部已读")
    @PostMapping("/read-all")
    public ApiResult<Void> markAllRead() {
        notifyService.markAllRead(AuthContext.getUserId());
        return ApiResult.ok();
    }
}
