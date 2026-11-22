package com.campusflow.common.enums;

import lombok.Getter;

/**
 * 活动状态机。
 * <pre>
 * DRAFT --提交审核--> PENDING_REVIEW --审核通过--> PUBLISHED --开赛时间--> ONGOING --结束时间--> FINISHED
 *                    |
 *                    +--审核拒绝--> REJECTED
 * PUBLISHED/ONGOING --组织者/管理员取消--> CANCELLED
 * </pre>
 */
@Getter
public enum ActivityStatus {

    DRAFT(0, "草稿"),
    PENDING_REVIEW(1, "待审核"),
    REJECTED(2, "审核未通过"),
    PUBLISHED(3, "已发布"),
    ONGOING(4, "进行中"),
    FINISHED(5, "已结束"),
    CANCELLED(6, "已取消");

    private final int code;
    private final String desc;

    ActivityStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static ActivityStatus ofCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (ActivityStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }

    /** 是否处于可报名状态 */
    public boolean canSignup() {
        return this == PUBLISHED || this == ONGOING;
    }

    /** 是否处于可取消活动状态 */
    public boolean canCancel() {
        return this == PUBLISHED || this == ONGOING;
    }
}
