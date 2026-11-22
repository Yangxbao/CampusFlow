package com.campusflow.biz.activity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动创建/更新请求。
 */
@Data
public class ActivityUpsertRequest {

    @NotBlank(message = "活动标题不能为空")
    @Size(max = 128, message = "标题最长 128 字符")
    private String title;

    @Size(max = 20000, message = "描述过长")
    private String description;

    @NotNull(message = "分类不能为空")
    @Min(value = 0, message = "分类不合法")
    @Max(value = 6, message = "分类不合法")
    private Integer category;

    @Size(max = 255, message = "封面地址过长")
    private String coverUrl;

    @Size(max = 128, message = "地点过长")
    private String location;

    @NotNull(message = "活动开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "活动结束时间不能为空")
    private LocalDateTime endTime;

    @NotNull(message = "报名开始时间不能为空")
    private LocalDateTime signupStartTime;

    @NotNull(message = "报名截止时间不能为空")
    private LocalDateTime signupEndTime;

    @NotNull(message = "名额不能为空")
    @Min(value = 1, message = "名额至少为 1")
    @Max(value = 100000, message = "名额超出上限")
    private Integer capacity;
}
