package com.campusflow.biz.registration.controller;

import com.campusflow.biz.registration.dto.RegistrationVO;
import com.campusflow.biz.registration.dto.SignupResultVO;
import com.campusflow.biz.registration.service.RegistrationService;
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
 * 报名接口（学生端）：报名 / 我的报名 / 取消报名。
 */
@Tag(name = "04-活动报名")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService registrationService;

    @Operation(summary = "报名活动（Redis Lua 原子预留 + MQ 异步确认）")
    @PostMapping("/activities/{activityId}/registrations")
    public ApiResult<SignupResultVO> signup(@PathVariable Long activityId) {
        return ApiResult.ok(registrationService.signup(activityId, AuthContext.getUserId()));
    }

    @Operation(summary = "我的报名列表")
    @GetMapping("/my/registrations")
    public ApiResult<PageResult<RegistrationVO>> myRegistrations(
            @RequestParam(required = false) Integer status, PageQuery pageQuery) {
        return ApiResult.ok(registrationService.pageMyRegistrations(
                AuthContext.getUserId(), status, pageQuery));
    }

    @Operation(summary = "报名详情")
    @GetMapping("/my/registrations/{id}")
    public ApiResult<RegistrationVO> detail(@PathVariable Long id) {
        return ApiResult.ok(registrationService.getDetail(id, AuthContext.getUserId()));
    }

    @Operation(summary = "取消报名（幂等，自动触发候补晋级）")
    @DeleteMapping("/my/registrations/{id}")
    public ApiResult<Void> cancel(@PathVariable Long id) {
        registrationService.cancel(id, AuthContext.getUserId());
        return ApiResult.ok();
    }
}
