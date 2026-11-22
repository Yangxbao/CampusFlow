package com.campusflow.biz.registration.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报名记录视图对象。
 */
@Data
@Builder
public class RegistrationVO {

    private Long id;
    private Long activityId;
    private String activityTitle;
    private String activityLocation;
    private LocalDateTime activityStartTime;
    private LocalDateTime activityEndTime;
    private Integer activityStatus;
    private Long userId;
    private String username;
    private String nickname;
    private Integer status;
    private String statusDesc;
    private LocalDateTime registeredAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime checkinAt;
    /** 是否已生成签到凭证（前端据此展示"我的凭证"入口） */
    private Boolean hasCheckinToken;
}
