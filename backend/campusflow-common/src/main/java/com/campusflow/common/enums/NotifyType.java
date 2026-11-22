package com.campusflow.common.enums;

import lombok.Getter;

/**
 * 站内通知类型。
 */
@Getter
public enum NotifyType {

    REGISTER_SUCCESS(0, "报名成功"),
    REGISTER_CONFIRM_FAILED(1, "报名确认失败"),
    CANCEL_SUCCESS(2, "取消报名成功"),
    WAITLIST_JOINED(3, "候补排队成功"),
    WAITLIST_PROMOTED(4, "候补晋级通知"),
    WAITLIST_PROMOTE_TIMEOUT(5, "候补晋级超时"),
    CHECKIN_SUCCESS(6, "签到成功"),
    ACTIVITY_CHANGED(7, "活动变更"),
    ACTIVITY_CANCELLED(8, "活动取消"),
    REVIEW_PASSED(9, "活动审核通过"),
    REVIEW_REJECTED(10, "活动审核未通过");

    private final int code;
    private final String desc;

    NotifyType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
