package com.campusflow.biz.checkin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 签到凭证视图对象：qrPayload 为前端渲染二维码的内容。
 */
@Data
@Builder
public class CheckinTokenVO {

    private Long registrationId;
    private Long activityId;
    private String activityTitle;
    private Long userId;
    /** 二维码内容（JSON 字符串，含明文 token） */
    private String qrPayload;
    private Integer status;
    private String statusDesc;
    private LocalDateTime expireAt;
    private LocalDateTime issuedAt;
}
