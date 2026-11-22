package com.campusflow.biz.activity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 活动列表视图对象。
 * <p>含无参构造：该 VO 会进入多级缓存（JSON 序列化回读，Jackson 需要默认构造器）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityVO {

    private Long id;
    private String title;
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
    private Integer status;
    private String statusDesc;
    private Long organizerId;
    private String organizerName;
    private Long viewCount;
    private LocalDateTime createdAt;
}
