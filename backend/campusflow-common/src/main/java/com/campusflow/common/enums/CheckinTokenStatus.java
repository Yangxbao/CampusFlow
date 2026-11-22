package com.campusflow.common.enums;

import lombok.Getter;

/**
 * 签到凭证状态。
 */
@Getter
public enum CheckinTokenStatus {

    VALID(0, "有效"),
    USED(1, "已使用"),
    EXPIRED(2, "已过期");

    private final int code;
    private final String desc;

    CheckinTokenStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static CheckinTokenStatus ofCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (CheckinTokenStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }
}
