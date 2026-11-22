package com.campusflow.biz.waitlist.controller;

import com.campusflow.biz.registration.dto.SignupResultVO;
import com.campusflow.biz.waitlist.dto.WaitlistVO;
import com.campusflow.biz.waitlist.service.WaitlistService;
import com.campusflow.common.page.PageQuery;
import com.campusflow.common.result.ApiResult;
import com.campusflow.common.result.PageResult;
import com.campusflow.infra.auth.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 候补接口（学生端）：加入候补 / 晋级确认 / 退出 / 我的候补。
 */
@Tag(name = "05-候补排队")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    @Operation(summary = "加入候补队列")
    @PostMapping("/activities/{activityId}/waitlists")
    public ApiResult<WaitlistVO> join(@PathVariable Long activityId) {
        return ApiResult.ok(waitlistService.join(activityId, AuthContext.getUserId()));
    }

    @Operation(summary = "候补晋级确认（转正报名）")
    @PostMapping("/my/waitlists/{id}/confirm")
    public ApiResult<SignupResultVO> confirm(@PathVariable Long id) {
        return ApiResult.ok(waitlistService.confirm(id, AuthContext.getUserId()));
    }

    @Operation(summary = "退出候补")
    @DeleteMapping("/my/waitlists/{id}")
    public ApiResult<Void> cancel(@PathVariable Long id) {
        waitlistService.cancel(id, AuthContext.getUserId());
        return ApiResult.ok();
    }

    @Operation(summary = "我的候补列表")
    @GetMapping("/my/waitlists")
    public ApiResult<PageResult<WaitlistVO>> myWaitlists(PageQuery pageQuery) {
        return ApiResult.ok(waitlistService.pageMyWaitlists(AuthContext.getUserId(), pageQuery));
    }
}
