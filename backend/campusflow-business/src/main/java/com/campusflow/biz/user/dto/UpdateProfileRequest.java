package com.campusflow.biz.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新个人信息请求。
 */
@Data
public class UpdateProfileRequest {

    @Size(max = 32, message = "昵称最长 32 字符")
    private String nickname;

    @Size(max = 128, message = "邮箱过长")
    private String email;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不合法")
    private String phone;

    @Size(max = 255, message = "头像地址过长")
    private String avatar;
}
