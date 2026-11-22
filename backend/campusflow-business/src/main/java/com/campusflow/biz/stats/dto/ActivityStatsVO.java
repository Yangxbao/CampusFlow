package com.campusflow.biz.stats.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 活动统计视图对象。
 */
@Data
@Builder
public class ActivityStatsVO {

    private Long activityId;
    private String activityTitle;
    private Integer capacity;
    private Integer confirmedCount;
    private Integer checkedInCount;
    private Long cancelledCount;
    private Long waitlistWaitingCount;
    private Long pendingReservationCount;
    /** Redis 剩余名额 */
    private Long remainingStock;
    /** Redis 浏览量 */
    private Long viewCount;
    /** 每日报名趋势（近 14 天） */
    private List<DailyCount> dailyRegistrations;
    /** 每日签到趋势 */
    private List<DailyCount> dailyCheckins;

    @Data
    @Builder
    public static class DailyCount {
        private String date;
        private long count;
    }
}
