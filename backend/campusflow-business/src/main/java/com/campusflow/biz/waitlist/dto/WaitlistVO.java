package com.campusflow.biz.waitlist.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 候补记录视图对象。
 */
@Data
@Builder
public class WaitlistVO {

    private Long id;
    private Long activityId;
    private String activityTitle;
    private Long userId;
    private Integer queueNo;
    private Integer status;
    private String statusDesc;
    /** 当前排队位置（仅候补中有意义） */
    private Long aheadCount;
    private LocalDateTime joinTime;
    private LocalDateTime promotedAt;
    private LocalDateTime confirmDeadline;
    private LocalDateTime confirmedAt;
    private LocalDateTime closedAt;
}
