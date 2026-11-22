package com.campusflow.biz.user.controller;

import com.campusflow.biz.user.dto.LoginRequest;
import com.campusflow.biz.user.dto.LoginVO;
import com.campusflow.biz.user.dto.RegisterRequest;
import com.campusflow.biz.user.dto.UserVO;
import com.campusflow.biz.user.service.UserService;
import com.campusflow.common.result.ApiResult;
import com.campusflow.infra.auth.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册 / 登录 / 当前用户信息。
 */
@Tag(name = "01-认证", description = "注册、登录、当前用户")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @Operation(summary = "注册（默认学生，可选组织者）")
    @PostMapping("/register")
    public ApiResult<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResult.ok(userService.register(request));
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public ApiResult<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return ApiResult.ok(userService.login(request));
    }

    @Operation(summary = "当前用户信息")
    @GetMapping("/me")
    public ApiResult<UserVO> me() {
        return ApiResult.ok(userService.getProfile(AuthContext.getUserId()));
    }
}
