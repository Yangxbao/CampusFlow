package com.campusflow.biz.registration.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 报名结果视图对象：异步确认语义下返回"确认中"，前端轮询报名记录状态。
 */
@Data
@AllArgsConstructor
public class SignupResultVO {

    /** 报名记录ID（异步确认产生前可为 null） */
    private Long registrationId;
    /** 预留记录ID */
    private Long reservationId;
    /** 结果状态：PENDING_CONFIRM / DUPLICATE / FULL 等 */
    private String result;
    private String message;
}
