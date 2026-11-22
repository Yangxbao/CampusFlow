package com.campusflow.biz.stats.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 管理端仪表盘视图对象。
 */
@Data
@Builder
public class AdminDashboardVO {

    private long totalUsers;
    private long totalActivities;
    private long pendingReviewCount;
    private long totalRegistrations;
    private long todayRegistrations;
    private long totalCheckins;
    private long pendingCompensationTasks;
    private long deadCompensationTasks;
    private long failedMessageCount;
    /** 分类活动分布 */
    private List<CategoryCount> categoryDistribution;
    /** 近 14 天报名趋势 */
    private List<ActivityStatsVO.DailyCount> registrationTrend;

    @Data
    @Builder
    public static class CategoryCount {
        private String categoryName;
        private long count;
    }
}
