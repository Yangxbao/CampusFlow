package com.campusflow.common.enums;

import lombok.Getter;

/**
 * 候补状态机。
 * <pre>
 * WAITING --名额释放晋级--> PROMOTED --30分钟内确认--> CONFIRMED
 *                                     +--超时未确认--> EXPIRED（名额重新释放并晋级下一位）
 * WAITING/PROMOTED --主动退出--> CANCELLED
 * </pre>
 */
@Getter
public enum WaitlistStatus {

    /** 排队等待中 */
    WAITING(0, "候补中"),
    /** 已晋级（获得名额保留期，需在截止时间前确认） */
    PROMOTED(1, "已晋级待确认"),
    /** 晋级后确认成功（报名生效） */
    CONFIRMED(2, "候补转正"),
    /** 晋级后超时未确认 */
    EXPIRED(3, "晋级超时"),
    /** 主动取消候补 */
    CANCELLED(4, "已取消");

    private final int code;
    private final String desc;

    WaitlistStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static WaitlistStatus ofCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (WaitlistStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }
}
