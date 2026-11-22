package com.campusflow.biz.checkin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 组织者核销签到请求（二维码内容直接提交）。
 */
@Data
public class ScanCheckinRequest {

    @NotNull(message = "活动ID不能为空")
    private Long activityId;

    @NotBlank(message = "签到凭证不能为空")
    private String token;
}
