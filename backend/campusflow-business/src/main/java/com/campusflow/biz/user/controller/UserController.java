package com.campusflow.biz.user.controller;

import com.campusflow.biz.user.dto.ChangePasswordRequest;
import com.campusflow.biz.user.dto.UpdateProfileRequest;
import com.campusflow.biz.user.dto.UserVO;
import com.campusflow.biz.user.service.UserService;
import com.campusflow.common.result.ApiResult;
import com.campusflow.infra.auth.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人信息接口。
 */
@Tag(name = "02-个人信息")
@RestController
@RequestMapping("/api/v1/my/profile")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "获取个人信息")
    @GetMapping
    public ApiResult<UserVO> profile() {
        return ApiResult.ok(userService.getProfile(AuthContext.getUserId()));
    }

    @Operation(summary = "更新个人信息")
    @PutMapping
    public ApiResult<UserVO> update(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResult.ok(userService.updateProfile(AuthContext.getUserId(), request));
    }

    @Operation(summary = "修改密码")
    @PutMapping("/password")
    public ApiResult<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(AuthContext.getUserId(),
                request.getOldPassword(), request.getNewPassword());
        return ApiResult.ok();
    }
}
