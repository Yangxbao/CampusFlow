package com.campusflow.biz.checkin.controller;

import com.campusflow.biz.checkin.dto.CheckinTokenVO;
import com.campusflow.biz.checkin.dto.ScanCheckinRequest;
import com.campusflow.biz.checkin.service.CheckinService;
import com.campusflow.common.result.ApiResult;
import com.campusflow.infra.auth.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 签到接口：学生获取电子凭证 / 组织者核销。
 */
@Tag(name = "06-二维码签到")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CheckinController {

    private final CheckinService checkinService;

    @Operation(summary = "获取我的电子签到凭证（QR 内容）")
    @GetMapping("/my/registrations/{registrationId}/checkin-token")
    public ApiResult<CheckinTokenVO> issueToken(@PathVariable Long registrationId) {
        return ApiResult.ok(checkinService.issueToken(registrationId, AuthContext.getUserId()));
    }

    @Operation(summary = "核销签到（组织者/管理员扫码）")
    @PostMapping("/checkins")
    public ApiResult<CheckinService.CheckinRecordVO> scan(@Valid @RequestBody ScanCheckinRequest request) {
        return ApiResult.ok(checkinService.scan(request, AuthContext.get()));
    }
}
