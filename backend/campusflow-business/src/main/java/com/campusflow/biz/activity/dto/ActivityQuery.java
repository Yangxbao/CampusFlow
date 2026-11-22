package com.campusflow.biz.activity.dto;

import com.campusflow.common.page.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 活动列表查询（学生端首页/分类/搜索）。
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ActivityQuery extends PageQuery {

    /** 分类（可选） */
    @Min(0) @Max(6)
    private Integer category;

    /** 关键词：标题模糊搜索（可选） */
    private String keyword;

    /** 状态过滤：不传默认已发布/进行中（学生视角） */
    private Integer status;
}
