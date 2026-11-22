package com.campusflow.biz.activity.controller;

import com.campusflow.biz.activity.dto.ActivityDetailVO;
import com.campusflow.biz.activity.dto.ActivityQuery;
import com.campusflow.biz.activity.dto.ActivityVO;
import com.campusflow.biz.activity.service.ActivityService;
import com.campusflow.common.result.ApiResult;
import com.campusflow.common.result.PageResult;
import com.campusflow.infra.auth.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 活动公开接口（学生端）：首页推荐 / 分类 / 搜索 / 详情。
 */
@Tag(name = "03-活动浏览")
@RestController
@RequestMapping("/api/v1/activities")
@RequiredArgsConstructor
public class ActivityPublicController {

    private final ActivityService activityService;

    @Operation(summary = "活动列表（分页/分类/搜索）")
    @GetMapping
    public ApiResult<PageResult<ActivityVO>> list(@Valid ActivityQuery query) {
        return ApiResult.ok(activityService.pageForStudent(query));
    }

    @Operation(summary = "首页推荐（按报名截止时间升序）")
    @GetMapping("/recommend")
    public ApiResult<List<ActivityVO>> recommend(@RequestParam(defaultValue = "6") int limit) {
        return ApiResult.ok(activityService.recommend(limit));
    }

    @Operation(summary = "活动详情（含我的报名状态）")
    @GetMapping("/{id}")
    public ApiResult<ActivityDetailVO> detail(@PathVariable Long id) {
        return ApiResult.ok(activityService.getDetail(id, AuthContext.getUserId()));
    }
}
