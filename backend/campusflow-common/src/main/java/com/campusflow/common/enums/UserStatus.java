package com.campusflow.common.enums;

import lombok.Getter;

/**
 * 用户状态。
 */
@Getter
public enum UserStatus {

    ACTIVE(0, "正常"),
    DISABLED(1, "禁用");

    private final int code;
    private final String desc;

    UserStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static UserStatus ofCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (UserStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }
}
