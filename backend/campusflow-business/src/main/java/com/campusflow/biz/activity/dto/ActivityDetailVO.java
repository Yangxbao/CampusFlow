package com.campusflow.biz.activity.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 活动详情视图对象：在列表基础上补充描述、剩余名额、我的报名/候补状态。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ActivityDetailVO {

    private Long id;
    private String title;
    private String description;
    private Integer category;
    private String categoryName;
    private String coverUrl;
    private String location;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime signupStartTime;
    private LocalDateTime signupEndTime;
    private Integer capacity;
    private Integer confirmedCount;
    /** Redis 剩余名额（未预热时为 null） */
    private Long remaining;
    private Integer status;
    private String statusDesc;
    private String reviewRemark;
    private Long organizerId;
    private String organizerName;
    private Long viewCount;
    private LocalDateTime createdAt;
    /** 我的有效报名ID（未报名为 null） */
    private Long myRegistrationId;
    /** 我的报名状态描述 */
    private String myRegistrationStatus;
    /** 我的候补状态描述 */
    private String myWaitlistStatus;
    /** 当前用户是否为组织者 */
    private Boolean mine;
}
