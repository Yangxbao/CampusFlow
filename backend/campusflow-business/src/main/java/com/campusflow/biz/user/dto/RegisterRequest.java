package com.campusflow.biz.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 32, message = "用户名长度 4-32 位")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字、下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度 6-32 位")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Size(max = 32, message = "昵称最长 32 字符")
    private String nickname;

    /** 注册身份：STUDENT / ORGANIZER（默认 STUDENT；ADMIN 只能由管理员授予） */
    @Pattern(regexp = "STUDENT|ORGANIZER", message = "注册身份只能为学生或组织者")
    private String role = "STUDENT";

    @Size(max = 128, message = "邮箱格式不合法或过长")
    private String email;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不合法")
    private String phone;
}
